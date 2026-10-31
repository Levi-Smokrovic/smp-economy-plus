package dev.smpeconomy.features.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.entity.EntityTypeTest;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;

final class NpcSelfTests {
	private static final String F = "npc";
	private static final NpcEdits.Spot SPOT = NpcEdits.Spot.of("minecraft:overworld", 10.3, 70.004, -5.9, 93.4f);

	private NpcSelfTests() {}

	static void register() {
		SelfTests.register(F, "defaults", ctx -> {
			NpcConfig c = new NpcConfig();
			NpcConfig.validate(c);
			Assert.isTrue(c.enabled, "default on (nothing happens without entries)");
			Assert.isTrue(c.npcs.isEmpty() && c.texts.isEmpty(), "no predefined NPCs or texts");
			MinecraftServer server = ctx.server();
			int running = 0;
			for (NpcActions.Preset p : NpcActions.presets()) {
				NpcConfig withPreset = new NpcConfig();
				NpcEdits.create(withPreset, "p_" + p.target(), p.target(), "", SPOT);
				NpcConfig.validate(withPreset);
				for (String item : p.equipment().values()) Assert.isFalse(NpcBody.equipmentStack(item).isEmpty(), "template " + p.target() + ": item " + item);
				if (NpcActions.moduleRunning(p.module())) {
					running++;
					Assert.isTrue(NpcActions.available(server, p), "module " + p.module() + " runs, command /" + p.command() + " missing");
				}
			}
			Assert.isTrue(NpcActions.preset("bank") != null && NpcActions.preset("shop") != null && NpcActions.preset("ah") != null, "core targets present");
			Assert.isFalse(NpcActions.available(server, new NpcActions.Preset("x", "no_such_module", "list", "", "", "", Map.of())), "foreign module not available");
			ctx.detail("empty and active; " + NpcActions.presets().size() + " templates valid, " + running + " of them with running module and command");
		});

		SelfTests.register(F, "validation", ctx -> {
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(withNpc("Bad Id", "", NpcConfig.Action.none())), "invalid id");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(withNpc("a", "wide/", NpcConfig.Action.none())), "broken skin");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(withNpc("a", "not a name!", NpcConfig.Action.none())), "skin with whitespace");
			NpcConfig dup = withNpc("a", "", NpcConfig.Action.none());
			dup.npcs.add(dup.npcs.get(0));
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(dup), "duplicate id");
			NpcConfig slot = withNpc("a", "", NpcConfig.Action.none());
			slot.npcs.get(0).equipment.put("hat", "stone");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(slot), "unknown slot");
			NpcConfig color = withNpc("a", "", NpcConfig.Action.none());
			color.npcs.get(0).equipment.put("chest", "leather_chestplate#12345");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(color), "broken color");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(withNpc("a", "", NpcConfig.Action.open("nope", ""))), "unknown target");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(withNpc("a", "", NpcConfig.Action.command(" / "))), "empty command");
			NpcConfig.Action weird = NpcConfig.Action.none();
			weird.type = "console";
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(withNpc("a", "", weird)), "unknown action type");
			NpcConfig noLines = withNpc("a", "", NpcConfig.Action.none());
			noLines.texts.add(new NpcConfig.Text("t", 0.5, 70, 0.5));
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(noLines), "text without lines");
			NpcConfig clash = withNpc("same", "", NpcConfig.Action.none());
			clash.texts.add(new NpcConfig.Text("same", 0.5, 70, 0.5, "A"));
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcConfig.validate(clash), "NPC and text id equal");
			NpcConfig ok = withNpc("guide2", "Notch", NpcConfig.Action.command("/warp  spawn "));
			NpcConfig.validate(ok);
			Assert.eq("warp  spawn", ok.npcs.get(0).action.command, "slash and surrounding whitespace removed");
			NpcConfig.Action upper = NpcConfig.Action.open("BANK", "");
			upper.type = "OPEN";
			NpcConfig norm = withNpc("n", "", upper);
			NpcConfig.validate(norm);
			Assert.eq("bank", norm.npcs.get(0).action.target, "target lowercase");
			NpcConfig nul = withNpc("n", "", null);
			NpcConfig.validate(nul);
			Assert.eq(NpcConfig.Action.NONE, nul.npcs.get(0).action.type, "missing action = none");
			NpcConfig.Text plain = new NpcConfig.Text("x", 0.5, 70, 0.5, "A");
			Assert.isTrue(plain.effectiveScale() == 1.0, "default scale");
			ctx.detail("ids, skins, slots, colors, actions, texts, collision, normalization");
		});

		SelfTests.register(F, "edit", ctx -> {
			NpcConfig cfg = new NpcConfig();
			Assert.eq(10.5, SPOT.x(), "block center x");
			Assert.eq(-5.5, SPOT.z(), "block center z");
			Assert.eq(70.0, SPOT.y(), "height to 1/100");
			Assert.eq(93f, SPOT.yaw(), "yaw whole number");
			Assert.eq(-90f, NpcEdits.Spot.of("minecraft:overworld", 0, 70, 0, 270f).yaw(), "Yaw 270 → -90");
			NpcConfig.Npc bank = NpcEdits.create(cfg, "Banker", "bank", "", SPOT);
			Assert.eq("banker", bank.id, "id lowercase");
			Assert.eq(NpcConfig.Action.OPEN, bank.action.type, "action open");
			Assert.eq("bank", bank.action.target, "target bank");
			Assert.eq(NpcActions.preset("bank").name(), bank.name, "template name");
			Assert.eq(NpcActions.preset("bank").skin(), bank.skin, "template skin");
			Assert.isFalse(bank.equipment.isEmpty(), "template equipment");
			NpcConfig.Npc guide = NpcEdits.create(cfg, "guide", "command", "Notch", SPOT);
			Assert.eq("Notch", guide.skin, "skin from the command");
			Assert.eq(NpcConfig.Action.NONE, guide.action.type, "command: comes later");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcEdits.create(cfg, "banker", "shop", "", SPOT), "id duplicate");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcEdits.create(cfg, "x", "no_such", "", SPOT), "unknown action");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcEdits.create(cfg, "x y", "bank", "", SPOT), "invalid id");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcEdits.create(cfg, "x", "bank", "bad skin!", SPOT), "invalid skin");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcEdits.create(cfg, "x", "bank", "",
				NpcEdits.Spot.of("minecraft:overworld", 0, 999, 0, 0)), "invalid height");
			Assert.eq(2, cfg.npcs.size(), "two NPCs (failed attempts change nothing)");
			NpcEdits.setCommand(cfg, "guide", "/warp {player}");
			Assert.eq("warp {player}", guide.action.command, "command set");
			NpcEdits.rename(cfg, "guide", "&6Guide &lX");
			Assert.eq("§6Guide §lX", guide.name, "& colors");
			NpcEdits.setTitle(cfg, "guide", "-");
			Assert.eq("", guide.description, "title empty");
			NpcEdits.setLook(cfg, "guide", false);
			Assert.isFalse(guide.lookAtPlayer, "head off");
			NpcEdits.setOpen(cfg, "banker", "shop", " wood ");
			Assert.eq("shop", bank.action.target, "target changed");
			Assert.eq("wood", bank.action.arg, "Argument");
			NpcEdits.move(cfg, "banker", NpcEdits.Spot.of("minecraft:the_nether", 1, 64, 1, -45));
			Assert.eq("minecraft:the_nether", bank.dimension, "moved (dimension)");
			Assert.eq(1.5, bank.x, "moved (x)");
			NpcConfig.validate(cfg);
			// The copy is independent (edits never touch the active object directly) and survives JSON.
			NpcConfig copy = NpcConfig.copy(cfg);
			NpcEdits.remove(copy, "banker");
			Assert.eq(2, cfg.npcs.size(), "original unchanged");
			Assert.eq(1, copy.npcs.size(), "copy: removed");
			Assert.eq("warp {player}", copy.npcs.get(0).action.command, "copy: action kept");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcEdits.remove(copy, "banker"), "remove twice");
			Assert.assertThrows(IllegalArgumentException.class, () -> NpcEdits.rename(copy, "ghost", "x"), "unknown NPC");
			ctx.detail("create (template, command, skin), failure cases, rename, title, look, action, move, remove, independent copy");
		});

		SelfTests.register(F, "actions", ctx -> {
			NpcActions.Resolved shop = NpcActions.resolve(NpcConfig.Action.open("shop", ""), "Steve", p -> true);
			Assert.eq("shop", shop.command(), "open shop");
			Assert.eq("shop wood", NpcActions.resolve(NpcConfig.Action.open("shop", "wood"), "Steve", p -> true).command(), "with argument");
			Assert.eq("ah", NpcActions.resolve(NpcConfig.Action.open("ah", ""), "Steve", p -> true).command(), "auction house");
			NpcActions.Resolved off = NpcActions.resolve(NpcConfig.Action.open("casino", ""), "Steve", p -> !p.module().equals("casino"));
			Assert.eq(NpcActions.UNAVAILABLE, off.error(), "disabled module -> message");
			Assert.eq("", off.command(), "disabled: no command");
			Assert.eq("pay Steve 1", NpcActions.resolve(NpcConfig.Action.command("pay {player} 1"), "Steve", p -> true).command(), "{player}");
			Assert.eq("", NpcActions.resolve(NpcConfig.Action.none(), "Steve", p -> true).command(), "none");
			Assert.isTrue(NpcActions.moduleRunning("npc"), "own module runs");
			Assert.isFalse(NpcActions.moduleRunning("no_such_module"), "unknown module doesn't run");
			List<String> avail = NpcActions.availableTargets(ctx.server());
			for (String t : avail) Assert.isTrue(NpcActions.moduleRunning(NpcActions.preset(t).module()), "available only with module: " + t);
			ctx.detail("open, argument, disabled, command, {player}, none; available: " + String.join(",", avail));
		});

		SelfTests.register(F, "layout", ctx -> {
			// Player directly south gives yaw 0; far behind is limited to 70 degrees.
			float[] r = NpcLayout.lookAt(0, 1.62, 0, 0, 1.62, 5, 0);
			Assert.isTrue(Math.abs(r[0]) < 0.01 && Math.abs(r[1]) < 0.01, "straight ahead");
			float[] back = NpcLayout.lookAt(0, 1.62, 0, 0.1, 1.62, -5, 0);
			Assert.isTrue(Math.abs(Math.abs(back[0]) - NpcLayout.MAX_HEAD_TURN) < 0.01, "head limited: " + back[0]);
			float[] up = NpcLayout.lookAt(0, 1.62, 0, 0, 30, 1, 0);
			Assert.isTrue(Math.abs(up[1] + NpcLayout.MAX_PITCH) < 0.01, "pitch limited (up = negative)");
			float[] west = NpcLayout.lookAt(0, 1.62, 0, -5, 1.62, 0, 90);
			Assert.isTrue(Math.abs(west[0] - 90) < 0.01, "west = 90");
			ctx.detail("head and pitch limited");
		});

		SelfTests.register(F, "skins-equipment", ctx -> {
			NpcLayout.ItemSpec s = NpcLayout.parseItemSpec("leather_chestplate#3FA9F5");
			Assert.eq("leather_chestplate", s.id(), "ID");
			Assert.eq(0x3FA9F5, s.color(), "color");
			Assert.eq(NpcLayout.ItemSpec.NONE, NpcLayout.parseItemSpec("compass").color(), "without color");
			ItemStack chest = NpcBody.equipmentStack("leather_chestplate#3FA9F5");
			Assert.notNull(chest.get(DataComponents.DYED_COLOR), "dyed");
			Assert.eq(0x3FA9F5, chest.get(DataComponents.DYED_COLOR).rgb(), "color value");
			Assert.isTrue(NpcBody.equipmentStack("no_such_item").isEmpty(), "unknown -> empty");
			Assert.isTrue(NpcLayout.profileTag("") == null, "empty -> default");
			ResolvableProfile vanilla = decode(NpcLayout.profileTag("wide/efe"));
			Assert.isTrue(vanilla.skinPatch().body().isPresent(), "texture override set");
			Assert.eq("slim", decode(NpcLayout.profileTag("slim/alex")).skinPatch().model().get().getSerializedName(), "slim");
			Assert.eq("Notch", decode(NpcLayout.profileTag("Notch")).name().orElse(""), "name profile");
			Assert.isFalse(NpcBody.resolveStack("diamond_pickaxe").isEmpty(), "item without namespace");
			Assert.isTrue(NpcBody.resolveStack("nomod:no_such_item").isEmpty(), "unknown item with namespace -> empty");
			FakePlayer fake = ctx.player("NpcEquip");
			ItemStack dyed = new ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET);
			dyed.set(DataComponents.DYED_COLOR, new DyedItemColor(0x00FF00));
			fake.setItemSlot(EquipmentSlot.HEAD, dyed);
			fake.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.GOLDEN_AXE));
			Map<String, String> gear = NpcBody.equipmentOf(fake);
			Assert.eq("leather_helmet#00FF00", gear.get("head"), "helmet with color");
			Assert.eq("golden_axe", gear.get("mainhand"), "Hand");
			Assert.eq(2, gear.size(), "only used slots");
			fake.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			ctx.detail("leather colors, vanilla skins (override), name profiles, copying equipment");
		});

		SelfTests.register(F, "welt", ctx -> {
			MinecraftServer server = ctx.server();
			ServerPlayer anchor = null;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!(p instanceof FakePlayer)) anchor = p;
			}
			if (anchor == null) {
				ctx.detail("skipped (no player online, needs a loaded chunk)");
				return;
			}
			ServerLevel level = anchor.level();
			double x = Math.floor(anchor.getX()) + 0.5, z = Math.floor(anchor.getZ()) + 0.5;
			double y = Math.min(anchor.getY() + 12, level.getMaxY() - 4);
			if (!Mc.canSpawnAt(level, x, z)) {
				ctx.detail("skipped (chunk still loading)");
				return;
			}
			NpcConfig cfg = new NpcConfig();
			cfg.enabled = true;
			NpcConfig.Npc st = NpcEdits.create(cfg, "selftest", "none", "wide/efe", NpcEdits.Spot.of(NpcManager.dimensionId(level), x, y, z, 90));
			st.name = "§bTester";
			st.description = "§7selftest";
			st.equipment.put("chest", "leather_chestplate#3FA9F5");
			st.equipment.put("mainhand", "compass");
			cfg.texts = new ArrayList<>(List.of(new NpcConfig.Text("selftest_text", x, y + 4, z, "§aHello", "§7World")));
			NpcConfig.validate(cfg);
			List<Entity> extra = new ArrayList<>();
			ctx.cleanup(() -> {
				for (Entity e : extra) if (!e.isRemoved()) e.discard();
				NpcManager.setOverride(null);
				NpcManager.ensureAll(server);
			});
			NpcManager.setOverride(cfg);
			NpcManager.ensureAll(server);
			Assert.eq(3, NpcManager.parts().size(), "3 parts (body, tag, text)");
			Assert.eq(3, NpcManager.liveCount(), "all placed");
			Assert.eq(3, count(level, NpcManager.TAG), "exactly 3 own entities");

			Entity body = NpcManager.live("n.selftest.body");
			Assert.isTrue(body instanceof Mannequin, "Mannequin");
			LivingEntity m = (LivingEntity) body;
			Assert.isTrue(m.isInvulnerable() && m.isNoGravity() && m.isSilent(), "invulnerable, floating, silent");
			Assert.eq("minecraft:leather_chestplate", dev.smpeconomy.core.Items.id(m.getItemBySlot(EquipmentSlot.CHEST).getItem()), "chest");
			ResolvableProfile prof = body.get(DataComponents.PROFILE);
			Assert.isTrue(prof != null && prof.skinPatch().body().isPresent(), "skin override on entity");
			Entity plate = NpcManager.live("n.selftest.plate");
			Assert.eq("Tester\nselftest", NpcManager.text(plate).getString(), "name tag");
			Assert.eq("Hello\nWorld", NpcManager.text(NpcManager.live("t.selftest_text")).getString(), "Text-Display");

			Display.TextDisplay copy = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
			copy.snapTo(x, y + 2, z, 0f, 0f);
			copy.addTag(NpcManager.TAG);
			copy.addTag(NpcManager.TAG_KEY + "n.selftest.plate");
			extra.add(copy);
			level.addFreshEntity(copy);
			NpcManager.processPending();
			Assert.isTrue(copy.isRemoved(), "copy removed");
			Assert.isFalse(plate.isRemoved(), "original stays");

			m.teleportTo(x + 2, y + 1, z);
			NpcManager.ensureAll(server);
			Assert.isTrue(m.distanceToSqr(x, y, z) < 0.01, "moved back");
			UUID old = m.getUUID();
			m.discard();
			NpcManager.ensureAll(server);
			NpcManager.processPending();
			NpcManager.ensureAll(server);
			Entity again = NpcManager.live("n.selftest.body");
			Assert.isTrue(again instanceof Mannequin && !again.getUUID().equals(old), "placed again");
			Assert.eq(3, count(level, NpcManager.TAG), "still exactly 3");

			FakePlayer fake = ctx.player("NpcSelftest");
			fake.snapTo(x + 1, y, z, 0f, 0f);
			Assert.eq(InteractionResult.SUCCESS, NpcManager.onClick(fake, false, again, true), "click on NPC intercepted");
			Assert.eq(InteractionResult.FAIL, NpcManager.onClick(fake, false, plate, false), "attack on tag intercepted");
			Assert.eq(InteractionResult.PASS, NpcManager.onClick(fake, false, fake, true), "foreign entity untouched");
			float hp = ((LivingEntity) again).getHealth();
			((LivingEntity) again).hurtServer(level, level.damageSources().playerAttack(fake), 50f);
			Assert.eq((long) hp, (long) ((LivingEntity) again).getHealth(), "invulnerable");

			NpcConfig removed = NpcConfig.copy(cfg);
			NpcEdits.remove(removed, "selftest");
			NpcManager.setOverride(removed);
			NpcManager.ensureAll(server);
			Assert.eq(1, count(level, NpcManager.TAG), "after remove only the text");
			Assert.isTrue(again.isRemoved(), "body removed");
			NpcManager.setOverride(cfg);
			NpcManager.ensureAll(server);
			Assert.eq(3, count(level, NpcManager.TAG), "exactly 3 again");

			NpcConfig off = new NpcConfig();
			off.enabled = false;
			NpcManager.setOverride(off);
			NpcManager.ensureAll(server);
			Assert.eq(0, count(level, NpcManager.TAG), "disabled: nothing left");
			ctx.detail("3 parts, skin, equipment, flags, copy removed, move back, replace, clicks, no damage, remove, disable");
		});
	}

	private static NpcConfig withNpc(String id, String skin, NpcConfig.Action action) {
		NpcConfig c = new NpcConfig();
		c.npcs = new ArrayList<>(List.of(new NpcConfig.Npc(id, 0.5, 70, 0.5, 0, "§aX", "", skin, action)));
		c.texts = new ArrayList<>();
		return c;
	}

	private static ResolvableProfile decode(Tag tag) {
		return ResolvableProfile.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	private static int count(ServerLevel level, String tag) {
		int n = 0;
		for (Entity e : level.getEntities(EntityTypeTest.forClass(Entity.class), e -> e.getTags().contains(tag))) {
			if (!e.isRemoved()) n++;
		}
		return n;
	}
}
