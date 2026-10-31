package dev.smpeconomy.core.test;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.fail;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;
import static dev.smpeconomy.core.test.Assert.notNull;

import com.mojang.brigadier.ParseResults;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import dev.smpeconomy.core.AdminLog;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TraderGoods;

/**
 * Money sources outside shop and recipes (villagers, bonemeal growth), PlacedBlockTracker fail-closed behavior, saving
 * with vanilla, and the admin log.
 */
public final class SafetySelfTests {
	private static final String F = "core";
	/** Maximum sell value per item for leaf-like blocks from bonemeal growth, in cents. */
	static final long CANOPY_MAX_CENTS = 10;
	/** A planted huge fungus may yield at most this multiple of its shop cost (fungus + bonemeal). */
	static final double FUNGUS_MAX_MULTIPLE = 6.0;

	private SafetySelfTests() {}

	public static void register() {
		SelfTests.register(F, "villager-trades", SafetySelfTests::villagerTrades);
		SelfTests.register(F, "villager-trades-snapshot", SafetySelfTests::villagerTradesSnapshot);
		SelfTests.register(F, "growth-canopy", ctx -> growthCanopy());
		SelfTests.register(F, "placed-fail-closed", SafetySelfTests::placedFailClosed);
		SelfTests.register(F, "datastore-vanilla-save", SafetySelfTests::dataStoreVanillaSave);
		SelfTests.register(F, "datastore-start-files", ctx -> dataStoreStartFiles());
		SelfTests.register(F, "admin-log", SafetySelfTests::adminLog);
		SelfTests.register(F, "prices-known-leaks", ctx -> pricesKnownLeaks());
	}

	/**
	 * Sell prices with a known money leak (villager trades, huge fungus growth). The shipped prices.json must contain
	 * none of them ({@code null}: the item must not be sellable at all).
	 */
	static final List<Object[]> LEAK_PRICES = List.of(
		new Object[] {"minecraft:emerald", 2500L}, new Object[] {"minecraft:emerald_block", 22500L},
		new Object[] {"minecraft:emerald_ore", 2500L}, new Object[] {"minecraft:deepslate_emerald_ore", 2500L},
		new Object[] {"minecraft:quartz_block", null}, new Object[] {"minecraft:quartz_pillar", null},
		new Object[] {"minecraft:chiseled_quartz_block", null}, new Object[] {"minecraft:quartz_bricks", null},
		new Object[] {"minecraft:smooth_quartz", null}, new Object[] {"minecraft:bricks", null},
		new Object[] {"minecraft:nether_wart_block", 100L}, new Object[] {"minecraft:warped_wart_block", 100L},
		new Object[] {"minecraft:shroomlight", 100L});

	private static void pricesKnownLeaks() throws Exception {
		String bundled = dev.smpeconomy.core.ConfigManager.readBundled("smpeconomy_defaults/prices.json").orElseThrow();
		java.util.Map<String, Long> defaults = DataStore.gson().fromJson(bundled,
			new com.google.gson.reflect.TypeToken<java.util.Map<String, Long>>() {}.getType());
		List<String> leaks = new ArrayList<>();
		for (Object[] leak : LEAK_PRICES) {
			Long price = defaults.get((String) leak[0]);
			if (price == null) continue;
			if (leak[1] == null || price.longValue() == (Long) leak[1]) leaks.add(leak[0] + "=" + price);
		}
		eq(List.of(), leaks, "bundled prices.json without known leak prices");
	}

	/**
	 * Trade results are marked (mixin active) and so unsellable. Through placing and breaking or a recipe, villager
	 * goods yield at most {@link TraderGoods#LIMIT_CENTS_PER_EMERALD} cents per emerald.
	 */
	/**
	 * Expected values of {@link #villagerTradesSnapshot} per MC version. Trades depend on the version; prices are not
	 * involved.
	 */
	// 26.x checked line by line against 1.21.11 (files from Snapshot.write). Differences come only from 26.x vanilla
	// data (costs capped at stack size: book and quill 1 rather than 2; name tag at the wandering trader rather than
	// the librarian; cake 3 emeralds; new items) plus 31 skipped trades: 10 explorer maps without a world position and
	// 21 rebalance books whose biome enchantment tags exist only in the datapack (1.21.11 skips those too).
	static final java.util.Map<String, String> TRADER_GOODS_SNAPSHOT = java.util.Map.of(
		"1.21.11", "offers=251 skipped=3",
		"26.1.2", "offers=274 skipped=31",
		"26.2", "offers=275 skipped=31",
		"26.3", "offers=278 skipped=31");

