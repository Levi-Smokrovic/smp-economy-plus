package dev.smpeconomy.features.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Chat leaderboard {@code /leaderboard}, with category aliases. Not /top, which
 * belongs to Essential Commands.
 */
final class Leaderboard {
	static final String COMMAND = "leaderboard";

	enum Category {
		MONEY("money", "Balance", "balance"),
		KILLS("kills", "Player Kills", "pvp", "playerkills"),
		PLAYTIME("playtime", "Playtime", "time"),
		DEATHS("deaths", "Deaths", "death"),
		MOBS("mobs", "Mob Kills", "mobkills");

		final String id;
		final String label;
		private final List<String> aliases;

		Category(String id, String label, String... aliases) {
			this.id = id;
			this.label = label;
			this.aliases = List.of(aliases);
		}

		String format(long value) {
			return switch (this) {
				case MONEY -> Money.format(value);
				case PLAYTIME -> TimeUtil.formatTicks(value);
				default -> Msg.number(value);
			};
		}

		static Optional<Category> parse(String input) {
			if (input == null) return Optional.empty();
			String s = input.trim().toLowerCase(Locale.ROOT);
			for (Category c : values()) {
				if (c.id.equals(s) || c.aliases.contains(s)) return Optional.of(c);
			}
			return Optional.empty();
		}

		static List<String> ids() {
			List<String> ids = new ArrayList<>();
			for (Category c : values()) ids.add(c.id);
			return ids;
		}
	}

	record Entry(UUID id, String name, long value) {}

	/** Ties share a place and the next one is skipped: 1, 2, 2, 4. */
	record Ranked(int rank, Entry entry) {}

	record Page(int page, int pages, List<Ranked> rows) {}

	private static final Comparator<Entry> ORDER = Comparator.comparingLong(Entry::value).reversed()
		.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
		.thenComparing(e -> e.id().toString());

	private Leaderboard() {}

	/** Sorts descending, drops values of 0 or less and assigns places. */
	static List<Ranked> rank(Collection<Entry> entries) {
		List<Entry> sorted = new ArrayList<>();
		for (Entry e : entries) {
			if (e != null && e.value() > 0) sorted.add(e);
		}
		sorted.sort(ORDER);
		List<Ranked> out = new ArrayList<>(sorted.size());
		int rank = 0;
		long previous = Long.MIN_VALUE;
		for (int i = 0; i < sorted.size(); i++) {
			Entry e = sorted.get(i);
			if (e.value() != previous) {
				rank = i + 1;
				previous = e.value();
			}
			out.add(new Ranked(rank, e));
		}
		return out;
	}

	static Optional<Ranked> find(List<Ranked> list, UUID id) {
		if (id == null) return Optional.empty();
		for (Ranked r : list) {
			if (r.entry().id().equals(id)) return Optional.of(r);
		}
		return Optional.empty();
	}

	/** 1-based; 0 if not listed. */
	static int rankOf(List<Ranked> list, UUID id) {
		return find(list, id).map(Ranked::rank).orElse(0);
	}

	/** 1-based, clamped to 1..pages. An empty list has exactly one empty page. */
	static Page page(List<Ranked> list, int page, int pageSize) {
		int size = Math.max(1, pageSize);
		int pages = Math.max(1, (list.size() + size - 1) / size);
		int p = Math.max(1, Math.min(page, pages));
		int from = (p - 1) * size;
		int to = Math.min(list.size(), from + size);
		return new Page(p, pages, from < to ? List.copyOf(list.subList(from, to)) : List.of());
	}

	/** Excludes test and system accounts. */
	static Set<UUID> candidates() {
		Set<UUID> ids = new LinkedHashSet<>(StatsStore.all().keySet());
		ids.addAll(PlayerDataService.knownPlayers());
		ids.removeIf(id -> TestPlayers.isTestPlayer(id) || EconomyService.isSystemAccount(id));
		return ids;
	}

