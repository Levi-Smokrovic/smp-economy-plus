package dev.smpeconomy.features.news;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.news.NewsData.NewsEntry;
import dev.smpeconomy.features.news.NewsData.NewsEntry.Type;

/**
 * Breaking news texts. Item names are translatable components (client language). Percentages are always the effective values
 * through {@link NewsPrices}, never more than is paid.
 */
final class NewsText {
	static final String TITLE = "§c§lBREAKING NEWS";
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);

	private NewsText() {}

	static MutableComponent item(String id) {
		Optional<Item> item = Items.byId(id);
		return item.map(i -> Msg.itemName(i).withStyle(ChatFormatting.WHITE)).orElseGet(() -> Component.literal(id).withStyle(ChatFormatting.WHITE));
	}

	static String pct(int percent) {
		return NewsPrices.pct(percent);
	}

	static String pctColored(NewsEntry e, int percent) {
		return (percent < 0 && e.kind() != Type.FLASH_SALE ? "§c▼ " : "§a▲ ") + pct(percent);
	}

	/**
	 * Current flash sale category name from shop.json, otherwise the one stored at start, so older history entries show the
	 * current translated name.
	 */
	static String categoryName(NewsEntry e) {
		if (e.category != null && !e.category.isEmpty()) {
			for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
				if (c.id().equals(e.category) && c.name() != null && !c.name().isBlank()) return c.name();
			}
		}
		return e.categoryName;
	}

	static String typeLabel(NewsEntry e) {
		return switch (e.kind()) {
			case CRASH -> "Crash";
			case HYPE -> "Hype";
			case FLASH_SALE -> "Flash Sale";
			case BULK_ORDER -> "NPC Bulk Order";
			case AUCTION_FEVER -> "Auction Fever";
		};
	}

	static String when(long ms) {
		return WHEN.format(Instant.ofEpochMilli(ms).atZone(TimeUtil.zone()));
	}

	static String minutes(long ms) {
		return Math.max(1, Math.round(ms / 60_000.0)) + " min";
	}

	static MutableComponent shortLine(NewsEntry e) {
		return switch (e.kind()) {
			case CRASH -> item(e.item).append(Msg.legacy(" §c" + pct(e.shownPercent) + "!"));
			case HYPE -> item(e.item).append(Msg.legacy(" §a" + pct(e.shownPercent) + "!"));
			case FLASH_SALE -> Msg.legacy("§aFlash Sale: §f" + categoryName(e) + " §aup to " + pct(e.shownPercent) + "!");
			case BULK_ORDER -> Msg.legacy("§6NPC Bulk Order: §f" + Msg.number(e.amount) + "× ").append(item(e.item)).append(Msg.legacy("§6!"));
			case AUCTION_FEVER -> Msg.legacy("§eAuction Fever in the /ah!");
		};
	}

	static MutableComponent subtitle(List<NewsEntry> group) {
		MutableComponent c = Component.empty();
		for (int i = 0; i < group.size(); i++) {
			if (i > 0) c.append(Msg.legacy(" §8· "));
			c.append(shortLine(group.get(i)));
		}
		return c;
	}

	static BossEvent.BossBarColor color(NewsEntry e) {
		return e.kind() == Type.CRASH ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.GREEN;
	}

	static float progress(NewsEntry e, long now) {
		long total = e.endMs - e.startMs;
		if (total <= 0 || now >= e.endMs) return 0f;
		return Math.max(0f, Math.min(1f, (float) (e.endMs - now) / total));
	}

	static MutableComponent bossLine(NewsEngine engine, NewsEntry e, long now, int index, int total) {
		MutableComponent c = Msg.legacy(total > 1 ? "§8[" + (index + 1) + "/" + total + "] " : "");
		if (e.kind().isShock() && !e.running(now)) {
			return c.append(Msg.legacy("§7⚡ ")).append(item(e.item)).append(Msg.legacy(" §8» §7Price returning to normal §8(§7"
				+ TimeUtil.formatDuration(e.endMs + e.fadeOutMs - now) + "§8)"));
		}
		String left = " §8| §f" + TimeUtil.formatDuration(e.endMs - now) + " §7left";
		int live = engine.livePercent(e);
		return switch (e.kind()) {
			case CRASH -> c.append(Msg.legacy("§c§l⚡ CRASH §8» ")).append(item(e.item)).append(Msg.legacy(" " + pctColored(e, live) + left));
			case HYPE -> c.append(Msg.legacy("§a§l⚡ HYPE §8» ")).append(item(e.item)).append(Msg.legacy(" " + pctColored(e, live) + left));
			case FLASH_SALE -> c.append(Msg.legacy("§a§l⚡ FLASH SALE §8» §f" + categoryName(e) + " §aup to " + pct(e.shownPercent) + left));
			case BULK_ORDER -> c.append(Msg.legacy("§6§l⚡ BULK ORDER §8» §f" + Msg.number(e.amount) + "× ")).append(item(e.item))
				.append(Msg.legacy(" §7in §f/orders" + left));
			case AUCTION_FEVER -> c.append(Msg.legacy("§e§l⚡ AUCTION FEVER §8» §7NPC buyers buy twice as fast" + left));
		};
	}

	static MutableComponent chatLine(NewsEngine engine, List<NewsEntry> group) {
		MutableComponent hover = Component.empty();
		for (int i = 0; i < group.size(); i++) {
			if (i > 0) hover.append(Component.literal("\n\n"));
			hover.append(details(engine, group.get(i)));
		}
		hover.append(Msg.legacy("\n\n§eClick: §f/news"));
		MutableComponent body = Msg.legacy(TITLE + " §8» ").append(subtitle(group)).append(Msg.legacy(" §8[§eDetails§8]"));
		return Msg.prefixed(body.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)).withClickEvent(new ClickEvent.RunCommand(CommandRegistry.publicCommand("/news")))));
	}

	static MutableComponent details(NewsEngine engine, NewsEntry e) {
		MutableComponent c = Component.empty();
		long duration = e.endMs - e.startMs;
		switch (e.kind()) {
			case CRASH, HYPE -> {
				c.append(Msg.legacy((e.kind() == Type.CRASH ? "§c§lCRASH " : "§a§lHYPE "))).append(item(e.item));
				Optional<Item> item = Items.byId(e.item);
				if (item.isPresent()) {
					NewsPrices.Change sell = NewsPrices.sell(engine.source, item.get(), e.factor);
					if (sell.present()) {
						c.append(Msg.legacy("\n§7Sell: " + Msg.moneyStr(sell.before()) + " §8→ " + Msg.moneyStr(sell.after())
							+ " §8(" + pct(sell.percent()) + ")"));
						if (e.kind() == Type.HYPE && NewsPrices.sellCapped(engine.source, item.get(), e.factor)) {
							c.append(Msg.legacy("\n§8Shop item: sell price capped (price cap)"));
						}
					}
					NewsPrices.Change shop = NewsPrices.shop(engine.source, item.get(), e.factor);
					if (shop.present()) {
						c.append(Msg.legacy(shop.after() == shop.before()
							? "\n§7Shop: §e" + Money.format(shop.before()) + " §8(unchanged" + (e.kind() == Type.CRASH ? " – a crash only lowers selling" : "") + ")"
							: "\n§7Shop: §e" + Money.format(shop.before()) + " §8→ §e" + Money.format(shop.after())));
					}
				}
				c.append(Msg.legacy("\n§7Duration: §f" + minutes(duration) + (e.fadeOutMs > 0 ? " §8(then " + minutes(e.fadeOutMs) + " transition)" : "")));
				MutableComponent fam = familyLine(e);
				if (fam != null) c.append(Component.literal("\n")).append(fam);
			}
			case FLASH_SALE -> {
				c.append(Msg.legacy("§a§lFLASH SALE §f" + categoryName(e) + "\n§7Discount in the §f/shop§7: §aup to "
					+ pct(e.shownPercent) + "\n§8Minimum prices still apply."));
				MutableComponent excluded = excludedLine(engine, e);
				if (excluded != null) c.append(Component.literal("\n")).append(excluded);
				c.append(Msg.legacy("\n§7Duration: §f" + minutes(duration)));
			}
			case BULK_ORDER -> c.append(Msg.legacy("§6§lNPC BULK ORDER\n§7NPCs are buying §f" + Msg.number(e.amount) + "× "))
				.append(item(e.item)).append(Msg.legacy("\n§7Deliver via §f/orders\n§7Duration: §f" + minutes(duration)));
			case AUCTION_FEVER -> c.append(Msg.legacy("§e§lAUCTION FEVER\n§7NPC buyers buy player listings in the §f/ah\n"
				+ "§7twice as fast and pay a bit more.\n§8Price caps still apply.\n§7Duration: §f" + minutes(duration)));
		}
		return c;
	}

	static MutableComponent familyLine(NewsEntry e) {
		List<String> others = new ArrayList<>(e.members);
		others.remove(e.item);
		if (others.isEmpty()) return null;
		MutableComponent c = Msg.legacy("§8Also applies to: ");
		int shown = Math.min(3, others.size());
		for (int i = 0; i < shown; i++) {
			if (i > 0) c.append(Msg.legacy("§8, "));
			String id = others.get(i);
			c.append(Items.byId(id).map(it -> Msg.itemName(it).withStyle(ChatFormatting.GRAY)).orElseGet(() -> Component.literal(id)));
		}
		if (others.size() > shown) c.append(Msg.legacy("§8 and " + (others.size() - shown) + " more"));
		return c;
	}

	static MutableComponent excludedLine(NewsEngine engine, NewsEntry e) {
		List<Item> excluded = engine.flashExcludedIn(e.category);
		if (excluded.isEmpty()) return null;
		MutableComponent c = Msg.legacy("§8No discount: ");
		int shown = Math.min(3, excluded.size());
		for (int i = 0; i < shown; i++) {
			if (i > 0) c.append(Msg.legacy("§8, "));
			c.append(Msg.itemName(excluded.get(i)).withStyle(ChatFormatting.GRAY));
		}
		if (excluded.size() > shown) c.append(Msg.legacy("§8 and " + (excluded.size() - shown) + " more"));
		return c;
	}

	static MutableComponent endLine(NewsEntry e) {
		return switch (e.kind()) {
			case CRASH, HYPE -> Msg.prefixed(Msg.legacy("§7Breaking News over: ").append(item(e.item))
				.append(Msg.legacy(" §7returns to its normal price" + (e.fadeOutMs > 0 ? " in " + minutes(e.fadeOutMs) : "") + ".")));
			case FLASH_SALE -> Msg.prefixed("§7Flash Sale over: §f" + categoryName(e) + "§7.");
			case BULK_ORDER -> Msg.prefixed(Msg.legacy("§7NPC Bulk Order over: ").append(item(e.item)).append(Msg.legacy("§7.")));
			case AUCTION_FEVER -> Msg.prefixed("§7Auction Fever is over.");
		};
	}

	static MutableComponent joinHint(int count) {
		return Msg.prefixed(Msg.legacy("§7Live now: §f" + count + " §7Breaking News – ")
			.append(Msg.clickRun("§e[/news]", "/news", "§7Open details")));
	}
}
