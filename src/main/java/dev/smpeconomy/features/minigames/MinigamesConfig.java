package dev.smpeconomy.features.minigames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/minigames.json}. Money in cents. Word lists and riddles are separate in {@code
 * chatgames.json}.
 */
public final class MinigamesConfig {
	public ChatGames chatGames = new ChatGames();
	public Coinflip coinflip = new Coinflip();
	public Treasure treasure = new Treasure();
	public dev.smpeconomy.features.minigames.mega.MegaConfig megaChallenge = new dev.smpeconomy.features.minigames.mega.MegaConfig();
	/** Schema version of this file, 0 if the key is missing. Don't edit by hand. */
	public int defaultsVersion = 0;
	transient boolean announceMinBetMigrated;
	/** Not stored in the file: old defaults were migrated on load, so write minigames.json back once. */
	public transient boolean defaultsMigrated;

	public static final int DEFAULTS_VERSION = 1;

	/**
	 * Upgrades a minigames.json from an older schema to {@link #DEFAULTS_VERSION}. No conversions exist so far,
	 * only the version is set. Returns a description of the changes.
	 */
	public static List<String> migrate(MinigamesConfig c) {
		if (c.defaultsVersion < DEFAULTS_VERSION) c.defaultsVersion = DEFAULTS_VERSION;
		return new ArrayList<>();
	}

	public static final class ChatGames {
		public boolean enabled = true;
		public int minIntervalMinutes = 10;
		public int maxIntervalMinutes = 10;
		public int timeoutSeconds = 60;
		public int minPlayers = 1;
		/** Cents per kind, times the chat game multiplier from hooks. */
		public long rewardUnscramble = 15_000;
		public long rewardMath = 10_000;
		public long rewardFastType = 10_000;
		public long rewardRiddle = 20_000;
		public double keyChance = 0.05;
		/**
		 * true: prizes from the treasury (capped, R_REWARD); what it can't pay right now is paid later, see {@link
		 * TreasuryRewards}. false: money source like missions.
		 */
		public boolean rewardsFromTreasury = false;
		public int riddleHintSeconds = 20;
		public Map<String, Integer> weights = defaultWeights();

		private static Map<String, Integer> defaultWeights() {
			Map<String, Integer> m = new LinkedHashMap<>();
			m.put("unscramble", 3);
			m.put("math", 3);
			m.put("fasttype", 2);
			m.put("riddle", 2);
			return m;
		}
	}

	public static final class Coinflip {
		public static final long DEFAULT_ANNOUNCE_MIN_BET = 500_000;

		public long minBet = 10_000;
		public long maxBet = 1_000_000_000L;
		public double feePercent = 2.0;
		public int maxOpenPerPlayer = 3;
		/**
		 * Stake from which new coinflips and results are announced. 0 was an old default that announced every flip,
		 * so it is raised to {@link #DEFAULT_ANNOUNCE_MIN_BET} on load; use 1 to really announce every flip.
		 */
		public long announceMinBet = DEFAULT_ANNOUNCE_MIN_BET;
		/**
		 * At most one "started a coinflip" announcement per player in this many seconds, 0 = no limit. Creating and
		 * cancelling is free, so without a limit it could flood everyone's chat.
		 */
		public int announceCooldownSeconds = 60;
		/** Only when the casino feature registers the service. */
		public boolean offerDoubleOrNothing = true;
	}

	public static final class Treasure {
		public boolean enabled = true;
		public int minIntervalMinutes = 120;
		public int maxIntervalMinutes = 240;
		/** Server time zone: no treasure hunt starts in [from, to). */
		public int nightFromHour = 1;
		public int nightToHour = 8;
		public int minPlayers = 1;
		/** Ring around world spawn in the overworld, pregenerated chunks only. */
		public int minRadius = 300;
		public int maxRadius = 1500;
		public int durationMinutes = 30;
		public int hint2AfterMinutes = 5;
		public int hint3AfterMinutes = 10;
		/** The real position is at most this far per axis from the hinted point. */
		public int coordinateFuzz = 50;
		public long minMoney = 100_000;
		public long maxMoney = 500_000;
		/**
		 * true: money from the treasury (capped, R_REWARD). The amount is capped to the treasury's hourly budget
		 * when hiding ({@code Treasury.maxSpendPerHour()}, about $1,250 at the default reserve), and what it can't
		 * pay at the find is paid later ({@link TreasuryRewards}). false: money source.
		 */
		public boolean rewardsFromTreasury = false;
		public int itemRolls = 2;
		public double commonKeyChance = 0.35;
		public double rareKeyChance = 0.08;
		/** The chunk and its neighbours must be fully generated. */
		public int searchAttempts = 40;
		public List<Loot> loot = defaultLoot();

