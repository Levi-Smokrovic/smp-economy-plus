package dev.smpeconomy.features.crates;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.features.crates.CrateService.DeliverResult;
import dev.smpeconomy.features.crates.CrateService.PendingReward;

/**
 * Opened crate state without GUI, so it can be tested. {@link #finish} delivers at most once, however many of
 * animation end, close, death, disconnect or server stop occur. Even an outside second call does nothing,
 * because {@link CrateService#deliver} removes the entry the first time.
 */
final class SpinSession {
	private final UUID player;
	private final String crate;
	private final PendingReward reward;
	private DeliverResult result;

	SpinSession(UUID player, String crate, PendingReward reward) {
		this.player = player;
		this.crate = crate;
		this.reward = reward;
	}

	UUID player() {
		return player;
	}

	String crate() {
		return crate;
	}

	PendingReward reward() {
		return reward;
	}

	boolean finished() {
		return result != null;
	}

	DeliverResult result() {
		return result;
	}

	/**
	 * @param interactive player is present (animation over or closed themselves), so overflow may drop at their
	 * feet. Otherwise only with enough space; the reward stays pending.
	 */
	DeliverResult finish(ServerPlayer p, boolean interactive, HolderLookup.Provider registries) {
		if (result != null) return result;
		result = CrateService.deliver(p, reward.id, interactive, registries);
		return result;
	}
}
