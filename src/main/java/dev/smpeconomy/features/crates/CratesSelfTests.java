package dev.smpeconomy.features.crates;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.crates.CrateService.DeliverResult;
import dev.smpeconomy.features.crates.CrateService.DeliverStatus;
import dev.smpeconomy.features.crates.CrateService.OpenResult;
import dev.smpeconomy.features.crates.CrateService.OpenStatus;
import dev.smpeconomy.features.crates.CrateService.PendingReward;
import dev.smpeconomy.features.crates.CratesConfig.CrateDef;
import dev.smpeconomy.features.crates.CratesConfig.RewardDef;

/** GUIs are covered by e2e/crates.mjs. */
final class CratesSelfTests {
	private static final String F = "crates";

	private CratesSelfTests() {}

	static void register() {
		SelfTests.register(F, "config-valid", CratesSelfTests::configValid);
		SelfTests.register(F, "config-validation", CratesSelfTests::configValidation);
		SelfTests.register(F, "enchantments", CratesSelfTests::enchantments);
		SelfTests.register(F, "weights-10k", CratesSelfTests::weights);
		SelfTests.register(F, "key-consumption", CratesSelfTests::keyConsumption);
		SelfTests.register(F, "exactly-once", CratesSelfTests::exactlyOnce);
		SelfTests.register(F, "pending-on-join", CratesSelfTests::pendingDelivery);
		SelfTests.register(F, "join-during-animation", CratesSelfTests::joinDuringSpin);
		SelfTests.register(F, "dead-then-respawn", CratesSelfTests::deadPlayer);
		SelfTests.register(F, "inventory-full-drop", CratesSelfTests::interactiveDrop);
		SelfTests.register(F, "broken-data-stays", CratesSelfTests::brokenData);
		SelfTests.register(F, "persistence", CratesSelfTests::persistence);
		SelfTests.register(F, "admin-commands", CratesSelfTests::adminCommands);
		SelfTests.register(F, "animation", CratesSelfTests::animation);
		SelfTests.register(F, "values", CratesSelfTests::expectedValues);
	}

	private static HolderLookup.Provider reg(TestContext ctx) {
		return ctx.server().registryAccess();
	}

	/** Uses the ID of a default crate; the keys belong only to the test player. */
	private static CrateDef crate(RewardDef... rewards) {
		return CratesConfig.testCrate(CrateKeys.COMMON, List.of(rewards));
	}

	private static PendingReward openOk(UUID player, CrateDef crate, RewardDef forced, TestContext ctx) {
		OpenResult r = CrateService.open(player, crate, forced, new Random(1), reg(ctx));
		Assert.eq(OpenStatus.OK, r.status(), "open " + (forced == null ? "?" : forced.id) + " (" + r.error() + ")");
		Assert.notNull(r.reward(), "reward present");
		return r.reward();
	}

	private static int diamonds(FakePlayer p) {
		return Items.count(p, s -> s.is(net.minecraft.world.item.Items.DIAMOND));
	}

	private static void fillInventory(FakePlayer p) {
		for (int i = 0; i < Items.MAIN_SIZE; i++) p.getInventory().setItem(i, new ItemStack(net.minecraft.world.item.Items.STONE, 64));
	}

	private static CratesConfig minimalConfig() {
		CratesConfig cfg = new CratesConfig();
		for (String tier : CrateKeys.TIERS) {
			CrateDef c = CratesConfig.testCrate(tier, List.of(CratesConfig.money("money", 1, 100)));
			c.name = "§f" + tier;
			cfg.crates.add(c);
		}
		return cfg;
	}

	private static void rejects(String what, Consumer<CratesConfig> mutate, HolderLookup.Provider reg) {
		CratesConfig cfg = minimalConfig();
		mutate.accept(cfg);
		Assert.assertThrows(IllegalArgumentException.class, () -> CratesConfig.validate(cfg, reg), "rejected: " + what);
	}

