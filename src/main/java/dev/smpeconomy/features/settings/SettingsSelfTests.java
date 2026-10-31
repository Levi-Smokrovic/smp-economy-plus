package dev.smpeconomy.features.settings;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.features.settings.mixin.PatrolSpawnerInvoker;

/** GUI clicks are covered by e2e/settings.mjs. */
final class SettingsSelfTests {
	private static final String F = "settings";

	private SettingsSelfTests() {}

	/** The spawn switches are off by default in config, so these tests enable them (config-off has its own test). */
	private static void reg(String name, SelfTests.Test test) {
		SelfTests.register(F, name, ctx -> {
			ctx.cleanup(SettingsConfig.overrideForTest(true, true));
			test.run(ctx);
		});
	}

	static void register() {
		SelfTests.register(F, "spawn-toggles-config-off", ctx -> {
			ctx.cleanup(SettingsConfig.overrideForTest(false, false));
			FakePlayer a = ctx.player("SettinOff");
			UUID id = a.getUUID();
			PlayerDataService.setSetting(id, Settings.MOBSPAWN, "false");
			PlayerDataService.setSetting(id, Settings.PHANTOMS, "false");
			ctx.cleanup(() -> {
				PlayerDataService.setSetting(id, Settings.MOBSPAWN, null);
				PlayerDataService.setSetting(id, Settings.PHANTOMS, null);
			});
			List<String> keys = new ArrayList<>();
			for (Settings.Def d : Settings.all()) keys.add(d.key());
			isFalse(keys.contains(Settings.MOBSPAWN), "mobspawn not in /settings: " + keys);
			isFalse(keys.contains(Settings.PHANTOMS), "phantoms not in /settings: " + keys);
			isTrue(keys.contains(Settings.PAY_RECEIVE) || !dev.smpeconomy.core.module.Modules.isEnabled("economy"), "other switches stay");
			eq(Optional.empty(), SettingsLogic.toggle(id, Settings.MOBSPAWN), "mobspawn not toggleable");
			isFalse(SettingsLogic.set(id, Settings.PHANTOMS, true), "phantoms not settable");
			BlockPos pos = a.blockPosition();
			isFalse(SpawnRules.blocksNaturalSpawn(List.of(a), MobCategory.MONSTER, EntityType.ZOMBIE, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
				"stored mobspawn=false has no effect");
			isFalse(SpawnRules.skipPhantomsFor(a), "stored phantoms=false has no effect");
			ctx.detail("settings.json: both spawn switches off (default) -> invisible and without effect");
		});
		reg("toggles-persist", ctx -> {
			FakePlayer a = ctx.player("SettinA");
			UUID id = a.getUUID();
			for (Settings.Def d : Settings.all()) eq(d.defaultValue(), Settings.get(id, d.key()), "no entry = default: " + d.key());
			eq(0, SettingsLogic.changedCount(id), "nothing changed");

			List<String> events = new ArrayList<>();
			Hooks.Registration reg = Settings.addListener((p, k, v) -> {
				if (p.equals(id)) events.add(k + "=" + v);
			});
			ctx.cleanup(reg::remove);

			eq(Optional.of(false), SettingsLogic.toggle(id, Settings.MOBSPAWN), "toggle -> off");
			isFalse(Settings.get(id, Settings.MOBSPAWN), "mobspawn off");
			isTrue(Settings.get(id, Settings.PHANTOMS), "other switches untouched");
			eq("false", PlayerDataService.getSetting(id, Settings.MOBSPAWN, null), "stored in PlayerData");
			eq(1, SettingsLogic.changedCount(id), "1 changed");
			eq(List.of("mobspawn=false"), events, "listener exactly once");

			String file = "-";
			if (DataStore.dataDir() != null) {
				DataStore.flushDirty();
				eq("false", savedSetting(id, Settings.MOBSPAWN), "players.json: mobspawn=false");
				file = "players.json";
			}

			eq(Optional.of(true), SettingsLogic.toggle(id, Settings.MOBSPAWN), "toggle back -> on");
			eq(null, PlayerDataService.getSetting(id, Settings.MOBSPAWN, null), "default is not stored");
			if (DataStore.dataDir() != null) {
				DataStore.flushDirty();
				eq(null, savedSetting(id, Settings.MOBSPAWN), "players.json: entry removed");
			}
			eq(List.of("mobspawn=false", "mobspawn=true"), events, "listener on toggling back");

			isTrue(SettingsLogic.set(id, Settings.MOBSPAWN, true), "set known");
			eq(2, events.size(), "same value -> no listener");
			isTrue(SettingsLogic.toggle(id, "nonexistent.xyz").isEmpty(), "unknown key: toggle empty");
			isFalse(SettingsLogic.set(id, "nonexistent.xyz", false), "unknown key: set false");
			eq(null, PlayerDataService.getSetting(id, "nonexistent.xyz", null), "unknown not stored");

			SettingsLogic.set(id, Settings.PHANTOMS, false);
			SettingsLogic.set(id, Settings.DEATH_COORDS, false);
			eq(2, SettingsLogic.changedCount(id), "2 changed");
			eq(2, SettingsLogic.resetAll(id), "reset changes 2");
			eq(0, SettingsLogic.changedCount(id), "default afterwards");
			eq(0, SettingsLogic.resetAll(id), "second reset changes nothing");
			isTrue(PlayerDataService.peek(id).map(d -> d.settings.isEmpty()).orElse(true), "no entries left");
			ctx.detail("toggle/listener/reset ok, file: " + file);
		});

		reg("on-off-and-wrap", ctx -> {
			for (String s : List.of("on", "ON", " on ", "true", "1", "yes")) eq(Optional.of(true), SettingsLogic.parseOnOff(s), "on: '" + s + "'");
			for (String s : List.of("off", "Off", "false", "0", "no")) eq(Optional.of(false), SettingsLogic.parseOnOff(s), "off: '" + s + "'");
			for (String s : new String[] {"", "maybe", "2", null}) isTrue(SettingsLogic.parseOnOff(s).isEmpty(), "invalid: " + s);
			List<String> lines = SettingsLogic.wrap("§7When off, no natural hostile mobs spawn within 64 blocks of you.", 20);
			isTrue(lines.size() >= 3, "wrapped");
			for (String l : lines) {
				isTrue(l.length() <= 20 || !l.contains(" "), "line too long: " + l);
				isFalse(l.contains("§"), "no § codes: " + l);
			}
			eq("When off, no natural hostile mobs spawn within 64 blocks of you.", String.join(" ", lines), "no word lost");
			eq(List.of("Supercalifragilistic"), SettingsLogic.wrap("Supercalifragilistic", 5), "overlong word stays whole");
			eq(List.of(), SettingsLogic.wrap("   ", 10), "empty");
		});

		reg("spawn-two-players", ctx -> {
			FakePlayer a = ctx.player("SettinA");
			FakePlayer b = ctx.player("SettinB");
			Settings.set(a.getUUID(), Settings.MOBSPAWN, false);
			double x = 5000.5, y = 80, z = -5000.5;
			List<FakePlayer> both = List.of(a, b);

			a.setPos(x + 10, y, z);
			b.setPos(x + 30, y, z);
			isTrue(blocked(both, x, y, z), "A (off) is nearest -> blocked");
			eq(a, SpawnRules.decidingPlayer(both, x, y, z), "A decides");
			isFalse(SpawnRules.blocksNaturalSpawn(both, MobCategory.CREATURE, EntityType.COW, x, y, z), "animals untouched");
			isFalse(SpawnRules.blocksNaturalSpawn(both, MobCategory.WATER_CREATURE, EntityType.SQUID, x, y, z), "water animals untouched");
			isTrue(SpawnRules.blocksNaturalSpawn(both, MobCategory.MONSTER, null, x, y, z), "category MONSTER is enough");

			b.setPos(x - 5, y, z);
			isFalse(blocked(both, x, y, z), "B (on) is nearer -> allowed");
			eq(b, SpawnRules.decidingPlayer(both, x, y, z), "B decides");

			a.setPos(x + 60, y, z);
			b.setPos(x + 50, y, z);
			isFalse(blocked(both, x, y, z), "B (on, 50) before A (off, 60) -> allowed");

			List<FakePlayer> onlyA = List.of(a);
			a.setPos(x + 64, y, z);
			isTrue(blocked(onlyA, x, y, z), "exactly 64 blocks -> blocked");
			a.setPos(x + 64.05, y, z);
			isFalse(blocked(onlyA, x, y, z), "just over 64 -> allowed");
			eq(null, SpawnRules.decidingPlayer(onlyA, x, y, z), "nobody in radius");
			a.setPos(x, y + 65, z);
			isFalse(blocked(onlyA, x, y, z), "65 blocks above -> allowed");
			a.setPos(x + 40, y - 40, z);
			isTrue(blocked(onlyA, x, y, z), "56.6 blocks diagonal -> blocked");

			a.setPos(x + 70, y, z);
			b.setPos(x + 80, y, z);
			isFalse(blocked(both, x, y, z), "nearest player beyond 64 -> allowed");

			Settings.set(b.getUUID(), Settings.MOBSPAWN, false);
			a.setPos(x + 10, y, z);
			b.setPos(x + 5, y, z);
			isTrue(blocked(both, x, y, z), "both off -> blocked");
			isFalse(blocked(List.of(), x, y, z), "no players -> allowed");
			ctx.detail("nearest player within 64 decides, MONSTER only");
		});

		reg("nearest-core", ctx -> {
			record Cand(String name, double dist, boolean spectator) {}
			List<Cand> list = List.of(new Cand("spectator", 1, true), new Cand("far", 30, false), new Cand("near", 20, false),
				new Cand("broken", Double.NaN, false));
			Cand c = SpawnRules.nearestWithin(list, k -> k.dist() * k.dist(), k -> !k.spectator(), 64);
			eq("near", c == null ? null : c.name(), "spectators and NaN don't count");
			eq(null, SpawnRules.nearestWithin(List.of(new Cand("x", 64.5, false)), k -> k.dist() * k.dist(), k -> true, 64), "outside");
			eq("x", SpawnRules.nearestWithin(List.of(new Cand("x", 64, false)), k -> k.dist() * k.dist(), k -> true, 64).name(), "limit inclusive");
			eq(null, SpawnRules.nearestWithin(List.<Cand>of(), k -> 0, k -> true, 64), "empty");
			eq("a", SpawnRules.nearestWithin(List.of(new Cand("a", 5, false), new Cand("b", 5, false)), k -> k.dist() * k.dist(), k -> true, 64).name(),
				"tie: the first");
		});

		reg("phantoms", ctx -> {
			FakePlayer a = ctx.player("SettinA");
			FakePlayer b = ctx.player("SettinB");
			Settings.set(a.getUUID(), Settings.PHANTOMS, false);
			isTrue(SpawnRules.skipPhantomsFor(a), "A (off) is skipped");
			isFalse(SpawnRules.skipPhantomsFor(b), "B (on) is not");
			SpawnRules.beginPhantomDryRun();
			Map<UUID, Boolean> rec;
			try {
				isTrue(SpawnRules.skipPhantomsFor(b), "dry run skips everyone");
				isTrue(SpawnRules.skipPhantomsFor(a), "dry run A");
			} finally {
				rec = SpawnRules.endPhantomDryRun();
			}
			eq(Boolean.FALSE, rec.get(b.getUUID()), "recorded: B would spawn");
			eq(Boolean.TRUE, rec.get(a.getUUID()), "recorded: A skipped");
			isFalse(SpawnRules.phantomDryRunActive(), "dry run ended");
			isFalse(SpawnRules.skipPhantomsFor(b), "back to normal afterwards");
			Settings.set(a.getUUID(), Settings.PHANTOMS, true);
			isFalse(SpawnRules.skipPhantomsFor(a), "A on again");
		});

		reg("spawn-events", ctx -> {
			FakePlayer a = ctx.player("SettinA");
			FakePlayer b = ctx.player("SettinB");
			Settings.set(a.getUUID(), Settings.MOBSPAWN, false);
			double x = 5000.5, y = 80, z = -5000.5;
			List<FakePlayer> both = List.of(a, b);
			List<EntityType<?>> monsters = List.of(EntityType.PILLAGER, EntityType.ZOMBIE, EntityType.ZOMBIFIED_PIGLIN);

			a.setPos(x + 40, y, z + 30);
			b.setPos(x + 90, y, z);
			for (EntityType<?> t : monsters) isTrue(SpawnRules.blocksSpawnAt(both, t, x, y, z), "A (off) nearest -> blocked: " + t);
			isFalse(SpawnRules.blocksSpawnAt(both, EntityType.IRON_GOLEM, x, y, z), "golems untouched");
			isFalse(SpawnRules.blocksSpawnAt(both, EntityType.CAT, x, y, z), "cats untouched");
			isFalse(SpawnRules.blocksSpawnAt(both, null, x, y, z), "no type, nothing blocked");

			b.setPos(x + 20, y, z);
			for (EntityType<?> t : monsters) isFalse(SpawnRules.blocksSpawnAt(both, t, x, y, z), "B (on) nearer -> allowed: " + t);

			b.setPos(x + 200, y, z);
			a.setPos(x + 32, y + 2, z);
			isTrue(SpawnRules.blocksSpawnAt(both, EntityType.ZOMBIE, x, y, z), "siege zombie next to A blocked");
			a.setPos(x + 64.5, y, z);
			isFalse(SpawnRules.blocksSpawnAt(both, EntityType.ZOMBIE, x, y, z), "beyond 64 -> allowed");
			ctx.detail("pillagers, zombies, portal piglins like natural spawns; golems and cats untouched");
		});

		reg("mixins-active", ctx -> {
			isTrue(hasHandler(NaturalSpawner.class, "smpeco$mobspawnSetting"), "NaturalSpawner mixin applied");
			isTrue(hasHandler(PhantomSpawner.class, "smpeco$phantomSetting"), "PhantomSpawner mixin applied");
			isTrue(hasHandler(PatrolSpawner.class, "smpeco$patrolSetting"), "PatrolSpawner mixin applied");
			isTrue(hasHandler(VillageSiege.class, "smpeco$siegeSetting"), "VillageSiege mixin applied");
			isTrue(hasHandler(NetherPortalBlock.class, "smpeco$portalSetting"), "NetherPortalBlock mixin applied");
			isTrue(PatrolSpawnerInvoker.class.isAssignableFrom(PatrolSpawner.class), "PatrolSpawner invoker (diagnostics) applied");
			ctx.detail("6 Mixins");
		});

		reg("death-coordinates", ctx -> {
			FakePlayer a = ctx.player("SettinA");
			a.setPos(12.7, 64, -45.2);
			Optional<MutableComponent> msg = DeathCoords.message(a);
			isTrue(msg.isPresent(), "default on -> message");
			String text = msg.get().getString();
			isTrue(text.contains("X 12, Y 64, Z -46"), "coordinates (block, rounded down): " + text);
			isTrue(text.contains("You died at X 12, Y 64, Z -46 (Overworld)"), "Dimension: " + text);
			isTrue(text.startsWith(Msg.legacy(Msg.prefix()).getString()), "prefix: " + text);
			eq("12 64 -46", DeathCoords.copyText(new BlockPos(12, 64, -46)), "copy text");
			isTrue(hasCopy(msg.get(), "12 64 -46"), "click copies the coordinates");

			Settings.set(a.getUUID(), Settings.DEATH_COORDS, false);
			isTrue(DeathCoords.message(a).isEmpty(), "switch off -> no message");

			eq("Overworld", DeathCoords.dimensionLabel(Level.OVERWORLD), "overworld");
			eq("Nether", DeathCoords.dimensionLabel(Level.NETHER), "Nether");
			eq("End", DeathCoords.dimensionLabel(Level.END), "End");
			eq("smpeco:testworld", DeathCoords.dimensionLabel(ResourceKey.create(Registries.DIMENSION, Identifier.parse("smpeco:testworld"))), "custom dimension");
		});

		reg("gui-layout", ctx -> {
			eq(1, SettingsGui.pages(0), "0 -> 1 page");
			eq(1, SettingsGui.pages(9), "9 -> 1 page");
			eq(1, SettingsGui.pages(14), "14 -> 1 page");
			eq(2, SettingsGui.pages(15), "15 -> 2 pages");
			eq(3, SettingsGui.pages(29), "29 -> 3 pages");
			Set<Integer> reserved = Set.of(SettingsGui.SLOT_INFO, SettingsGui.SLOT_PREV, SettingsGui.SLOT_CLOSE, SettingsGui.SLOT_NEXT, SettingsGui.SLOT_RESET);
			for (int n = 0; n <= SettingsGui.PER_PAGE; n++) {
				int[] slots = SettingsGui.iconSlots(n);
				eq(n, slots.length, "slot count at " + n);
				Set<Integer> used = new HashSet<>();
				for (int s : slots) {
					int row = s / 9, col = s % 9;
					isTrue(row == 1 || row == 3, "icon in row 1/3: " + s);
					isTrue(col >= 1 && col <= 7, "inside the frame: " + s);
					isTrue(used.add(s) && used.add(s + 9), "used twice: " + s);
					isFalse(reserved.contains(s) || reserved.contains(s + 9), "collides with button: " + s);
				}
				int[] cols = SettingsGui.columns(Math.min(n, SettingsGui.PER_ROW));
				for (int i = 0; i < cols.length; i++) eq(8, cols[i] + cols[cols.length - 1 - i], "symmetric at " + n);
			}
			eq(List.of(10, 11, 12, 13, 14, 15, 16, 30, 32), toList(SettingsGui.iconSlots(9)), "9 switches (default)");
			isTrue(SettingsGui.iconSlots(99).length == SettingsGui.PER_PAGE, "at most 14 per page");

			for (Settings.Def d : Settings.all()) {
				ItemStack on = SettingsGui.icon(d, true).asStack();
				ItemStack off = SettingsGui.icon(d, false).asStack();
				isTrue(Boolean.TRUE.equals(on.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)), "on glows: " + d.key());
				isFalse(Boolean.TRUE.equals(off.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)), "off doesn't glow: " + d.key());
				ItemLore lore = on.get(DataComponents.LORE);
				check(lore != null && lore.lines().stream().anyMatch(l -> l.getString().contains("Status: On")), "status in lore: " + d.key());
				eq("✔ On", SettingsGui.toggle(d, true).asStack().getHoverName().getString(), "switch on: " + d.key());
				eq("✘ Off", SettingsGui.toggle(d, false).asStack().getHoverName().getString(), "switch off: " + d.key());
				eq("minecraft:lime_dye", dev.smpeconomy.core.Items.id(SettingsGui.toggle(d, true).asStack().getItem()), "green dye");
				eq("minecraft:red_dye", dev.smpeconomy.core.Items.id(SettingsGui.toggle(d, false).asStack().getItem()), "red dye");
			}
			ctx.detail(Settings.all().size() + " switches, " + SettingsGui.pages(Settings.all().size()) + " page(s)");
		});

