package dev.smpeconomy.features.afk;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/afk.json}. There is no zone without both corners in the same dimension. Without {@code
 * payFromTreasury} the money is newly created, which is why the daily limit is capped by default.
 */
public final class AfkConfig {
	public static final int MIN_INTERVAL_SECONDS = 5;
	public static final int MAX_INTERVAL_SECONDS = 86_400;
	public static final int MAX_HEIGHT_PADDING = 16;
	public static final int MAX_DAY_LIMIT = 100_000;
	/** 24 rewards, or 2 h of AFK at 5 min each. Never ship this unlimited: it is a money source. */
	public static final int DEFAULT_DAY_LIMIT = 24;
	public static final int MAX_ACCOUNTS_PER_IP = 100;
	/** Explains the keys inside afk.json, since JSON has no comments. Never parsed. */
	public static final String NOTE = "maxRewardsPerDay: rewards per player per day, 0 = unlimited (default "
		+ DEFAULT_DAY_LIMIT + "). maxAccountsPerIp: accounts per IP that can collect at the same time, 0 = off (behind a tunnel/proxy"
		+ " everyone has the same IP). payFromTreasury: true = capped money from the Treasury, false = new money. rewardCents in cents.";

	public String note = NOTE;
	/** World border (30M) plus a margin; y well above all vanilla heights. */
	public static final int MAX_HORIZONTAL = 30_000_000;
	public static final int MAX_VERTICAL = 4_096;

	public int intervalSeconds = 300;
	/** Cents per interval; 0 means key chance only. */
	public long rewardCents = 10_000;
	public double keyChancePercent = 5.0;
	public String keyTier = CrateKeys.COMMON;
	/**
	 * Per player per day in the server time zone; 0 = unlimited. Deliberately not the default, since this is a
	 * money source.
	 */
	public int maxRewardsPerDay = DEFAULT_DAY_LIMIT;
	/**
	 * Accounts per IP whose timer runs at once (against alts); 0 = off. Off by default because behind a tunnel or
	 * proxy such as playit.gg all players share one IP.
	 */
	public int maxAccountsPerIp = 0;
	/** true: capped money from the treasury ({@code Treasury.payTo}); false: new money, like daily and missions. */
	public boolean payFromTreasury = false;
	/** Extra blocks above the top corner so jumping or slabs don't count as leaving. */
	public int heightPadding = 2;
	public Corner pos1;
	public Corner pos2;

	public static final class Corner {
		public String dimension = "minecraft:overworld";
		public int x;
		public int y;
		public int z;

		public Corner() {}

		public Corner(String dimension, int x, int y, int z) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public String toString() {
			return dimension + "," + x + "," + y + "," + z;
		}
	}

	/** Null if a corner is missing or the corners are in different dimensions. */
	public AfkZone zone() {
		if (pos1 == null || pos2 == null) return null;
		if (pos1.dimension == null || pos2.dimension == null) return null;
		String dim = normalizeDimension(pos1.dimension); // "the_nether" and "minecraft:the_nether" are the same dimension
		if (dim == null || !dim.equals(normalizeDimension(pos2.dimension))) return null;
		return AfkZone.of(dim, pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z);
	}

	/** Throws on invalid values, so ConfigManager keeps the previous state. */
	public static void validate(AfkConfig c) {
		c.note = NOTE; // always the current text, written on the next save
		if (c.intervalSeconds < MIN_INTERVAL_SECONDS || c.intervalSeconds > MAX_INTERVAL_SECONDS)
			throw new IllegalArgumentException("intervalSeconds must be " + MIN_INTERVAL_SECONDS + ".." + MAX_INTERVAL_SECONDS);
		if (c.rewardCents < 0 || c.rewardCents > Money.MAX) throw new IllegalArgumentException("rewardCents must be 0.." + Money.MAX);
		if (!Double.isFinite(c.keyChancePercent) || c.keyChancePercent < 0 || c.keyChancePercent > 100)
			throw new IllegalArgumentException("keyChancePercent must be 0..100");
		if (c.keyTier == null || !CrateKeys.TIERS.contains(c.keyTier))
			throw new IllegalArgumentException("keyTier must be one of " + CrateKeys.TIERS);
		if (c.maxRewardsPerDay < 0 || c.maxRewardsPerDay > MAX_DAY_LIMIT)
			throw new IllegalArgumentException("maxRewardsPerDay must be 0.." + MAX_DAY_LIMIT);
		if (c.maxAccountsPerIp < 0 || c.maxAccountsPerIp > MAX_ACCOUNTS_PER_IP)
			throw new IllegalArgumentException("maxAccountsPerIp must be 0.." + MAX_ACCOUNTS_PER_IP);
		if (c.heightPadding < 0 || c.heightPadding > MAX_HEIGHT_PADDING)
			throw new IllegalArgumentException("heightPadding must be 0.." + MAX_HEIGHT_PADDING);
		validateCorner(c.pos1, "pos1");
		validateCorner(c.pos2, "pos2");
	}

	private static void validateCorner(Corner corner, String name) {
		if (corner == null) return;
		if (corner.dimension == null || Identifier.tryParse(corner.dimension) == null)
			throw new IllegalArgumentException(name + ".dimension invalid: " + corner.dimension);
		if (!validCoordinates(corner.x, corner.y, corner.z)) throw new IllegalArgumentException(name + ": coordinates outside the world");
	}

	public static boolean validCoordinates(int x, int y, int z) {
		return Math.abs((long) x) <= MAX_HORIZONTAL && Math.abs((long) z) <= MAX_HORIZONTAL && Math.abs((long) y) <= MAX_VERTICAL;
	}

	enum FileState { MISSING, SAME, DIFFERENT, BROKEN, UNREADABLE }

	record FileCheck(FileState state, String detail) {
		/** Only a missing file or one matching the active state may be overwritten. */
		boolean writable() {
			return state == FileState.MISSING || state == FileState.SAME;
		}
	}

	/**
	 * Checks {@code file} the way ConfigManager loads it and compares it with {@code active}. With a broken file
	 * the code defaults are running (or the old state after a reload); a hand-edited file that wasn't reloaded
	 * differs from the active state. In both cases an admin command saving the in-memory state would overwrite the
	 * file and could delete the zone.
	 */
	static FileCheck checkFile(Path file, AfkConfig active) {
		if (Files.notExists(file)) return new FileCheck(FileState.MISSING, "");
		AfkConfig onDisk;
		try {
			onDisk = DataStore.gson().fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)), AfkConfig.class);
			if (onDisk == null) throw new IllegalArgumentException("file is empty");
			validate(onDisk);
		} catch (IOException e) {
			return new FileCheck(FileState.UNREADABLE, shortMessage(e));
		} catch (RuntimeException e) {
			return new FileCheck(FileState.BROKEN, shortMessage(e));
		}
		boolean same = DataStore.gson().toJson(onDisk).equals(DataStore.gson().toJson(active));
		return new FileCheck(same ? FileState.SAME : FileState.DIFFERENT, "");
	}

	/** First line of the error, shortened because Gson appends links. */
	private static String shortMessage(Exception e) {
		String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
		int nl = msg.indexOf('\n');
		if (nl >= 0) msg = msg.substring(0, nl).strip();
		return msg.length() > 160 ? msg.substring(0, 157) + "..." : msg;
	}

	public static String normalizeDimension(String dimension) {
		Identifier id = dimension == null ? null : Identifier.tryParse(dimension);
		return id == null ? dimension : id.toString();
	}
}