	private static void configValid(TestContext ctx) {
		CratesConfig cfg = CratesConfig.get();
		CratesConfig.validate(cfg, reg(ctx));
		Assert.check(CratesConfig.problem() == null, "crates not locked (" + CratesConfig.problem() + ")");
		int rewards = 0;
		for (String tier : CrateKeys.TIERS) Assert.check(CratesConfig.crate(tier).isPresent(), "crate " + tier + " present");
		for (CrateDef c : cfg.crates) {
			for (RewardDef r : c.rewards) {
				PendingReward p = CrateService.toPending(c.id, r, reg(ctx));
				if (p.isItem()) {
					ItemStack built = CrateRewards.buildItem(r, reg(ctx));
					ItemStack back = ItemCodec.decode(p.item, reg(ctx));
					Assert.check(ItemStack.isSameItemSameComponents(built, back), c.id + "/" + r.id + ": item round trip");
					Assert.eq(r.effectiveCount(), p.count, c.id + "/" + r.id + ": amount");
				}
				rewards++;
			}
		}
		Assert.check(CratesConfig.crate("common").isPresent() && CratesConfig.crate("EPIC").isPresent(), "lookup is case-insensitive");
		ctx.detail(cfg.crates.size() + " crates, " + rewards + " rewards buildable");
	}

	private static void configValidation(TestContext ctx) {
		HolderLookup.Provider reg = reg(ctx);
		CratesConfig.validate(minimalConfig(), reg);
		rejects("no crates", c -> c.crates.clear(), reg);
		rejects("default crate missing", c -> c.crates.remove(2), reg);
		rejects("duplicate crate", c -> c.crates.get(1).id = CrateKeys.COMMON, reg);
		rejects("invalid crate ID", c -> c.crates.add(CratesConfig.testCrate("Bad Crate", List.of(CratesConfig.money("x", 1, 1)))), reg);
		rejects("duplicate reward", c -> c.crates.get(0).rewards.add(CratesConfig.money("money", 1, 5)), reg);
		rejects("weight sum 0", c -> c.crates.get(0).rewards.get(0).weight = 0, reg);
		rejects("negative weight", c -> c.crates.get(0).rewards.add(CratesConfig.money("neg", -1, 5)), reg);
		rejects("unknown item", c -> c.crates.get(0).rewards.add(CratesConfig.item("x", 1, "minecraft:nonexistent", 1)), reg);
		rejects("money + item", c -> {
			RewardDef r = CratesConfig.item("x", 1, "minecraft:diamond", 1);
			r.money = 100;
			c.crates.get(0).rewards.add(r);
		}, reg);
		rejects("empty reward", c -> {
			RewardDef r = new RewardDef();
			r.id = "empty";
			r.weight = 1;
			c.crates.get(0).rewards.add(r);
		}, reg);
		rejects("amount 0", c -> c.crates.get(0).rewards.add(CratesConfig.item("x", 1, "minecraft:diamond", 0)), reg);
		rejects("amount too large", c -> c.crates.get(0).rewards.add(CratesConfig.item("x", 1, "minecraft:diamond", CratesConfig.MAX_ITEM_COUNT + 1)), reg);
		rejects("level 0", c -> c.crates.get(0).rewards.add(CratesConfig.book("x", 1, "minecraft:mending", 0)), reg);
		rejects("enchantment on money", c -> c.crates.get(0).rewards.get(0).enchantments = Map.of("minecraft:mending", 1), reg);
		rejects("key of unknown crate", c -> c.crates.get(0).rewards.add(CratesConfig.key("x", 1, "legendary", 1)), reg);
		rejects("unknown enchantment", c -> c.crates.get(0).rewards.add(CratesConfig.book("x", 1, "minecraft:nonexistent", 1)), reg);
		rejects("enchantment does not fit", c -> {
			RewardDef r = CratesConfig.item("x", 1, "minecraft:diamond", 1);
			r.enchantments = Map.of("minecraft:sharpness", 5);
			c.crates.get(0).rewards.add(r);
		}, reg);
		// Without registries, as at mod init, the unknown enchantment is not caught yet, everything else is
		CratesConfig noReg = minimalConfig();
		noReg.crates.get(0).rewards.add(CratesConfig.book("x", 1, "minecraft:nonexistent", 1));
		CratesConfig.validate(noReg, null);
		ctx.detail("17 error cases rejected");
	}