	/**
	 * Trader goods rating through {@code core.compat.VanillaTrades}. The leak list depends on prices and so is only in
	 * the detail, as a hash for comparing two runs with the same prices.
	 */
	private static void villagerTradesSnapshot(TestContext ctx) {
		TraderGoods.Analysis a = TraderGoods.analyze(ctx.server());
		List<String> leaks = new ArrayList<>();
		for (TraderGoods.Leak l : a.leaks()) leaks.add(l.describe());
		// Comparison data for ports: all offers as analyze reads them, plus rated leaks.
		List<String> offers = new ArrayList<>();
		for (dev.smpeconomy.core.compat.VanillaTrades.Offer o : dev.smpeconomy.core.compat.VanillaTrades.offers(ctx.overworld(),
				dev.smpeconomy.core.compat.VanillaTrades.Kind.ALL, dev.smpeconomy.core.compat.VanillaTrades.Experimental.ALWAYS, null).offers()) {
			offers.add((o.wandering() ? "W " : "V ") + Snapshot.stack(o.costA()) + " + " + Snapshot.stack(o.costB()) + " -> " + Snapshot.stack(o.result()));
		}
		Snapshot.write("trader-goods-offers", offers);
		Snapshot.write("trader-goods-leaks", leaks);
		Snapshot.check("trader goods rating", TRADER_GOODS_SNAPSHOT, "offers=" + a.offers() + " skipped=" + a.skipped());
		ctx.detail("offers=" + a.offers() + " skipped=" + a.skipped() + " leaks " + Snapshot.of(leaks) + " max=" + a.villagerMax());
	}

	private static void villagerTrades(TestContext ctx) {
		Item bread = net.minecraft.world.item.Items.BREAD;
		MerchantOffer offer = new MerchantOffer(new ItemCost(net.minecraft.world.item.Items.EMERALD, 1), new ItemStack(bread, 6), 16, 1, 0.05F);
		ItemStack result = offer.assemble();
		isTrue(TraderGoods.isMarked(result), "trade result marked (MerchantOffer mixin active)");
		isFalse(Items.isPristine(result), "marked means not pristine (no /sell, no delivery)");
		eq(6, result.getCount(), "count unchanged");
		isTrue(result.is(bread), "item unchanged");
		isFalse(TraderGoods.isMarked(offer.getResult()), "the offer itself stays unchanged");
		isTrue(ItemStack.isSameItemSameComponents(result, offer.assemble()), "marked stacks stack");
		notNull(result.get(DataComponents.LORE), "lore note");

		ItemStack emerald = new MerchantOffer(new ItemCost(net.minecraft.world.item.Items.WHEAT, 20),
			new ItemStack(net.minecraft.world.item.Items.EMERALD), 16, 2, 0.05F).assemble();
		isFalse(TraderGoods.isMarked(emerald), "emeralds stay unchanged");
		isTrue(Items.isPristine(emerald), "emerald still sellable");
		ItemStack named = new ItemStack(bread);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Named bread"));
		ItemStack namedResult = new MerchantOffer(new ItemCost(net.minecraft.world.item.Items.EMERALD, 1), named, 16, 1, 0.05F).assemble();
		isFalse(TraderGoods.isMarked(namedResult), "already modified results (books, armor...) not marked in addition");

		TraderGoods.Analysis a = TraderGoods.analyze(ctx.server());
		isTrue(a.offers() >= 60, "emerald purchases rated: " + a.offers());
		List<String> bad = new ArrayList<>();
		for (TraderGoods.Leak l : a.villagerViolations()) bad.add(l.describe());
		eq(List.of(), bad, "villager goods above " + TraderGoods.LIMIT_CENTS_PER_EMERALD + " ¢/emerald (lower or remove the price)");
		long value = TraderGoods.emeraldValue();
		isTrue(value >= BasePrices.sellPrice(net.minecraft.world.item.Items.EMERALD).orElse(0), "emerald value >= sell price");
		isTrue(value <= Math.max(TraderGoods.LIMIT_CENTS_PER_EMERALD, BasePrices.sellPrice(net.minecraft.world.item.Items.EMERALD).orElse(0)),
			"emerald value within limit: " + value);
		String topVillager = a.leaks().stream().filter(l -> !l.wandering()).findFirst().map(TraderGoods.Leak::describe).orElse("none");
		String topWandering = a.leaks().stream().filter(TraderGoods.Leak::wandering).findFirst().map(TraderGoods.Leak::describe).orElse("none");
		ctx.detail(a.offers() + " purchases, " + a.skipped() + " skipped; highest villager path: " + topVillager
			+ "; wandering trader (report only): " + topWandering);
	}

