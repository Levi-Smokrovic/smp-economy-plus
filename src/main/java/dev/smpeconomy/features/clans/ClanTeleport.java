package dev.smpeconomy.features.clans;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.Home;
import dev.smpeconomy.features.clans.ClanService.Code;
import dev.smpeconomy.features.clans.ClanService.Res;

/**
 * {@code /clan home}: teleports after {@code teleportDelaySeconds}, cancelled when the player moves more than
 * {@link #MOVE_TOLERANCE} blocks, changes dimension, dies or leaves. One tick task checks all pending
 * teleports, and everything is rechecked at trigger (clan, membership, home, home lock). Joining an open clan
 * without an invite delays teleporting by {@link ClanConfig#openJoinHomeMinutes} ({@link
 * ClanService#homeLockRemaining}).
 */
public final class ClanTeleport {
	/** Head turning and slight sliding do not count as movement. */
	public static final double MOVE_TOLERANCE = 0.3;

	public enum Step { WAITING, DONE, CANCELLED, FAILED }

	static final class Pending {
		final ServerPlayer player;
		final Vec3 start;
		final ResourceKey<Level> dimension;
		final int clanId;
		int ticksLeft;

		Pending(ServerPlayer player, int clanId, int ticks) {
			this.player = player;
			this.start = player.position();
			this.dimension = player.level().dimension();
			this.clanId = clanId;
			this.ticksLeft = ticks;
		}
	}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static Scheduler.Task ticker;

	private ClanTeleport() {}

	/** Called from onServerStarted; the scheduler drops everything on stop. */
	static void start() {
		PENDING.clear();
		ticker = Scheduler.runRepeating(1, 1, ClanTeleport::tick);
	}

	static void stop() {
		PENDING.clear();
		if (ticker != null) ticker.cancel();
		ticker = null;
	}

	public static boolean isPending(UUID player) {
		return PENDING.containsKey(player);
	}

	public static void cancel(UUID player) {
		PENDING.remove(player);
	}

	/** Replaces a running teleport of the same player. */
	public static Res request(ServerPlayer player) {
		Clan c = ClanService.clanOf(player.getUUID()).orElse(null);
		if (c == null) return ClanService.fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (c.home == null) return ClanService.fail(Code.NO_HOME, "Your clan has no Clan Home. §7(Leader/officers: /clan sethome)", c);
		long lock = ClanService.homeLockRemaining(c, player.getUUID(), System.currentTimeMillis());
		if (lock > 0) return ClanService.fail(Code.HOME_LOCKED, ClanService.homeLockMessage(lock), c);
		if (level(player, c.home) == null) return ClanService.fail(Code.FAILED, "The Clan Home's dimension doesn't exist anymore.", c);
		int ticks = ClanService.config().teleportDelaySeconds * 20;
		PENDING.put(player.getUUID(), new Pending(player, c.id, ticks));
		Msg.actionBar(player, "§bTeleporting in §f" + ClanService.config().teleportDelaySeconds + "s §7– don't move");
		return ClanService.ok(c, "Teleporting to the Clan Home in §f" + ClanService.config().teleportDelaySeconds + "s§a – don't move!");
	}

	private static void tick() {
		if (PENDING.isEmpty()) return;
		for (UUID id : List.copyOf(PENDING.keySet())) {
			Pending p = PENDING.get(id);
			if (p == null) continue;
			Step s = step(p);
			if (s != Step.WAITING) PENDING.remove(id, p);
		}
	}

	/** Also used by selftests. */
	static Step step(Pending p) {
		ServerPlayer player = p.player;
		if (player.isRemoved() || player.hasDisconnected() || !player.isAlive()) return Step.CANCELLED;
		if (player.level().dimension() != p.dimension || moved(p.start, player.position())) {
			Msg.error(player, "Teleport cancelled – you moved.");
			Msg.actionBar(player, "§cTeleport cancelled");
			Gui.failSound(player);
			return Step.CANCELLED;
		}
		p.ticksLeft--;
		if (p.ticksLeft > 0) {
			if (p.ticksLeft % 20 == 0) Msg.actionBar(player, "§bTeleporting in §f" + (p.ticksLeft / 20) + "s §7– don't move");
			return Step.WAITING;
		}
		Clan c = ClanService.clanOf(player.getUUID()).orElse(null);
		if (c == null || c.id != p.clanId) {
			Msg.error(player, "Teleport cancelled – you're no longer in the clan.");
			return Step.FAILED;
		}
		if (c.home == null) {
			Msg.error(player, "Teleport cancelled – the Clan Home was removed.");
			return Step.FAILED;
		}
		long lock = ClanService.homeLockRemaining(c, player.getUUID(), System.currentTimeMillis());
		if (lock > 0) {
			Msg.error(player, "Teleport cancelled. " + ClanService.homeLockMessage(lock));
			return Step.FAILED;
		}
		ServerLevel level = level(player, c.home);
		if (level == null) {
			Msg.error(player, "The Clan Home's dimension doesn't exist anymore.");
			return Step.FAILED;
		}
		Home h = c.home;
		if (!player.teleportTo(level, h.x, h.y, h.z, Set.of(), h.yaw, h.pitch, true)) {
			Msg.error(player, "Teleport failed.");
			return Step.FAILED;
		}
		if (!player.isFallFlying()) {
			player.setDeltaMovement(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
			player.setOnGround(true);
		}
		Msg.success(player, "Welcome to the Clan Home of " + c.display() + "§a.");
		Gui.successSound(player);
		return Step.DONE;
	}

	public static boolean moved(Vec3 start, Vec3 now) {
		return start.distanceToSqr(now) > MOVE_TOLERANCE * MOVE_TOLERANCE;
	}

	static ServerLevel level(ServerPlayer player, Home home) {
		Identifier id = Identifier.tryParse(home.dim);
		if (id == null) return null;
		return player.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	/** For selftests. */
	static Pending pending(UUID player) {
		return PENDING.get(player);
	}

	static Home homeOf(ServerPlayer player) {
		Home h = new Home();
		h.dim = player.level().dimension().identifier().toString();
		h.x = player.getX();
		h.y = player.getY();
		h.z = player.getZ();
		h.yaw = player.getYRot();
		h.pitch = player.getXRot();
		return h;
	}
}