		private static List<Loot> defaultLoot() {
			List<Loot> l = new ArrayList<>();
			l.add(new Loot("minecraft:diamond", 2, 5, 10));
			l.add(new Loot("minecraft:emerald", 4, 12, 10));
			l.add(new Loot("minecraft:gold_ingot", 8, 20, 12));
			l.add(new Loot("minecraft:iron_ingot", 12, 32, 12));
			l.add(new Loot("minecraft:golden_apple", 1, 3, 8));
			l.add(new Loot("minecraft:ender_pearl", 4, 12, 8));
			l.add(new Loot("minecraft:experience_bottle", 8, 24, 10));
			l.add(new Loot("minecraft:name_tag", 1, 2, 5));
			l.add(new Loot("minecraft:saddle", 1, 1, 4));
			l.add(new Loot("minecraft:netherite_scrap", 1, 2, 3));
			l.add(new Loot("minecraft:enchanted_golden_apple", 1, 1, 1));
			return l;
		}
	}

	public static final class Loot {
		public String item = "minecraft:diamond";
		public int min = 1;
		public int max = 1;
		public int weight = 1;

		public Loot() {}

		public Loot(String item, int min, int max, int weight) {
			this.item = item;
			this.min = min;
			this.max = max;
			this.weight = weight;
		}
	}

	/** Throws on invalid values, so the old config stays active. */
	public static void validate(MinigamesConfig c) {
		if (c.chatGames == null || c.coinflip == null || c.treasure == null) throw new IllegalArgumentException("section missing (chatGames/coinflip/treasure)");
		if (c.megaChallenge == null) c.megaChallenge = new dev.smpeconomy.features.minigames.mega.MegaConfig();
		List<String> migrated = migrate(c);
		if (!migrated.isEmpty()) {
			c.defaultsMigrated = true;
			dev.smpeconomy.SmpEconomy.LOG.warn("[SMPEco] minigames.json: old defaults adjusted once: {}", String.join(", ", migrated));
		}
		dev.smpeconomy.features.minigames.mega.MegaConfig.validate(c.megaChallenge);
		ChatGames g = c.chatGames;
		if (g.minIntervalMinutes < 1 || g.maxIntervalMinutes < g.minIntervalMinutes) throw new IllegalArgumentException("chatGames: invalid interval");
		if (g.timeoutSeconds < 10 || g.timeoutSeconds > 600) throw new IllegalArgumentException("chatGames.timeoutSeconds 10–600");
		if (g.rewardUnscramble < 0 || g.rewardMath < 0 || g.rewardFastType < 0 || g.rewardRiddle < 0) throw new IllegalArgumentException("chatGames: negative reward");
		if (!(g.keyChance >= 0 && g.keyChance <= 1)) throw new IllegalArgumentException("chatGames.keyChance 0–1");
		if (g.riddleHintSeconds < 5) throw new IllegalArgumentException("chatGames.riddleHintSeconds >= 5");
		if (g.weights == null) g.weights = ChatGames.defaultWeights();
		Coinflip f = c.coinflip;
		if (f.minBet < 1 || f.maxBet < f.minBet || f.maxBet > Money.MAX / 2) throw new IllegalArgumentException("coinflip: invalid minBet/maxBet");
		if (!(f.feePercent >= 0 && f.feePercent < 50)) throw new IllegalArgumentException("coinflip.feePercent 0–50");
		if (f.maxOpenPerPlayer < 1) throw new IllegalArgumentException("coinflip.maxOpenPerPlayer >= 1");
		if (f.announceCooldownSeconds < 0 || f.announceCooldownSeconds > 86_400) throw new IllegalArgumentException("coinflip.announceCooldownSeconds 0–86400");
		c.announceMinBetMigrated = f.announceMinBet <= 0;
		if (c.announceMinBetMigrated) f.announceMinBet = Coinflip.DEFAULT_ANNOUNCE_MIN_BET;
		Treasure t = c.treasure;
		if (t.minIntervalMinutes < 5 || t.maxIntervalMinutes < t.minIntervalMinutes) throw new IllegalArgumentException("treasure: invalid interval");
		if (t.minRadius < 0 || t.maxRadius <= t.minRadius) throw new IllegalArgumentException("treasure: invalid radius");
		if (t.durationMinutes < 1) throw new IllegalArgumentException("treasure.durationMinutes >= 1");
		if (t.minMoney < 0 || t.maxMoney < t.minMoney || t.maxMoney > Money.MAX) throw new IllegalArgumentException("treasure: invalid money amount");
		if (!(t.commonKeyChance >= 0 && t.commonKeyChance <= 1 && t.rareKeyChance >= 0 && t.rareKeyChance <= 1)) throw new IllegalArgumentException("treasure: key chance 0-1");
		if (t.coordinateFuzz < 0 || t.searchAttempts < 1 || t.itemRolls < 0) throw new IllegalArgumentException("treasure: invalid values");
		if (t.loot == null) t.loot = new ArrayList<>();
		for (Loot l : t.loot) {
			if (l == null || Items.byId(l.item).isEmpty()) throw new IllegalArgumentException("treasure.loot: unknown item " + (l == null ? null : l.item));
			if (l.min < 1 || l.max < l.min || l.max > 64 * 36 || l.weight < 0) throw new IllegalArgumentException("treasure.loot: invalid amount/weight for " + l.item);
		}
	}
}