		reg("admin-summary", ctx -> {
			FakePlayer a = ctx.player("SettinA");
			Settings.set(a.getUUID(), Settings.PHANTOMS, false);
			String line = SettingsCommand.summary(a.getUUID());
			isTrue(line.startsWith("settings "), "prefix: " + line);
			isTrue(line.contains(" phantoms=false"), "phantoms=false: " + line);
			isTrue(line.contains(" mobspawn=true"), "mobspawn=true: " + line);
			for (Settings.Def d : Settings.all()) isTrue(line.contains(" " + d.key() + "="), "contains " + d.key());
		});
	}

	private static boolean blocked(List<FakePlayer> players, double x, double y, double z) {
		return SpawnRules.blocksNaturalSpawn(players, MobCategory.MONSTER, EntityType.ZOMBIE, x, y, z);
	}

	private static String savedSetting(UUID id, String key) throws Exception {
		Path file = DataStore.dataDir().resolve("players.json");
		JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
		JsonObject players = root.getAsJsonObject().getAsJsonObject("players");
		check(players != null, "players.json without 'players'");
		JsonObject p = players.getAsJsonObject(id.toString());
		check(p != null, "test player not in players.json");
		JsonObject settings = p.getAsJsonObject("settings");
		if (settings == null || !settings.has(key)) return null;
		return settings.get(key).getAsString();
	}

	private static boolean hasHandler(Class<?> target, String handlerName) {
		for (Method m : target.getDeclaredMethods()) {
			if (m.getName().contains(handlerName)) return true;
		}
		return false;
	}

	private static boolean hasCopy(Component c, String value) {
		if (c.getStyle().getClickEvent() instanceof ClickEvent.CopyToClipboard copy && value.equals(copy.value())) return true;
		for (Component sibling : c.getSiblings()) {
			if (hasCopy(sibling, value)) return true;
		}
		return false;
	}

	private static List<Integer> toList(int[] a) {
		List<Integer> list = new ArrayList<>();
		for (int v : a) list.add(v);
		return list;
	}
}
