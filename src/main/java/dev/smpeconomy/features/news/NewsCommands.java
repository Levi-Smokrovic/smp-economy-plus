package dev.smpeconomy.features.news;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.news.NewsData.NewsEntry;

/**
 * {@code /news} (GUI, smpeconomy.news). Admin (smpeconomy.admin.news, default OP 2): {@code /news trigger} (random event, as
 * automatic), {@code /news action <flashsale|bulkorder|fever> [minutes]}, {@code /news clear}, {@code /news auto [on|off]} (pause
 * automatic events, persisted), {@code /news status} (machine-readable).
 *
 * <p>{@code /news trigger <item> [percent] [minutes]} additionally needs {@link Perms#ADMIN_PRICES}: freely choosing item, factor
 * (up to +1000 %) and duration (up to 24 h) is the same lever on everyone's /sell as {@code /smpeco pricemod}, even without
 * percent, since one can trigger until a hype lands on the chosen item. Every admin start, {@code clear} and {@code auto} is
 * logged with the sender.
 */
final class NewsCommands {
	static final String NODE = "smpeconomy.news";
	static final String ADMIN_NODE = "smpeconomy.admin.news";
	static final List<String> ACTIONS = List.of("flashsale", "bulkorder", "fever");
	static final List<String> SWITCH = List.of("on", "off");
	static final int MIN_PERCENT = -95;
	static final int MAX_PERCENT = 1000;

	private NewsCommands() {}

