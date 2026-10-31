package dev.smpeconomy.features.casino.games.wheel;

import java.math.BigInteger;
import java.util.UUID;
import java.util.random.RandomGenerator;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Starting a wheel round (no GUI) through {@link Casino#startInstant}: admission, stake, draw, saved decided round.
 * Settling is {@link Casino#settle}.
 *
 * <p>The round is decided ({@code payout} fixed, {@code reserved} = payout, landing segment in {@code card}) in the same
 * server tick in which the stake is collected, and saved with it, nothing about the result depends on the animation.
 */
final class WheelRounds {
	private WheelRounds() {}

	/** Start of a round: the round or a message for the player. */
	record Start(Round round, String error) {
		boolean ok() {
			return round != null;
		}
	}

	/** Highest possible payout for this stake (the Treasury must be able to cover it at admission). */
	static long potential(long stake) {
		BigInteger p = BigInteger.valueOf(Math.max(0, stake)).multiply(BigInteger.valueOf(WheelMath.maxTenths())).divide(BigInteger.TEN);
		return p.bitLength() <= 63 ? p.longValue() : Long.MAX_VALUE;
	}

	/**
	 * Highest stake the wheel would admit right now (ignoring the player's balance), 0 = locked: casino max bet, daily loss
	 * room and the Treasury's free coverage for the 50× jackpot.
	 */
	static long maxStake(UUID player) {
		return Casino.maxStake(player, WheelGame.GAME);
	}

	/** Admission, stake to the Treasury, draw. The result is decided and saved before anything is shown. */
	static Start start(UUID player, String name, long stake, RandomGenerator rng) {
		Casino.Start s = Casino.startInstant(player, name, WheelGame.GAME, stake, r -> {
			int segment = WheelMath.spin(rng);
			r.card = segment; // landing segment (/smpeco casino peek: card=)
			return WheelMath.payout(r.stake, segment);
		});
		return new Start(s.round(), s.error());
	}
}