	/**
	 * Leaf-like blocks from bonemeal growth are nearly worthless, and a planted huge fungus yields at most {@value
	 * #FUNGUS_MAX_MULTIPLE} times its shop cost. Expected yield per growth ({@code HugeFungusFeature}, planted,
	 * simulated 40,000 times): 9.2 stems, 63 wart blocks, 2.0 shroomlights, crimson also 6.8 weeping vines; bonemeal
	 * works 40% of the time (2.5 per growth).
	 */
	private static void growthCanopy() {
		List<Item> canopy = new ArrayList<>();
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LEAVES)) canopy.add(h.value());
		canopy.addAll(List.of(net.minecraft.world.item.Items.NETHER_WART_BLOCK, net.minecraft.world.item.Items.WARPED_WART_BLOCK,
			net.minecraft.world.item.Items.SHROOMLIGHT, net.minecraft.world.item.Items.MUSHROOM_STEM, net.minecraft.world.item.Items.RED_MUSHROOM_BLOCK,
			net.minecraft.world.item.Items.BROWN_MUSHROOM_BLOCK));
		isTrue(canopy.size() > 6, "leaves tag read");
		List<String> pricey = new ArrayList<>();
		for (Item i : canopy) {
			long sell = BasePrices.sellPrice(i).orElse(0);
			if (sell > CANOPY_MAX_CENTS) pricey.add(Items.id(i) + "=" + sell);
		}
		eq(List.of(), pricey, "leaves/wart blocks/shroomlight/huge fungus blocks above " + CANOPY_MAX_CENTS + " ¢ (bonemeal grows them by the dozen)");

		OptionalLong bone = BasePrices.shopBuyPrice(net.minecraft.world.item.Items.BONE_MEAL);
		for (boolean crimson : new boolean[] {true, false}) {
			Item fungus = crimson ? net.minecraft.world.item.Items.CRIMSON_FUNGUS : net.minecraft.world.item.Items.WARPED_FUNGUS;
			OptionalLong fungusPrice = BasePrices.shopBuyPrice(fungus);
			if (fungusPrice.isEmpty() || bone.isEmpty()) continue; // not in the shop: no buy path
			double value = 9.2 * sell(crimson ? net.minecraft.world.item.Items.CRIMSON_STEM : net.minecraft.world.item.Items.WARPED_STEM)
				+ 63.0 * sell(crimson ? net.minecraft.world.item.Items.NETHER_WART_BLOCK : net.minecraft.world.item.Items.WARPED_WART_BLOCK)
				+ 2.0 * sell(net.minecraft.world.item.Items.SHROOMLIGHT)
				+ (crimson ? 6.8 * sell(net.minecraft.world.item.Items.WEEPING_VINES) : 0);
			double cost = fungusPrice.getAsLong() + 2.5 * bone.getAsLong();
			isTrue(value <= FUNGUS_MAX_MULTIPLE * cost, Items.id(fungus) + ": huge fungus yields " + Math.round(value) + " ¢ at "
				+ Math.round(cost) + " ¢ cost (> " + FUNGUS_MAX_MULTIPLE + "×)");
		}
	}

	private static long sell(Item item) {
		return BasePrices.sellPrice(item).orElse(0);
	}

	private static void placedFailClosed(TestContext ctx) throws Exception {
		Path dir = DataStore.dataDir().resolve("selftest-placed");
		Runnable wipe = () -> {
			try {
				if (Files.isDirectory(dir)) {
					try (var files = Files.list(dir)) {
						for (Path p : files.toList()) Files.deleteIfExists(p);
					}
				}
				Files.deleteIfExists(dir);
			} catch (Exception ignored) {
			}
		};
		ctx.cleanup(wipe);
		wipe.run();
		Files.createDirectories(dir);
		Path file = dir.resolve(PlacedBlockTracker.FILE);

		PlacedBlockTracker.Inspection fresh = PlacedBlockTracker.inspect(file, List.of());
		isTrue(fresh.reliable() && fresh.fresh(), "missing without traces: new");
		PlacedBlockTracker.Inspection traced = PlacedBlockTracker.inspect(file, List.of("restore point X"));
		isFalse(traced.reliable(), "missing despite restore point copy: unreliable");
		isTrue(traced.problem().contains("missing"), "reason: missing");

		Files.write(file, new byte[] {1, 2, 3});
		isFalse(PlacedBlockTracker.isReadable(file), "garbage is not readable (no copy into the restore point)");
		PlacedBlockTracker.Inspection broken = PlacedBlockTracker.inspect(file, List.of());
		isFalse(broken.reliable(), "broken: unreliable");
		notNull(broken.store(), "broken file moved aside");
		eq(0, broken.store().size(), "no marks");
		isFalse(Files.exists(file), "broken file moved");
		PlacedBlockTracker.Inspection afterCorrupt = PlacedBlockTracker.inspect(file, List.of());
		isFalse(afterCorrupt.reliable(), "missing next to .corrupt-*: still unreliable (also after a restart)");

		PlacedBlockTracker.Store s = new PlacedBlockTracker.Store();
		long pos = BlockPos.asLong(1, 2, 3);
		s.add("minecraft:overworld", pos);
		Files.write(file, s.toBytes());
		isTrue(PlacedBlockTracker.isReadable(file), "valid file readable");
		PlacedBlockTracker.Inspection ok = PlacedBlockTracker.inspect(file, List.of("restore point X"));
		isTrue(ok.reliable() && !ok.fresh(), "valid: reliable");
		isTrue(ok.store().contains("minecraft:overworld", pos), "mark read");

		isTrue(PlacedBlockTracker.reliable(), "live tracker reliable: " + PlacedBlockTracker.problem().orElse(""));
		ServerLevel level = ctx.overworld();
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, new BlockPos(2_345_678, 70, -2_345_678)), "untouched block natural");
		List<String> copies = DataStore.restorePointsContaining(PlacedBlockTracker.FILE);
		ctx.detail("restore points with " + PlacedBlockTracker.FILE + ": " + copies.size());
	}

	/**
	 * Saves happen on the vanilla save (PlayerList.saveAll), not in the tick. Changes and player requests stay in
	 * memory until then, so a crash rolls world, players and mod data back together.
	 */
	private static void dataStoreVanillaSave(TestContext ctx) {
		if (DataStore.millisSinceCommitPoint() > DataStore.FALLBACK_INTERVAL_MS - 60_000) {
			ctx.detail("skipped: no vanilla save for a long time (fallback save is due)");
			return;
		}
		long flushes = DataStore.flushCount();
		PlayerDataService.markDirty();
		DataStore.requestPlayerSave(UUID.randomUUID()); // offline: discarded when saving
		DataStore.requestFlush();
		for (int i = 0; i < 3; i++) DataStore.tick(ctx.server());
		isTrue(DataStore.dirtyStoreNames().contains("players.json"), "tick does not write (players.json stays dirty)");
		isTrue(DataStore.playerSavePending(), "player request stays until the vanilla save");
		eq(flushes, DataStore.flushCount(), "no save in the tick");
		ctx.server().getPlayerList().saveAll();
		isFalse(DataStore.dirtyStoreNames().contains("players.json"), "written on the vanilla save");
		isFalse(DataStore.playerSavePending(), "requests processed");
		isTrue(DataStore.flushCount() > flushes, "save point counted");
		ctx.detail("last save " + DataStore.lastCommitMs() + " ms, slow: " + DataStore.slowCommits());
	}

	/** After startup every store has a file and a copy in the restore point, new ones too. */
	private static void dataStoreStartFiles() {
		String snap = DataStore.startSnapshot();
		notNull(snap, "restore point of this start");
		Path dir = DataStore.backupRoot().resolve(snap);
		List<String> noFile = new ArrayList<>();
		List<String> noCopy = new ArrayList<>();
		for (String name : DataStore.managedStoreNames()) {
			if (!Files.isRegularFile(DataStore.dataDir().resolve(name))) noFile.add(name);
			if (!Files.isRegularFile(dir.resolve(name))) noCopy.add(name);
		}
		eq(List.of(), noFile, "stores without a file");
		eq(List.of(), noCopy, "stores without a copy in restore point " + snap);
	}

	private static void adminLog(TestContext ctx) {
		CommandSourceStack console = ctx.server().createCommandSourceStack();
		var dispatcher = ctx.server().getCommands().getDispatcher();
		String status = AdminLog.lineFor(dispatcher.parse("smpeco status", console), "smpeco status");
		notNull(status, "/smpeco status logged");
		isTrue(status.startsWith(AdminLog.PREFIX) && status.contains("smpeconomy.admin.core") && status.endsWith("/smpeco status"), status);
		String nested = AdminLog.lineFor(dispatcher.parse("execute run smpeco status", console), "execute run smpeco status");
		notNull(nested, "also behind /execute ... run");
		eq(null, AdminLog.lineFor(dispatcher.parse("smpeco nonexistent", console), "smpeco nonexistent"), "syntax error: no line");
		eq(null, AdminLog.lineFor(dispatcher.parse("smpeco", console), "smpeco"), "incomplete: no line");
		ParseResults<CommandSourceStack> plain = dispatcher.parse("worth", console);
		if (plain.getContext().getCommand() != null) eq(null, AdminLog.lineFor(plain, "worth"), "player command: no line");
		// Mixin active: a real call creates the line immediately; it only executes after this test.
		List<String> seen = new ArrayList<>();
		AdminLog.setListener(seen::add);
		try {
			ctx.server().getCommands().performPrefixedCommand(console, "smpeco status");
		} finally {
			AdminLog.setListener(null);
		}
		if (seen.isEmpty()) fail("Commands mixin not active: no admin line");
		ctx.detail(seen.get(0));
	}
}
