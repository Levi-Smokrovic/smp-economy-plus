package dev.smpeconomy.features.casino.games.crash;

import java.math.BigInteger;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Pure crash math (no server state). Multipliers are whole hundredths ({@code 100} = ×1.00, {@code 250} = ×2.50).
 *
 * <p>Crash point: with {@code k} uniform in {@code [0, 2^48)} the round crashes at
 * {@code floor((10000 − edgeBp) · 2^48 / (100 · (k + 1)))} hundredths, clamped to {@code [×1.00, cap]}. Hence
 * {@code P(crash point ≥ a) = (1 − edge) / a} (up to rounding) and every cash-out target {@code a} returns
 * {@code a · P(crash point ≥ a) = 1 − edge} on average, the house edge does not depend on the player's strategy. A crash
 * point at the cap means the rocket reaches the cap and is paid out there.
 *
 * <p>Timeline: the multiplier doubles every {@code doublingSeconds} ({@link #multiplierAt}). The round crashes as soon as
 * the multiplier EXCEEDS the crash point, so every multiplier that is shown while the rocket flies can still be cashed
 * out ({@link #phase}).
 */
final class CrashMath {
	/** ×1.00 in hundredths. */
	static final long ONE = 100;
	private static final int BITS = 48;
	private static final long SPAN = 1L << BITS;

	/** Auto cash-out presets (hundredths), 0 = off. Only those ≤ cap are offered. */
	static final long[] AUTO_PRESETS = {0, 110, 125, 150, 200, 300, 500, 1_000, 2_000, 5_000, 10_000};

	private CrashMath() {}

	enum Phase {
		/** Still flying; the shown multiplier can be cashed out. */
		RUNNING,
		/** Auto cash-out (or the cap) reached; paid at the target. */
		CASHED,
		/** The multiplier went past the crash point; bet lost. */
		CRASHED
	}

	/** Draws a crash point (hundredths, {@code [100, capH]}). */
	static long crashPoint(RandomGenerator rng, int edgeBp, long capH) {
		return crashPointFor(rng.nextLong() >>> (64 - BITS), edgeBp, capH);
	}

	/** Crash point for the raw draw {@code k} in {@code [0, 2^48)}. */
	static long crashPointFor(long k, int edgeBp, long capH) {
		long c = numerator(edgeBp) / (100L * (k + 1));
		return Math.max(ONE, Math.min(capH, c));
	}

	private static long numerator(int edgeBp) {
		// (10000 − edgeBp) · 2^48 ≤ 2.82e18 < Long.MAX_VALUE
		return (10_000L - edgeBp) << BITS;
	}

	/** Exact {@code P(crash point ≥ aH)} for this distribution. */
	static double survival(long aH, int edgeBp, long capH) {
		if (aH <= ONE) return 1.0;
		if (aH > capH) return 0.0;
		long count = numerator(edgeBp) / (100L * aH); // k + 1 ≤ count
		return Math.min(SPAN, count) / (double) SPAN;
	}

	/** Exact return to player when cashing out at {@code aH} (auto cash-out, or any shown multiplier). */
	static double rtp(long aH, int edgeBp, long capH) {
		return aH / (double) ONE * survival(aH, edgeBp, capH);
	}

	/** Multiplier (hundredths) after {@code ticks} server ticks: {@code floor(100 · 2^(ticks / (20 · doublingSeconds)))}. */
	static long multiplierAt(long ticks, double doublingSeconds) {
		if (ticks <= 0) return ONE;
		double v = 100.0 * StrictMath.pow(2.0, ticks / (20.0 * doublingSeconds));
		if (!(v < 1e12)) return 1_000_000_000_000L;
		return Math.max(ONE, (long) Math.floor(v));
	}

	/** Same curve as a double (×), for drawing. */
	static double curve(double ticks, double doublingSeconds) {
		return ticks <= 0 ? 1.0 : StrictMath.pow(2.0, ticks / (20.0 * doublingSeconds));
	}

	/** Ticks until the multiplier reaches {@code aH} (for display). */
	static long ticksUntil(long aH, double doublingSeconds) {
		if (aH <= ONE) return 0;
		return (long) Math.ceil(20.0 * doublingSeconds * StrictMath.log(aH / 100.0) / StrictMath.log(2.0));
	}

	/**
	 * Where the round stands at multiplier {@code multH}. {@code targetH} = auto cash-out (or the cap): reached and not
	 * beyond the crash point → paid at the target, even if the multiplier jumped past both in one tick. Otherwise the
	 * round crashes once the multiplier is greater than the crash point.
	 */
	static Phase phase(long crashH, long targetH, long multH) {
		if (multH >= targetH && targetH <= crashH) return Phase.CASHED;
		if (multH > crashH) return Phase.CRASHED;
		return Phase.RUNNING;
	}

	/** Effective target: auto cash-out (if set) but never above the cap. */
	static long target(long autoH, long capH) {
		return autoH > 0 ? Math.min(autoH, capH) : capH;
	}

	/** {@code floor(stake · multH / 100)} without overflow. */
	static long payout(long stake, long multH) {
		if (stake <= 0 || multH <= 0) return 0;
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(multH)).divide(BigInteger.valueOf(ONE)).longValueExact();
	}

	/** Largest stake whose payout at {@code targetH} stays ≤ {@code cover}. */
	static long maxStakeFor(long cover, long targetH) {
		if (cover <= 0 || targetH <= 0) return 0;
		BigInteger s = BigInteger.valueOf(cover).multiply(BigInteger.valueOf(ONE)).divide(BigInteger.valueOf(targetH));
		return s.bitLength() >= 63 ? Long.MAX_VALUE : s.longValue();
	}

	/** {@code "×2.50"}. */
	static String format(long multH) {
		return "×" + multH / 100 + "." + String.format(Locale.ROOT, "%02d", Math.abs(multH % 100));
	}

	/**
	 * Parses a multiplier such as {@code 2}, {@code 2.5}, {@code 2.50}, {@code x2.5}, {@code 2.5x}, {@code ×2.5} to hundredths;
	 * -1 if invalid (more than two decimals, not a number, absurdly large).
	 */
	static long parseMultiplier(String raw) {
		if (raw == null) return -1;
		String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", ".");
		if (s.startsWith("x") || s.startsWith("×")) s = s.substring(1);
		if (s.endsWith("x") || s.endsWith("×")) s = s.substring(0, s.length() - 1);
		if (!s.matches("\\d{1,7}(\\.\\d{1,2})?")) return -1;
		int dot = s.indexOf('.');
		long whole = Long.parseLong(dot < 0 ? s : s.substring(0, dot));
		long frac = 0;
		if (dot >= 0) {
			String f = s.substring(dot + 1);
			frac = Long.parseLong(f.length() == 1 ? f + "0" : f);
		}
		return whole * 100 + frac;
	}

	/** {@code 5} or {@code 5.5}. */
	static String num(double v) {
		return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
	}

	/** Percentage with one decimal, e.g. {@code "48.0 %"}. */
	static String percent(double p) {
		return String.format(Locale.ROOT, "%.1f %%", p * 100);
	}
}
