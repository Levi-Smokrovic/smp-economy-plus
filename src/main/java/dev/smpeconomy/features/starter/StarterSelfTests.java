package dev.smpeconomy.features.starter;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;

/** Everything uses its own config (starter.json stays untouched) and test players. */
final class StarterSelfTests {
	private static final String F = "starter";

	private StarterSelfTests() {}

	static void register() {
		SelfTests.register(F, "config", ctx -> {
			StarterConfig c = new StarterConfig();
			StarterConfig.validate(c);
			Assert.isTrue(c.giveKit && c.onlyNewPlayers, "default: kit on, only new players");
			Assert.isFalse(c.kit.isEmpty(), "default kit present");
			for (StarterConfig.KitItem k : c.kit) Assert.isTrue(StarterService.stackOf(k).isPresent(), "default item valid: " + k.id);
			StarterConfig bad = new StarterConfig();
			bad.kit.add(new StarterConfig.KitItem(41, "minecraft:stone", 1));
			Assert.assertThrows(IllegalArgumentException.class, () -> StarterConfig.validate(bad), "Slot 41");
			StarterConfig noId = new StarterConfig();
			noId.kit.add(new StarterConfig.KitItem(-1, " ", 1));
			Assert.assertThrows(IllegalArgumentException.class, () -> StarterConfig.validate(noId), "without id or item");
			StarterConfig zero = new StarterConfig();
			zero.kit.add(new StarterConfig.KitItem(-1, "stone", 0));
			Assert.assertThrows(IllegalArgumentException.class, () -> StarterConfig.validate(zero), "count 0");
			StarterConfig ver = new StarterConfig();
			ver.kitVersion = 0;
			Assert.assertThrows(IllegalArgumentException.class, () -> StarterConfig.validate(ver), "Version 0");
			StarterConfig norm = new StarterConfig();
			norm.kit = new ArrayList<>(List.of(new StarterConfig.KitItem(-1, " Bread ", 3)));
			StarterConfig.validate(norm);
			Assert.eq("minecraft:bread", norm.kit.get(0).id, "id normalized");
			StarterConfig copy = StarterConfig.copy(norm);
			copy.kit.clear();
			Assert.eq(1, norm.kit.size(), "copy independent");
			ctx.detail("defaults valid; slots, ids, count, version checked; copy independent");
		});

		SelfTests.register(F, "capture", ctx -> {
			FakePlayer admin = ctx.player("StarterAdmin");
			ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
			sword.set(DataComponents.CUSTOM_NAME, Component.literal("Starter Blade"));
			sword.setDamageValue(7);
			ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
			shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.TORCH, 32), new ItemStack(Items.COOKED_BEEF, 5))));
			admin.getInventory().setItem(3, sword);
			admin.getInventory().setItem(20, shulker);
			admin.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			admin.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			List<StarterConfig.KitItem> kit = StarterService.capture(admin);
			Assert.eq(4, kit.size(), "4 stacks captured");
			Assert.eq(List.of(3, 20, 39, 40), kit.stream().map(k -> k.slot).toList(), "slots kept (head 39, offhand 40)");
			Assert.isTrue(ItemStack.isSameItemSameComponents(sword, StarterService.stackOf(kit.get(0)).orElseThrow()), "sword with name and damage");
			Assert.isTrue(ItemStack.isSameItemSameComponents(shulker, StarterService.stackOf(kit.get(1)).orElseThrow()), "shulker with contents");
			Assert.isFalse(admin.getInventory().getItem(3).isEmpty(), "capture takes nothing away");
			FakePlayer fresh = ctx.player("StarterFresh");
			StarterConfig cfg = cfgWith(kit);
			StarterService.Given g = StarterService.give(fresh, cfg);
			Assert.eq(4, g.placed(), "everything placed");
			Assert.isTrue(ItemStack.isSameItemSameComponents(sword, fresh.getInventory().getItem(3)), "sword in slot 3");
			Assert.isTrue(fresh.getInventory().getItem(20).is(Items.SHULKER_BOX), "shulker in slot 20");
			Assert.isTrue(fresh.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "helmet on head");
			Assert.isTrue(fresh.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD), "shield in offhand");
			admin.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			admin.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
			fresh.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			fresh.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
			ctx.detail("components, shulker contents, armor, offhand: captured and returned identically");
		});

		SelfTests.register(F, "once", ctx -> {
			StarterConfig cfg = cfgWith(List.of(new StarterConfig.KitItem(0, "minecraft:diamond", 3), new StarterConfig.KitItem(-1, "minecraft:bread", 16)));
			use(ctx, cfg);
			FakePlayer p = ctx.player("StarterOnce");
			StarterService.Given g = StarterService.giveOnJoin(p, 0);
			Assert.eq(StarterService.Outcome.GIVEN, g.outcome(), "first join -> kit");
			Assert.eq(3, count(p, Items.DIAMOND), "3 diamonds");
			Assert.eq(16, count(p, Items.BREAD), "16 bread");
			Assert.eq(1, PlayerDataService.getLong(p.getUUID(), StarterService.KEY, 0), "marker = kit version");
			Assert.eq(StarterService.Outcome.ALREADY, StarterService.giveOnJoin(p, 0).outcome(), "second join -> nothing");
			Assert.eq(StarterService.Outcome.ALREADY, StarterService.giveOnJoin(p, 0).outcome(), "third join -> nothing");
			Assert.eq(3, count(p, Items.DIAMOND), "still 3 diamonds");
			cfg.kitVersion = 2;
			Assert.eq(StarterService.Outcome.GIVEN, StarterService.giveOnJoin(p, 0).outcome(), "kit version 2 -> once more");
			Assert.eq(6, count(p, Items.DIAMOND), "6 diamonds");
			Assert.eq(StarterService.Outcome.ALREADY, StarterService.giveOnJoin(p, 0).outcome(), "v2 only once");
			PlayerDataService.remove(p.getUUID(), StarterService.KEY);
			Assert.eq(StarterService.Outcome.GIVEN, StarterService.giveOnJoin(p, 0).outcome(), "again after reset");
			ctx.detail("once per version, bump, reset");
		});

		SelfTests.register(F, "conditions", ctx -> {
			StarterConfig cfg = cfgWith(List.of(new StarterConfig.KitItem(-1, "minecraft:diamond", 1)));
			use(ctx, cfg);
			FakePlayer vet = ctx.player("StarterVeteran");
			long minutes = cfg.newPlayerMaxMinutes * StarterService.TICKS_PER_MINUTE;
			Assert.eq(StarterService.Outcome.NOT_NEW, StarterService.giveOnJoin(vet, minutes).outcome(), "playtime at the limit -> no kit");
			Assert.eq(0, count(vet, Items.DIAMOND), "veteran: nothing");
			Assert.eq(0, PlayerDataService.getLong(vet.getUUID(), StarterService.KEY, 0), "veteran: no marker");
			Assert.eq(StarterService.Outcome.GIVEN, StarterService.giveOnJoin(vet, minutes - 1).outcome(), "just below -> kit");
			FakePlayer old = ctx.player("StarterOld");
			cfg.onlyNewPlayers = false;
			Assert.eq(StarterService.Outcome.GIVEN, StarterService.giveOnJoin(old, 10_000_000).outcome(), "onlyNewPlayers off -> everyone once");
			FakePlayer off = ctx.player("StarterOff");
			cfg.giveKit = false;
			Assert.eq(StarterService.Outcome.DISABLED, StarterService.giveOnJoin(off, 0).outcome(), "switch off");
			cfg.giveKit = true;
			cfg.kit.clear();
			Assert.eq(StarterService.Outcome.EMPTY, StarterService.giveOnJoin(off, 0).outcome(), "empty kit");
			Assert.eq(0, PlayerDataService.getLong(off.getUUID(), StarterService.KEY, 0), "off or empty: no marker");
			ctx.detail("only new players (limit), onlyNewPlayers off, switch off, empty kit");
		});

		SelfTests.register(F, "full", ctx -> {
			StarterConfig cfg = cfgWith(List.of(new StarterConfig.KitItem(0, "minecraft:diamond", 3), new StarterConfig.KitItem(39, "minecraft:iron_helmet", 1),
				new StarterConfig.KitItem(-1, "minecraft:bread", 80)));
			cfg.kit.add(StarterConfig.KitItem.encoded(5, "bm90IGFuIGl0ZW0="));
			use(ctx, cfg);
			FakePlayer p = ctx.player("StarterFull");
			for (int i = 0; i < dev.smpeconomy.core.Items.MAIN_SIZE; i++) p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
			StarterService.Given g = StarterService.giveOnJoin(p, 0);
			Assert.eq(StarterService.Outcome.GIVEN, g.outcome(), "given despite full inventory");
			Assert.eq(3, g.dropped(), "3 stacks at the feet");
			Assert.eq(1, g.skipped(), "1 unreadable");
			List<ItemStack> drops = ctx.drops(p);
			Assert.eq(3, drops.stream().filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(), "diamonds dropped");
			Assert.eq(80, drops.stream().filter(s -> s.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum(), "80 bread dropped (2 stacks)");
			Assert.eq(1, drops.stream().filter(s -> s.is(Items.IRON_HELMET)).count(), "helmet dropped (head occupied)");
			Assert.isTrue(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET), "own helmet stays");
			Assert.eq(1, PlayerDataService.getLong(p.getUUID(), StarterService.KEY, 0), "marker set");
			Assert.eq(4, cfg.kit.size(), "unreadable entry stays in config");
			p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			FakePlayer q = ctx.player("StarterBusy");
			q.getInventory().setItem(0, new ItemStack(Items.STICK));
			StarterService.giveOnJoin(q, 0);
			Assert.eq(3, count(q, Items.DIAMOND), "occupied slot -> elsewhere in inventory");
			Assert.isTrue(q.getInventory().getItem(0).is(Items.STICK), "slot 0 unchanged");
			Assert.eq(0, ctx.drops(q).size(), "nothing dropped");
			q.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			ctx.detail("full inventory -> at the feet, occupied slot -> elsewhere, occupied armor slot, unreadable skipped");
		});
	}

	private static StarterConfig cfgWith(List<StarterConfig.KitItem> kit) {
		StarterConfig c = new StarterConfig();
		c.kit = new ArrayList<>(kit);
		c.welcome = new ArrayList<>();
		StarterConfig.validate(c);
		return c;
	}

	private static void use(TestContext ctx, StarterConfig cfg) {
		StarterService.setOverride(cfg);
		ctx.cleanup(() -> StarterService.setOverride(null));
	}

	private static int count(FakePlayer p, net.minecraft.world.item.Item item) {
		return dev.smpeconomy.core.Items.count(p, s -> s.is(item));
	}
}