	/** Online live, offline from the cache. */
	static long value(Category c, UUID id) {
		return switch (c) {
			case MONEY -> EconomyService.balance(id);
			case KILLS -> StatsService.INSTANCE.playerKills(id);
			case PLAYTIME -> StatsService.playtimeTicks(id);
			case DEATHS -> StatsService.INSTANCE.deaths(id);
			case MOBS -> StatsService.INSTANCE.mobKills(id);
		};
	}

	static List<Ranked> collect(Category c) {
		List<Entry> entries = new ArrayList<>();
		if (c == Category.MONEY) {
			// Same source as /baltop: no treasury, no test accounts.
			for (EconomyService.Entry e : EconomyService.top()) entries.add(new Entry(e.id(), e.name(), e.balance()));
		} else {
			for (UUID id : candidates()) entries.add(new Entry(id, StatsService.name(id), value(c, id)));
		}
		return rank(entries);
	}

	static String rankColor(int rank) {
		return switch (rank) {
			case 1 -> "§6§l";
			case 2 -> "§f§l";
			case 3 -> "§c§l";
			default -> "§7";
		};
	}

	/** {@code viewer} null means console. */
	static List<MutableComponent> render(Category c, List<Ranked> all, int requestedPage, int pageSize, UUID viewer) {
		Page page = page(all, requestedPage, pageSize);
		List<MutableComponent> lines = new ArrayList<>();
		lines.add(Msg.prefixed("§6§lLeaderboard §8» §e" + c.label + " §8(Page " + page.page() + "/" + page.pages() + ")"));
		if (page.rows().isEmpty()) {
			lines.add(Msg.legacy(" §7No entries yet."));
		}
		for (Ranked r : page.rows()) {
			String name = r.entry().name();
			MutableComponent line = Msg.legacy(" " + rankColor(r.rank()) + "#" + r.rank() + " ");
			line.append(Msg.clickRun("§f" + name, "/stats " + name, "§7Show stats of §f" + name));
			line.append(Msg.legacy(" §8» §e" + c.format(r.entry().value())));
			lines.add(line);
		}
		if (viewer != null) {
			Optional<Ranked> own = find(all, viewer);
			lines.add(own.isPresent()
				? Msg.legacy(" §7Your rank: §e#" + own.get().rank() + " §8(§e" + c.format(own.get().entry().value()) + "§8)")
				: Msg.legacy(" §7You're not on this leaderboard yet."));
		}
		if (page.pages() > 1) {
			MutableComponent nav = Msg.legacy(" ");
			nav.append(page.page() > 1
				? Msg.clickRun("§e[« Back]", "/" + COMMAND + " " + c.id + " " + (page.page() - 1), "§7Page " + (page.page() - 1))
				: Msg.legacy("§8[« Back]"));
			nav.append(Msg.legacy(" "));
			nav.append(page.page() < page.pages()
				? Msg.clickRun("§e[Next »]", "/" + COMMAND + " " + c.id + " " + (page.page() + 1), "§7Page " + (page.page() + 1))
				: Msg.legacy("§8[Next »]"));
			lines.add(nav);
		}
		return lines;
	}

	static void send(Consumer<Component> sink, Category c, int page, UUID viewer) {
		for (MutableComponent line : render(c, collect(c), page, StatsFeature.config().pageSize, viewer)) sink.accept(line);
	}

	static List<MutableComponent> usage() {
		List<MutableComponent> lines = new ArrayList<>();
		lines.add(Msg.prefixed("§6§lLeaderboards §8» §7Pick a category:"));
		MutableComponent chips = Msg.legacy(" ");
		for (Category c : Category.values()) {
			chips.append(Msg.clickRun("§e[" + c.id + "]", "/" + COMMAND + " " + c.id, "§7" + c.label));
			chips.append(Msg.legacy(" "));
		}
		lines.add(chips);
		return lines;
	}
}