	static void register(Supplier<NewsEngine> engine) {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("news")
				.requires(Perms.require(NODE))
				.executes(ctx -> show(ctx, engine.get()))
				.then(Commands.literal("trigger")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.executes(ctx -> report(ctx, "trigger", engine.get().startRandom(true)))
					.then(Commands.argument("item", ItemArgument.item(registryAccess))
						.executes(ctx -> trigger(ctx, engine.get(), null, 0))
						.then(Commands.argument("percent", StringArgumentType.word())
							.executes(ctx -> trigger(ctx, engine.get(), StringArgumentType.getString(ctx, "percent"), 0))
							.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 24 * 60))
								.executes(ctx -> trigger(ctx, engine.get(), StringArgumentType.getString(ctx, "percent"),
									IntegerArgumentType.getInteger(ctx, "minutes")))))))
				.then(actionCommand("action", engine))
				.then(Commands.literal("clear")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.executes(ctx -> clear(ctx, engine.get())))
				.then(Commands.literal("auto")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.executes(ctx -> auto(ctx, engine.get(), null))
					.then(Commands.argument("state", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SWITCH, builder))
						.executes(ctx -> auto(ctx, engine.get(), StringArgumentType.getString(ctx, "state")))))
				.then(Commands.literal("status")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.executes(ctx -> status(ctx, engine.get())))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> actionCommand(String literal, Supplier<NewsEngine> engine) {
		return Commands.literal(literal)
			.requires(Perms.requireAdmin(ADMIN_NODE))
			.then(Commands.argument("type", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ACTIONS, builder))
				.executes(ctx -> action(ctx, engine.get(), 0))
				.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 24 * 60))
					.executes(ctx -> action(ctx, engine.get(), IntegerArgumentType.getInteger(ctx, "minutes")))));
	}

	private static int show(CommandContext<CommandSourceStack> ctx, NewsEngine engine) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player != null) {
			NewsGui.open(player, engine);
			return 1;
		}
		List<NewsEntry> list = NewsBossBar.displayable(engine, engine.now());
		if (list.isEmpty()) Msg.info(ctx.getSource(), "No Breaking News right now.");
		for (NewsEntry e : list) Msg.send(ctx.getSource(), Msg.prefixed(NewsText.bossLine(engine, e, engine.now(), 0, 1)));
		return 1;
	}

	private static int trigger(CommandContext<CommandSourceStack> ctx, NewsEngine engine, String percent, int minutes) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		CommandSourceStack src = ctx.getSource();
		if (!Perms.checkAdmin(src, Perms.ADMIN_PRICES)) {
			SmpEconomy.LOG.info("[SMPEco] /news trigger {} by {} rejected (missing {})", Items.id(item), src.getTextName(), Perms.ADMIN_PRICES);
			Msg.error(src, "Targeted Breaking News (item, percent, duration) changes /sell prices like /smpeco pricemod – for that you "
				+ "also need §f" + Perms.ADMIN_PRICES + "§c. Without it: §f/news trigger §c(random story, like the automatic ones).");
			return 0;
		}
		double factor = 0;
		if (percent != null) {
			Optional<Double> f = parseFactor(percent);
			if (f.isEmpty()) {
				Msg.error(ctx.getSource(), "Invalid percentage '" + percent + "' – e.g. -50 or +150 (" + MIN_PERCENT + " to +"
					+ MAX_PERCENT + ", not 0).");
				return 0;
			}
			factor = f.get();
		}
		return report(ctx, "trigger " + Items.id(item), engine.triggerShock(item, factor, minutes, true));
	}

	private static int action(CommandContext<CommandSourceStack> ctx, NewsEngine engine, int minutes) {
		String type = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
		NewsEngine.Start start = switch (type) {
			case "flashsale" -> engine.startFlashSale(null, 0, minutes, true);
			case "bulkorder" -> engine.startBulkOrder(null, minutes, true);
			case "fever" -> engine.startAuctionFever(minutes, true);
			default -> NewsEngine.Start.fail("Unknown action '" + type + "' – " + String.join(", ", ACTIONS));
		};
		return report(ctx, "action " + type, start);
	}

	private static int clear(CommandContext<CommandSourceStack> ctx, NewsEngine engine) {
		int n = engine.clearAll();
		SmpEconomy.LOG.info("[SMPEco] /news clear by {}: {} events ended", ctx.getSource().getTextName(), n);
		long now = engine.now();
		List<NewsEntry> bulk = engine.runningBulkOrders();
		if (NewsBossBar.displayable(engine, now).isEmpty()) NewsFeature.bossBar().hide();
		else NewsFeature.bossBar().update(ctx.getSource().getServer(), engine, NewsFeature.config(), now);
		if (n > 0) Msg.success(ctx.getSource(), n + " Breaking News ended, prices back to normal.");
		else Msg.success(ctx.getSource(), bulk.isEmpty() ? "No Breaking News running." : "No price-changing Breaking News running.");
		// NpcOrders has no cancel: bulk orders stay visible until orders closes the order or time runs out
		for (NewsEntry e : bulk) {
			String how = e.orderId > 0 ? " §8– cancel: §7/orders admin cancel " + e.orderId : " §8– can't be cancelled.";
			Msg.send(ctx.getSource(), Msg.prefixed(Msg.legacy("§7NPC Bulk Order ").append(NewsText.item(e.item))
				.append(Msg.legacy(" §7keeps running in §f/orders §7(" + TimeUtil.formatDuration(e.endMs - now) + " left)" + how))));
		}
		return 1;
	}

	private static int auto(CommandContext<CommandSourceStack> ctx, NewsEngine engine, String value) {
		NewsConfig c = NewsFeature.config();
		String disabled = c.enabled ? "" : " §8(news.json: enabled=false – stays off anyway)";
		if (value == null) {
			Msg.info(ctx.getSource(), "Automatic Breaking News: " + (engine.autoPaused() ? "§cpaused" : "§aon") + disabled);
			return 1;
		}
		Optional<Boolean> on = parseSwitch(value);
		if (on.isEmpty()) {
			Msg.error(ctx.getSource(), "Unknown: '" + value + "' – on or off.");
			return 0;
		}
		SmpEconomy.LOG.info("[SMPEco] /news auto {} by {}", on.get() ? "on" : "off", ctx.getSource().getTextName());
		if (!on.get()) {
			engine.setAutoPaused(true);
			Msg.success(ctx.getSource(), "Automatic Breaking News paused §8(stays off after a restart; admin commands still work)§7.");
		} else {
			int dropped = engine.setAutoPaused(false);
			Msg.success(ctx.getSource(), "Automatic Breaking News back on" + (dropped > 0 ? " §8(" + dropped + " overdue story dropped)" : "")
				+ "§7." + disabled);
		}
		return 1;
	}

	static Optional<Boolean> parseSwitch(String s) {
		if (s == null) return Optional.empty();
		return switch (s.trim().toLowerCase(Locale.ROOT)) {
			case "on", "true" -> Optional.of(true);
			case "off", "false" -> Optional.of(false);
			default -> Optional.empty();
		};
	}

	private static int status(CommandContext<CommandSourceStack> ctx, NewsEngine engine) {
		long now = engine.now();
		NewsData d = engine.data();
		NewsConfig c = NewsFeature.config();
		long remaining = NewsPlanner.remaining(d);
		String flash = d.active.stream().filter(e -> e.kind() == NewsEntry.Type.FLASH_SALE && e.running(now)).map(e -> e.category)
			.findFirst().orElse("-");
		Msg.send(ctx.getSource(), Msg.legacy("§7news status active=" + d.active.size() + " fever=" + engine.feverActive() + " flash=" + flash
			+ " enabled=" + c.enabled + " auto=" + (engine.autoPaused() ? "off" : "on") + " running=" + engine.runningCount(now) + "/" + c.maxActive
			+ " progress=" + d.progressMs / 1000 + "s target=" + d.targetMs / 1000 + "s next=" + (remaining < 0 ? "-" : remaining / 1000 + "s")
			+ " interval=" + c.minIntervalMinutes + "-" + c.maxIntervalMinutes + "m quiet=" + NewsPlanner.isQuiet(now, NewsPlanner.Rules.of(c, TimeUtil.zone()))
			+ " autoStarted=" + d.autoStarted + " last=" + (d.lastEventAt > 0 ? NewsText.when(d.lastEventAt) : "-")
			+ " history=" + d.history.size() + " bossbar=" + NewsFeature.bossBar().isShown() + " villager="
			+ (NewsVillagerRoutes.items() == null ? "-" : NewsVillagerRoutes.items().size())));
		for (NewsEntry e : d.active) Msg.send(ctx.getSource(), Msg.legacy("§7" + entryLine(engine, e, now)));
		return 1;
	}

	static String entryLine(NewsEngine engine, NewsEntry e, long now) {
		return "news entry id=" + e.id + " type=" + e.type + " item=" + (e.item.isEmpty() ? "-" : e.item)
			+ (e.category.isEmpty() ? "" : " category=" + e.category) + " factor=" + String.format(Locale.ROOT, "%.2f", e.factor)
			+ " pct=" + e.shownPercent + " live=" + engine.livePercent(e) + " amount=" + e.amount + " order=" + e.orderId
			+ " remaining=" + Math.max(0, (e.endMs - now) / 1000) + "s members=" + e.members.size();
	}

	/**
	 * Marks started events with the sender in the log. Market shocks log as warnings like {@code /smpeco pricemod}, since they
	 * change everyone's /sell prices.
	 */
	private static int report(CommandContext<CommandSourceStack> ctx, String command, NewsEngine.Start start) {
		if (!start.ok()) {
			Msg.error(ctx.getSource(), start.error() == null ? "Nothing started." : start.error());
			return 0;
		}
		String actor = ctx.getSource().getTextName();
		for (NewsEntry e : start.entries()) {
			long minutes = Math.round((e.endMs - e.startMs) / 60_000.0);
			if (e.kind().isShock()) {
				SmpEconomy.LOG.warn("[SMPEco] /news {} by {}: {} {} factor {} for {} min, items {}", command, actor, e.type, e.item,
					String.format(Locale.ROOT, "%.2f", e.factor), minutes, e.members);
			} else {
				SmpEconomy.LOG.info("[SMPEco] /news {} by {}: {} item={} category={} factor={} amount={} for {} min", command, actor, e.type,
					e.item, e.category.isEmpty() ? "-" : e.category, String.format(Locale.ROOT, "%.2f", e.factor), e.amount, minutes);
			}
		}
		for (NewsEntry e : start.entries()) {
			String what = switch (e.kind()) {
				case CRASH, HYPE -> e.item + " " + NewsText.pct(e.shownPercent) + " (factor " + String.format(Locale.ROOT, "%.2f", e.factor)
					+ ", " + e.members.size() + (e.members.size() == 1 ? " item" : " items") + (e.kind() == NewsEntry.Type.HYPE && cappedNow(e) ? ", sell capped" : "") + ")";
				case FLASH_SALE -> "Flash Sale category=" + e.category + " " + NewsText.pct(e.shownPercent) + " sample=" + sampleItem(e);
				case BULK_ORDER -> "Bulk order " + e.amount + "x " + e.item + (e.orderId > 0 ? " (order #" + e.orderId + ")" : "");
				case AUCTION_FEVER -> "Auction Fever";
			};
			Msg.success(ctx.getSource(), "Started: §f" + what + " §7– " + TimeUtil.formatDuration(e.endMs - e.startMs) + " §8[id=" + e.id
				+ " type=" + e.type + "]");
			for (String w : warnings(e)) Msg.send(ctx.getSource(), Msg.prefixed(w));
		}
		return 1;
	}

	/**
	 * Notes for admin shocks on items automatic selection deliberately never hits (the admin trigger allows them, like {@code
	 * /smpeco pricemod}).
	 */
	static List<String> warnings(NewsEntry e) {
		if (!e.kind().isShock()) return List.of();
		List<String> list = new java.util.ArrayList<>();
		NewsConfig c = NewsFeature.config();
		if (c.isMarketSold(e.item) && (e.kind() == NewsEntry.Type.HYPE || !c.crashMarketSold)) {
			list.add("§e⚠ Trader goods: §7" + (e.kind() == NewsEntry.Type.HYPE ? "Hype" : "Crash")
				+ " can be exploited with Trader offers §8(never automatic)");
		}
		if (e.kind() == NewsEntry.Type.HYPE) {
			// Shop hype: the engine skips family members without a pivot (NewsEngine#hypeSkips)
			List<String> skipped = new java.util.ArrayList<>();
			for (String m : NewsFeature.config().familyIds(e.item)) {
				if (!e.members.contains(m) && Items.byId(m).isPresent()) skipped.add(m);
			}
			if (!skipped.isEmpty()) {
				list.add("§7Not in the hype §8(no shop pivot – selling would be uncapped)§7: §f" + String.join(", ", skipped));
			}
		}
		return list;
	}

	private static boolean cappedNow(NewsEntry e) {
		NewsEngine engine = NewsFeature.engine();
		return Items.byId(e.item).map(i -> NewsPrices.sellCapped(engine.source, i, e.factor)).orElse(false);
	}

	private static String sampleItem(NewsEntry e) {
		NewsEngine engine = NewsFeature.engine();
		Item best = null;
		int bestPct = 0;
		for (Item item : engine.itemsOf(e.category)) {
			if (engine.flashExcluded(item)) continue;
			NewsPrices.Change ch = NewsPrices.flash(engine.flashHookId, item, e.factor);
			if (ch.present() && ch.percent() < bestPct) {
				bestPct = ch.percent();
				best = item;
			}
		}
		return best == null ? "-" : Items.id(best);
	}

	/** "+150" gives 2.5, "-50" gives 0.5; empty for 0, outside [-95, +1000] or not an integer. */
	static Optional<Double> parseFactor(String s) {
		if (s == null) return Optional.empty();
		String t = s.trim();
		if (t.endsWith("%")) t = t.substring(0, t.length() - 1);
		if (t.startsWith("+")) t = t.substring(1);
		if (t.isEmpty() || t.startsWith("+")) return Optional.empty();
		int p;
		try {
			p = Integer.parseInt(t);
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
		if (p == 0 || p < MIN_PERCENT || p > MAX_PERCENT) return Optional.empty();
		return Optional.of(1.0 + p / 100.0);
	}
}
