package dev.smpeconomy.features.starter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Scheduler;

/**
 * Gives the kit exactly once per kit version and player. Marker {@value #KEY} in {@code players.json} holds the last
 * kit version received. Items and marker land in the same save ({@link DataStore#requestPlayerSave}), so a crash in
 * between rolls both back together: never a double kit or a marker without a kit.
 */
final class StarterService {
	static final String KEY = "starter.kitVersion";
	static final long TICKS_PER_MINUTE = 1200;

	enum Outcome { GIVEN, DISABLED, EMPTY, ALREADY, NOT_NEW }

	record Given(Outcome outcome, int placed, int dropped, int skipped) {
		static Given of(Outcome o) {
			return new Given(o, 0, 0, 0);
		}
	}

	private static StarterConfig override;

	private StarterService() {}

	static StarterConfig config() {
		return override != null ? override : StarterConfig.get();
	}

	static void setOverride(StarterConfig cfg) {
		override = cfg;
	}

	/** Server thread. The greeting follows shortly after, behind the join message. */
	static void onJoin(MinecraftServer server, ServerPlayer player) {
		if (Bots.isBot(player)) return;
		long playTicks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
		Given g = giveOnJoin(player, playTicks);
		if (g.outcome() != Outcome.GIVEN) return;
		SmpEconomy.LOG.info("[SMPEco] starter: kit v{} given to {} ({} stacks, {} dropped, {} unreadable)", config().kitVersion,
			player.getPlainTextName(), g.placed(), g.dropped(), g.skipped());
		UUID id = player.getUUID();
		List<String> lines = List.copyOf(config().welcome);
		boolean dropped = g.dropped() > 0;
		Scheduler.runLater(20, () -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) return;
			for (String line : lines) Msg.send(p, Msg.prefixed(line));
			if (dropped) Msg.info(p, "§eYour inventory was full – part of the kit lies at your feet.");
		});
	}

	static Given giveOnJoin(ServerPlayer player, long playTicks) {
		StarterConfig cfg = config();
		if (!cfg.giveKit) return Given.of(Outcome.DISABLED);
		if (cfg.kit.isEmpty()) return Given.of(Outcome.EMPTY);
		UUID id = player.getUUID();
		if (PlayerDataService.getLong(id, KEY, 0) >= cfg.kitVersion) return Given.of(Outcome.ALREADY);
		if (cfg.onlyNewPlayers && playTicks >= cfg.newPlayerMaxMinutes * TICKS_PER_MINUTE) return Given.of(Outcome.NOT_NEW);
		return give(player, cfg);
	}

	/** Skips the marker and playtime checks and sets the marker. */
	static Given give(ServerPlayer player, StarterConfig cfg) {
		int placed = 0, dropped = 0, skipped = 0;
		for (StarterConfig.KitItem k : cfg.kit) {
			Optional<ItemStack> stack = stackOf(k);
			if (stack.isEmpty()) {
				skipped++;
				continue;
			}
			if (put(player, k.slot, stack.get())) placed++;
			else dropped++;
		}
		PlayerDataService.setLong(player.getUUID(), KEY, cfg.kitVersion);
		PlayerDataService.markDirty();
		DataStore.requestPlayerSave(player);
		player.containerMenu.broadcastChanges();
		return new Given(Outcome.GIVEN, placed, dropped, skipped);
	}

	/** Count may exceed the stack size. Empty if unreadable, which is logged, and the entry stays. */
	static Optional<ItemStack> stackOf(StarterConfig.KitItem k) {
		if (k.isEncoded()) {
			Optional<ItemStack> s = ItemCodec.tryDecode(k.item);
			return s.filter(st -> !st.isEmpty());
		}
		Optional<Item> item = Items.byId(k.id);
		if (item.isEmpty()) {
			SmpEconomy.LOG.error("[SMPEco] starter: unknown item {} in starter.json, skipped", k.id);
			return Optional.empty();
		}
		return Optional.of(new ItemStack(item.get(), k.count));
	}

	/**
	 * Into the wanted slot if it's free and everything fits, otherwise anywhere, the rest at the player's feet. True
	 * if nothing dropped.
	 */
	static boolean put(ServerPlayer player, int slot, ItemStack stack) {
		if (Items.canReceive(player) && stack.getCount() <= stack.getMaxStackSize()) {
			EquipmentSlot eq = equipmentSlot(slot);
			if (eq != null) {
				if (player.getItemBySlot(eq).isEmpty()) {
					player.setItemSlot(eq, stack.copy());
					return true;
				}
			} else if (slot >= 0 && slot < Items.MAIN_SIZE) {
				Inventory inv = player.getInventory();
				if (inv.getItem(slot).isEmpty()) {
					inv.setItem(slot, stack.copy());
					return true;
				}
			}
		}
		return Items.giveOrDrop(player, stack) == 0;
	}

	/** 36 feet, 37 legs, 38 chest, 39 head, 40 offhand, otherwise null. */
	static EquipmentSlot equipmentSlot(int slot) {
		return switch (slot) {
			case 36 -> EquipmentSlot.FEET;
			case 37 -> EquipmentSlot.LEGS;
			case 38 -> EquipmentSlot.CHEST;
			case 39 -> EquipmentSlot.HEAD;
			case 40 -> EquipmentSlot.OFFHAND;
			default -> null;
		};
	}

	/** Slots are preserved, all components through ItemCodec. */
	static List<StarterConfig.KitItem> capture(ServerPlayer player) {
		List<StarterConfig.KitItem> out = new ArrayList<>();
		Inventory inv = player.getInventory();
		for (int i = 0; i < Items.MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) out.add(StarterConfig.KitItem.encoded(i, ItemCodec.encode(s.copy())));
		}
		for (int slot = 36; slot <= 40; slot++) {
			ItemStack s = player.getItemBySlot(equipmentSlot(slot));
			if (!s.isEmpty()) out.add(StarterConfig.KitItem.encoded(slot, ItemCodec.encode(s.copy())));
		}
		return out;
	}

	/** Also finds offline players, by name in players.json; empty if unknown. */
	static Optional<UUID> findPlayer(MinecraftServer server, String name) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) return Optional.of(online.getUUID());
		for (UUID id : PlayerDataService.knownPlayers()) {
			Optional<PlayerDataService.PlayerData> d = PlayerDataService.peek(id);
			if (d.isPresent() && d.get().name.equalsIgnoreCase(name)) return Optional.of(id);
		}
		return Optional.empty();
	}

	static String describe(StarterConfig.KitItem k) {
		return stackOf(k).map(s -> s.getCount() + "x " + s.getHoverName().getString()).orElse("(unreadable – kept)");
	}

	static String slotName(int slot) {
		return switch (slot) {
			case -1 -> "any";
			case 36 -> "feet";
			case 37 -> "legs";
			case 38 -> "chest";
			case 39 -> "head";
			case 40 -> "offhand";
			default -> slot < 9 ? "hotbar " + slot : "slot " + slot;
		};
	}
}
