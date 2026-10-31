package dev.smpeconomy.core.test;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.PlayerDataService;

/**
 * FakePlayers with fixed UUIDs derived from the name, which never collide with real or offline UUIDs. Clean up after
 * the test with {@link #cleanup}.
 */
public final class TestPlayers {
	private static final Set<UUID> KNOWN = new HashSet<>();
	private static final List<Consumer<UUID>> CLEANUP_HOOKS = new ArrayList<>();
	private static final Map<UUID, List<ItemStack>> DROPS = new HashMap<>();

	private TestPlayers() {}

	public static UUID uuid(String name) {
		UUID id = UUID.nameUUIDFromBytes(("smpeco-selftest:" + name).getBytes(StandardCharsets.UTF_8));
		KNOWN.add(id);
		return id;
	}

	public static boolean isTestPlayer(UUID id) {
		return KNOWN.contains(id);
	}

	public static FakePlayer get(MinecraftServer server, String name) {
		ServerLevel level = server.overworld();
		FakePlayer player = FakePlayer.get(level, new GameProfile(uuid(name), name));
		var spawn = level.getRespawnData().pos();
		player.setPos(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5);
		player.getInventory().clearContent();
		return player;
	}

	public static void onCleanup(Consumer<UUID> hook) {
		CLEANUP_HOOKS.add(hook);
	}

	/**
	 * Removes a test player's drop from the world at once, or real items would lie at spawn for bots and players to
	 * pick up, and records it for {@code TestContext.drops(player)}.
	 */
	public static void discardDrop(UUID player, ItemEntity entity) {
		DROPS.computeIfAbsent(player, k -> new ArrayList<>()).add(entity.getItem().copy());
		entity.discard();
	}

	public static List<ItemStack> drops(UUID player) {
		List<ItemStack> list = DROPS.get(player);
		if (list == null) return List.of();
		List<ItemStack> copy = new ArrayList<>(list.size());
		for (ItemStack s : list) copy.add(s.copy());
		return copy;
	}

	public static void cleanup(FakePlayer player) {
		UUID id = player.getUUID();
		player.getInventory().clearContent();
		DROPS.remove(id);
		for (Consumer<UUID> hook : CLEANUP_HOOKS) {
			try {
				hook.accept(id); // a broken hook must not block the others or the deletion below
			} catch (RuntimeException e) {
				dev.smpeconomy.SmpEconomy.LOG.error("[SMPEco] Test player cleanup: hook threw for {}", id, e);
			}
		}
		EconomyService.deleteAccount(id);
		PlayerDataService.delete(id);
	}
}