	private static void enchantments(TestContext ctx) {
		HolderLookup.Provider reg = reg(ctx);
		var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
		Holder<Enchantment> mending = lookup.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("minecraft:mending")));
		Holder<Enchantment> efficiency = lookup.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("minecraft:efficiency")));
		Holder<Enchantment> unbreaking = lookup.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("minecraft:unbreaking")));

		ItemStack book = CrateRewards.buildItem(CratesConfig.book("b", 1, "minecraft:mending", 1), reg);
		Assert.check(book.is(net.minecraft.world.item.Items.ENCHANTED_BOOK), "book");
		Assert.eq(1, book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).getLevel(mending), "book: stored enchantment Mending I");
		Assert.check(book.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty(), "book: no active enchantment");

		RewardDef pick = CratesConfig.item("p", 1, "minecraft:diamond_pickaxe", 1);
		pick.enchantments = new LinkedHashMap<>();
		pick.enchantments.put("minecraft:efficiency", 5);
		pick.enchantments.put("unbreaking", 3); // No namespace means minecraft
		ItemStack tool = CrateRewards.buildItem(pick, reg);
		ItemEnchantments te = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		Assert.eq(5, te.getLevel(efficiency), "pickaxe: Efficiency V");
		Assert.eq(3, te.getLevel(unbreaking), "pickaxe: Unbreaking III");
		Assert.isFalse(Items.isPristine(tool), "enchanted means not pristine (not sellable)");

		ItemStack back = ItemCodec.decode(ItemCodec.encode(tool, reg), reg);
		Assert.check(ItemStack.isSameItemSameComponents(tool, back), "codec round trip keeps enchantments");
		Assert.isFalse(CrateRewards.enchantmentNames(book).isEmpty(), "enchantment name for chat");

		for (String tier : List.of(CrateKeys.RARE, CrateKeys.EPIC)) {
			boolean hasBook = CratesConfig.crate(tier).orElseThrow().rewards.stream()
				.anyMatch(r -> "minecraft:enchanted_book".equals(r.item) && r.enchantments != null && !r.enchantments.isEmpty() && r.weight > 0);
			Assert.check(hasBook, tier + " contains enchanted books");
		}
		Assert.assertThrows(IllegalArgumentException.class, () -> CrateRewards.buildItem(CratesConfig.book("x", 1, "minecraft:nonexistent", 1), reg), "unknown enchantment");
	}

	private static void weights(TestContext ctx) {
		int[] w = {0, 3, 0, 2};
		Assert.eq(1, CrateRewards.pickIndex(w, 0), "r=0: first entry with weight");
		Assert.eq(1, CrateRewards.pickIndex(w, 2), "r=2: entry 1");
		Assert.eq(3, CrateRewards.pickIndex(w, 3), "r=3: entry 3");
		Assert.eq(3, CrateRewards.pickIndex(w, 4), "r=4: entry 3");
		Assert.assertThrows(IllegalArgumentException.class, () -> CrateRewards.pickIndex(w, 5), "r=sum invalid");

		CrateDef synthetic = crate(CratesConfig.money("a", 50, 1), CratesConfig.money("b", 30, 1), CratesConfig.money("c", 15, 1),
			CratesConfig.money("d", 5, 1), CratesConfig.money("off", 0, 1));
		checkDistribution(synthetic, 10_000, 42);
		int checked = 0;
		for (CrateDef c : CratesConfig.get().crates) {
			checkDistribution(c, 10_000, 1000 + checked);
			checked++;
		}
		ctx.detail("10k draws per crate within 4 sigma (" + (checked + 1) + " tables)");
	}

	private static void checkDistribution(CrateDef crate, int rolls, long seed) {
		Map<String, Integer> hits = new LinkedHashMap<>();
		Random random = new Random(seed);
		for (int i = 0; i < rolls; i++) hits.merge(CrateRewards.roll(crate, random).id, 1, Integer::sum);
		long total = crate.totalWeight();
		for (RewardDef r : crate.rewards) {
			int n = hits.getOrDefault(r.id, 0);
			if (r.weight == 0) {
				Assert.eq(0, n, crate.id + "/" + r.id + ": weight 0 never drawn");
				continue;
			}
			double p = (double) r.weight / total;
			double sigma = Math.sqrt(p * (1 - p) / rolls);
			double observed = (double) n / rolls;
			Assert.check(Math.abs(observed - p) <= 4 * sigma + 1e-9,
				crate.id + "/" + r.id + String.format(java.util.Locale.ROOT, ": %.4f instead of %.4f (+-%.4f)", observed, p, 4 * sigma));
		}
	}

	private static void keyConsumption(TestContext ctx) {
		FakePlayer a = ctx.player("CratesKeyA");
		UUID id = a.getUUID();
		RewardDef money = CratesConfig.money("money", 1, 100);
		CrateDef c = crate(money);
		OpenResult none = CrateService.open(id, c, null, new Random(1), reg(ctx));
		Assert.eq(OpenStatus.NO_KEY, none.status(), "without key");
		Assert.eq(0, CrateService.pendingCount(id), "no reward without key");

		CrateKeys.give(id, CrateKeys.COMMON, 2);
		openOk(id, c, null, ctx);
		Assert.eq(1, CrateKeys.count(id, CrateKeys.COMMON), "1st open: 1 key fewer");
		openOk(id, c, null, ctx);
		Assert.eq(0, CrateKeys.count(id, CrateKeys.COMMON), "2nd open: 0 keys");
		Assert.eq(OpenStatus.NO_KEY, CrateService.open(id, c, null, new Random(1), reg(ctx)).status(), "3rd open without key");
		Assert.eq(2, CrateService.pendingCount(id), "exactly 2 rewards pending");
		Assert.eq(2, PlayerDataService.getCounter(id, CrateService.OPENED_PREFIX + CrateKeys.COMMON), "opened counter");
		Assert.eq(OpenStatus.NO_KEY, CrateService.open(id, CratesConfig.testCrate(CrateKeys.EPIC, List.of(money)), null, new Random(1), reg(ctx)).status(),
			"Epic needs its own key");

		CrateKeys.give(id, CrateKeys.COMMON, 1);
		RewardDef broken = CratesConfig.book("broken", 1, "minecraft:nonexistent", 1);
		OpenResult err = CrateService.open(id, crate(broken), null, new Random(1), reg(ctx));
		Assert.eq(OpenStatus.CONFIG_ERROR, err.status(), "broken reward");
		Assert.eq(1, CrateKeys.count(id, CrateKeys.COMMON), "key not used on error");
		Assert.eq(2, CrateService.pendingCount(id), "no extra reward on error");
		Assert.eq(OpenStatus.UNKNOWN_CRATE, CrateService.open(id, "nonexistent", new Random(1), reg(ctx)).status(), "unknown crate");
		ctx.detail("exactly 1 key per open, errors use none");
	}

	private static void exactlyOnce(TestContext ctx) {
		FakePlayer a = ctx.player("CratesOnceA");
		UUID id = a.getUUID();
		CrateKeys.give(id, CrateKeys.COMMON, 3);
		RewardDef money = CratesConfig.money("money", 1, 12_345);
		RewardDef dia = CratesConfig.item("dia", 1, "minecraft:diamond", 5);
		RewardDef keys = CratesConfig.key("key", 1, CrateKeys.RARE, 2);
		CrateDef c = crate(money, dia, keys);

		// Money: animation end, close and a retry pay once
		long before = EconomyService.balance(id);
		PendingReward pm = openOk(id, c, money, ctx);
		Assert.eq(before, EconomyService.balance(id), "no money before delivery");
		SpinSession s = new SpinSession(id, c.id, pm);
		DeliverResult first = s.finish(a, true, reg(ctx));
		Assert.eq(DeliverStatus.DELIVERED, first.status(), "money delivered");
		Assert.check(s.finish(a, false, reg(ctx)) == first, "second finish has no effect");
		Assert.eq(DeliverStatus.NOT_FOUND, CrateService.deliver(a, pm.id, true, reg(ctx)).status(), "direct second attempt finds nothing");
		Assert.eq(before + 12_345, EconomyService.balance(id), "money exactly once");

		PendingReward pi = openOk(id, c, dia, ctx);
		SpinSession si = new SpinSession(id, c.id, pi);
		Assert.eq(DeliverStatus.DELIVERED, si.finish(a, true, reg(ctx)).status(), "items delivered");
		si.finish(a, true, reg(ctx));
		CrateService.deliver(a, pi.id, true, reg(ctx));
		CrateService.deliverAll(a, true, reg(ctx));
		Assert.eq(5, diamonds(a), "exactly 5 diamonds");

		long rare = CrateKeys.count(id, CrateKeys.RARE);
		PendingReward pk = openOk(id, c, keys, ctx);
		CrateService.deliver(a, pk.id, false, reg(ctx));
		CrateService.deliver(a, pk.id, false, reg(ctx));
		Assert.eq(rare + 2, CrateKeys.count(id, CrateKeys.RARE), "key reward exactly once");
		Assert.eq(0, CrateService.pendingCount(id), "nothing pending anymore");
		Assert.eq(0, CrateKeys.count(id, CrateKeys.COMMON), "3 keys used");
		Assert.check(ctx.drops(a).isEmpty(), "nothing dropped");
		ctx.detail("money, items and keys each exactly once");
	}

	private static void pendingDelivery(TestContext ctx) {
		FakePlayer a = ctx.player("CratesJoinA");
		UUID id = a.getUUID();
		CrateKeys.give(id, CrateKeys.COMMON, 2);
		RewardDef dia = CratesConfig.item("dia", 1, "minecraft:diamond", 3);
		RewardDef money = CratesConfig.money("money", 1, 500);
		CrateDef c = crate(dia, money);
		fillInventory(a);

		// Disconnect or server stop during the animation: not interactive and inventory full, so it stays pending
		PendingReward p = openOk(id, c, dia, ctx);
		DeliverResult kept = new SpinSession(id, c.id, p).finish(a, false, reg(ctx));
		Assert.eq(DeliverStatus.KEPT_NO_SPACE, kept.status(), "full: pending");
		Assert.eq(1, CrateService.pendingCount(id), "1 reward pending");
		Assert.eq(0, diamonds(a), "nothing given");
		// Money always goes through
		long before = EconomyService.balance(id);
		PendingReward pm = openOk(id, c, money, ctx);
		Assert.eq(DeliverStatus.DELIVERED, new SpinSession(id, c.id, pm).finish(a, false, reg(ctx)).status(), "money despite full inventory");
		Assert.eq(before + 500, EconomyService.balance(id), "money credited");

		List<DeliverResult> again = CrateService.deliverAll(a, false, reg(ctx));
		Assert.eq(1, again.size(), "one attempt on join");
		Assert.eq(DeliverStatus.KEPT_NO_SPACE, again.get(0).status(), "still pending");

		a.getInventory().setItem(5, ItemStack.EMPTY);
		List<DeliverResult> done = CrateService.deliverAll(a, false, reg(ctx));
		Assert.eq(DeliverStatus.DELIVERED, done.get(0).status(), "delivered once there is space");
		Assert.eq(3, diamonds(a), "3 diamonds");
		Assert.eq(0, CrateService.pendingCount(id), "nothing pending anymore");
		Assert.check(CrateService.deliverAll(a, false, reg(ctx)).isEmpty(), "second join: nothing");
		Assert.eq(3, diamonds(a), "still 3 diamonds");
		Assert.check(ctx.drops(a).isEmpty(), "nothing dropped");
		ctx.detail("pending on full inventory, exactly once on join");
	}

	private static void joinDuringSpin(TestContext ctx) {
		FakePlayer a = ctx.player("CratesSpinA");
		UUID id = a.getUUID();
		CrateKeys.give(id, CrateKeys.COMMON, 1);
		RewardDef dia = CratesConfig.item("dia", 1, "minecraft:diamond", 4);
		CrateDef c = crate(dia);
		PendingReward p = openOk(id, c, dia, ctx);
		CrateService.markActive(p.id);
		ctx.cleanup(() -> CrateService.unmarkActive(p.id));
		// Late join delivery or claim during the animation: the reward belongs to the animation
		Assert.check(CrateService.deliverAll(a, false, reg(ctx)).isEmpty(), "running animation skipped");
		Assert.eq(0, CrateService.deliverableCount(id), "not claimable during animation");
		Assert.eq(1, CrateService.pendingCount(id), "stays saved");
		Assert.eq(0, diamonds(a), "nothing given yet");
		DeliverResult r = new SpinSession(id, c.id, p).finish(a, true, reg(ctx));
		CrateService.unmarkActive(p.id);
		Assert.eq(DeliverStatus.DELIVERED, r.status(), "animation delivers itself");
		Assert.eq(4, diamonds(a), "exactly 4 diamonds");
		Assert.check(CrateService.deliverAll(a, false, reg(ctx)).isEmpty(), "nothing afterwards");
	}

	private static void deadPlayer(TestContext ctx) {
		FakePlayer a = ctx.player("CratesDeadA");
		UUID id = a.getUUID();
		float health = a.getMaxHealth();
		ctx.cleanup(() -> a.setHealth(health));
		CrateKeys.give(id, CrateKeys.COMMON, 2);
		RewardDef dia = CratesConfig.item("dia", 1, "minecraft:diamond", 2);
		RewardDef money = CratesConfig.money("money", 1, 700);
		CrateDef c = crate(dia, money);
		PendingReward p = openOk(id, c, dia, ctx);
		PendingReward pm = openOk(id, c, money, ctx);
		a.setHealth(0f);
		Assert.isFalse(a.isAlive(), "test player dead");
		Assert.eq(DeliverStatus.KEPT_UNAVAILABLE, new SpinSession(id, c.id, p).finish(a, true, reg(ctx)).status(), "dead: items stay pending");
		long before = EconomyService.balance(id);
		Assert.eq(DeliverStatus.DELIVERED, new SpinSession(id, c.id, pm).finish(a, false, reg(ctx)).status(), "dead: money anyway");
		Assert.eq(before + 700, EconomyService.balance(id), "money credited");
		Assert.eq(0, diamonds(a), "no items to the dead");
		Assert.check(ctx.drops(a).isEmpty(), "nothing dropped at death location");
		a.setHealth(health);
		CrateService.deliverAll(a, false, reg(ctx)); // Respawn
		Assert.eq(2, diamonds(a), "delivered after respawn");
		Assert.eq(0, CrateService.pendingCount(id), "nothing pending anymore");
	}

	private static void interactiveDrop(TestContext ctx) {
		FakePlayer a = ctx.player("CratesDropA");
		UUID id = a.getUUID();
		CrateKeys.give(id, CrateKeys.COMMON, 1);
		RewardDef dia = CratesConfig.item("dia", 1, "minecraft:diamond", 70);
		CrateDef c = crate(dia);
		fillInventory(a);
		a.getInventory().setItem(0, ItemStack.EMPTY); // Room for 64
		PendingReward p = openOk(id, c, dia, ctx);
		DeliverResult res = new SpinSession(id, c.id, p).finish(a, true, reg(ctx));
		Assert.eq(DeliverStatus.DELIVERED, res.status(), "delivered interactively");
		Assert.eq(6, res.dropped(), "6 dropped");
		Assert.eq(64, diamonds(a), "64 in inventory");
		int dropped = 0;
		for (ItemStack s : ctx.drops(a)) {
			Assert.check(s.is(net.minecraft.world.item.Items.DIAMOND), "drop is a diamond");
			dropped += s.getCount();
		}
		Assert.eq(6, dropped, "rest at feet (ctx.drops)");
		Assert.eq(0, CrateService.pendingCount(id), "nothing pending");
	}

	private static void brokenData(TestContext ctx) {
		FakePlayer a = ctx.player("CratesBrokenA");
		UUID id = a.getUUID();
		PendingReward bad = new PendingReward();
		bad.id = "broken-1";
		bad.crate = CrateKeys.COMMON;
		bad.entry = "x";
		bad.type = "item";
		bad.item = "%%% not base64 %%%";
		bad.count = 1;
		PendingReward odd = new PendingReward();
		odd.id = "broken-2";
		odd.type = "nonexistent";
		List<PendingReward> list = new ArrayList<>(List.of(bad, odd));
		CrateService.savePending(id, list);
		Assert.eq(DeliverStatus.KEPT_ERROR, CrateService.deliver(a, bad.id, true, reg(ctx)).status(), "unreadable item data stays");
		Assert.eq(DeliverStatus.KEPT_ERROR, CrateService.deliver(a, odd.id, true, reg(ctx)).status(), "unknown type stays");
		CrateService.deliverAll(a, true, reg(ctx));
		Assert.eq(2, CrateService.pendingCount(id), "never deleted");
		Assert.notNull(CrateService.describe(bad, reg(ctx)), "description without exception");
		Assert.check(a.getInventory().isEmpty(), "nothing created");
	}

	private static void persistence(TestContext ctx) {
		FakePlayer a = ctx.player("CratesSaveA");
		UUID id = a.getUUID();
		CrateKeys.give(id, CrateKeys.COMMON, 1);
		RewardDef book = CratesConfig.book("book", 1, "minecraft:mending", 1);
		book.announce = true;
		PendingReward p = openOk(id, crate(book), book, ctx);
		// In the players.json blob, the same file as the key counter
		var blob = PlayerDataService.peek(id).orElseThrow().blobs.get(CrateService.BLOB);
		Assert.notNull(blob, "blob in players.json");
		String json = DataStore.gson().toJson(blob);
		CrateService.PendingList back = DataStore.gson().fromJson(json, CrateService.PendingList.class);
		Assert.eq(1, back.rewards.size(), "1 entry after JSON round trip");
		PendingReward r = back.rewards.get(0);
		Assert.eq(p.id, r.id, "ID");
		Assert.eq(p.item, r.item, "item data");
		Assert.eq("item", r.type, "type");
		Assert.check(r.announce, "announcement");
		ItemStack decoded = ItemCodec.decode(r.item, reg(ctx));
		Assert.check(decoded.is(net.minecraft.world.item.Items.ENCHANTED_BOOK), "book after round trip");
		Assert.isFalse(decoded.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty(), "enchantment after round trip");
		Assert.eq(0, CrateKeys.count(id, CrateKeys.COMMON), "key deducted in the same record");
	}

	private static void adminCommands(TestContext ctx) throws CommandSyntaxException {
		FakePlayer a = ctx.player("CratesAdmA");
		UUID id = a.getUUID();
		EconomyService.ensureAccount(id, "CratesAdmA"); // Name cache for Args.knownPlayer, deleted in cleanup
		CommandDispatcher<CommandSourceStack> d = ctx.server().getCommands().getDispatcher();
		CommandSourceStack console = ctx.server().createCommandSourceStack().withSuppressedOutput();
		d.execute("crates give CratesAdmA common 3", console);
		Assert.eq(3, CrateKeys.count(id, CrateKeys.COMMON), "give 3");
		d.execute("crates give CratesAdmA epic", console);
		Assert.eq(1, CrateKeys.count(id, CrateKeys.EPIC), "give by alias (epic), default 1");
		Assert.eq(0, d.execute("crates give CratesAdmA legendary 2", console), "unknown crate");
		Assert.assertThrows(CommandSyntaxException.class, () -> d.execute("crates give CratesAdmA common 0", console), "amount 0 rejected");
		Assert.assertThrows(CommandSyntaxException.class, () -> d.execute("crates give NobodyXY common 1", console), "unknown player");
		Assert.eq(3, CrateKeys.count(id, CrateKeys.COMMON), "failed attempts change nothing");
		d.execute("crates set CratesAdmA common 0", console);
		Assert.eq(0, CrateKeys.count(id, CrateKeys.COMMON), "set 0 (English alias common)");
		// Without admin rights the subcommand does not exist for the player
		CommandSourceStack player = a.createCommandSourceStack().withSuppressedOutput();
		Assert.assertThrows(CommandSyntaxException.class, () -> d.execute("crates give CratesAdmA epic 5", player), "give without rights");
		Assert.assertThrows(CommandSyntaxException.class, () -> d.execute("crates give CratesAdmA epic 5", player), "give without rights (/crates)");
		Assert.eq(1, CrateKeys.count(id, CrateKeys.EPIC), "no keys without rights");
		CrateKeys.give(id, CrateKeys.COMMON, 1);
		String forced = CratesConfig.crate(CrateKeys.COMMON).orElseThrow().rewards.get(0).id;
		d.execute("crates force CratesAdmA common " + forced, console);
		OpenResult r = CrateService.open(id, CrateKeys.COMMON, new Random(7), reg(ctx));
		Assert.eq(OpenStatus.OK, r.status(), "open after force");
		Assert.eq(forced, r.reward().entry, "force works");
		Assert.eq(0, d.execute("crates force CratesAdmA common nonexistent", console), "force: unknown reward");
		Assert.check(CratesConfig.crateNames().containsAll(List.of("common", "rare", "epic")), "suggestions: English crate names " + CratesConfig.crateNames());
		ctx.detail("give/set/force including rights, error cases and aliases (common/epic)");
	}

	private static void animation(TestContext ctx) {
		Assert.eq(SpinGui.STEPS, SpinGui.DELAYS.length, "one delay per step");
		int ticks = SpinGui.totalTicks();
		Assert.check(ticks >= 50 && ticks <= 70, "animation about 3 s (" + ticks + " ticks)");
		for (int i = 1; i < SpinGui.DELAYS.length; i++) Assert.check(SpinGui.DELAYS[i] >= SpinGui.DELAYS[i - 1], "slows down");
		FakePlayer a = ctx.player("CratesAnimA");
		CrateKeys.give(a.getUUID(), CrateKeys.COMMON, 1);
		RewardDef dia = CratesConfig.item("dia", 1, "minecraft:diamond", 2);
		CrateDef c = crate(CratesConfig.money("money", 99, 100), dia);
		PendingReward p = openOk(a.getUUID(), c, dia, ctx);
		List<GuiElementBuilder> reel = SpinGui.buildReel(c, p, reg(ctx), new Random(3));
		Assert.eq(SpinGui.STEPS + 9, reel.size(), "reel length");
		// After STEPS steps the center (column 4) shows reel[STEPS + 4]
		Assert.check(reel.get(SpinGui.STEPS + 4).asStack().is(net.minecraft.world.item.Items.DIAMOND), "reward lands in the center");
		Assert.eq(SpinGui.CENTER, 9 + 4, "center is slot 13");
	}

	private static void expectedValues(TestContext ctx) {
		long last = -1;
		StringBuilder sb = new StringBuilder();
		for (String tier : CrateKeys.TIERS) {
			CrateDef c = CratesConfig.crate(tier).orElseThrow();
			double moneyEv = 0;
			for (RewardDef r : c.rewards) {
				if (r.kind() == CratesConfig.Kind.MONEY) moneyEv += (double) r.weight / c.totalWeight() * r.money;
			}
			Assert.check(moneyEv > last, tier + ": expected money rises with rarity");
			last = (long) moneyEv;
			CratesCommand.Expected e = CratesCommand.expected(c, reg(ctx));
			Assert.check(e.cents() > 0, tier + ": expected value > 0");
			sb.append(tier).append('=').append(e.cents() / 100).append("$ ");
		}
		ctx.detail(sb.toString().trim());
	}
}
