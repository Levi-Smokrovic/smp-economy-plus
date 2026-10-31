package dev.smpeconomy.features.missions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/missions_elite.json}: pool of the Daily Elite, one extremely hard mission per day (server
 * time zone), the same for all players, once per player per day. A separate file so it appears with defaults on first
 * start on servers that already have missions.json. Invalid entries are skipped with a warning (list: {@code /smpeco
 * missions check}). Money in cents.
 */
public final class EliteConfig {
	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
	/** Highest raid level in vanilla ({@code Raid#getMaxRaidOmenLevel}). */
	static final int MAX_RAID_LEVEL = 5;
	/**
	 * Never a hunt target: players (PvP with an alt account), ender dragon (respawnable at will with 4 crystals from
	 * shop ingredients) and mobs players can create at will (golems, snow golems).
	 */
	static final Set<String> FORBIDDEN_TARGETS = Set.of("minecraft:player", "minecraft:ender_dragon", "minecraft:iron_golem",
		"minecraft:snow_golem", "minecraft:copper_golem");

	public enum Type {
		/** Self-killed natural mobs ({@code entities}, {@code amount} in total); projectiles count, pets don't. */
		KILL("kill", "Hunting"),
		/**
		 * Win a raid that began at level {@code minRaidLevel} (level when the first wave spawned), with own
		 * contribution ({@code minRaidKills} / {@code minRaidKillShare}) and while in the village at the win.
		 */
		RAID("raid", "Raid"),
		/** Hand in {@code amount} unmodified {@code item} at once; pays at most price cap × amount. */
		DELIVER("deliver", "Delivery");

		public final String id;
		public final String label;

		Type(String id, String label) {
			this.id = id;
			this.label = label;
		}

		static Optional<Type> byId(String id) {
			if (id == null) return Optional.empty();
			String key = id.trim().toLowerCase(Locale.ROOT);
			for (Type t : values()) {
				if (t.id.equals(key)) return Optional.of(t);
			}
			return Optional.empty();
		}
	}

	public static final class Root {
		/** Off means no offer, no counting and no announcements. */
		public boolean enabled = true;
		/** Changing it gives a different order from the next day on; today stays. */
		public long seed = 0;
		/** Crate keys on top of money, overridable per mission. */
		public String keyTier = CrateKeys.EPIC;
		public int keys = 1;
		/** At midnight, title + chat to all online players; a chat hint on the first join of the day. */
		public boolean announceStart = true;
		/** Announce completion big (title + chat to everyone with the "announcements" setting). */
		public boolean announceComplete = true;
		public List<EliteDef> missions = new ArrayList<>();
	}

	public static final class EliteDef {
		public String id;
		/** "kill", "raid" or "deliver". */
		public String type;
		public String name;
		public String description;
		public String icon;
		public boolean enabled = true;
		/** Kills, won raids or items to hand in. */
		public int amount = 1;
		/** Money on completion in cents; deliveries at most price cap × amount, recomputed at hand-in. */
		public long rewardCents;
		/** kill: mob types ("minecraft:warden"), each counts toward {@code amount}. */
		public List<String> entities;
		/**
		 * raid: minimum raid level (1-5, 5 = Bad Omen V), measured when the first wave spawns, so Bad Omen mid-raid
		 * doesn't raise it. Level 2 and up have the same bonus wave; higher only means more heavily enchanted raiders.
		 */
		public int minRaidLevel = MAX_RAID_LEVEL;
		/**
		 * raid: minimum raiders of this raid the player must have killed personally (Hero of the Village isn't enough).
		 */
		public int minRaidKills = 5;
		/** raid: and at least this share (0-1) of all player raider kills in this raid. */
		public double minRaidKillShare = 0.2;
		/** deliver: item ID (not in the shop, must have a market value). */
		public String item;
		/** Null takes the keys from the root. */
		public String keyTier;
		public Integer keys;

		public EliteDef copy() {
			return DataStore.gson().fromJson(DataStore.gson().toJsonTree(this), EliteDef.class);
		}
	}

	record Elite(EliteDef def, Type type, Set<EntityType<?>> entities, Item item, Item icon) {
		String id() {
			return def.id;
		}

		int amount() {
			return Math.max(1, def.amount);
		}

		boolean countsKill(EntityType<?> t) {
			return type == Type.KILL && entities.contains(t);
		}
	}

	record Compiled(Root root, List<Elite> pool, Map<String, Elite> byId, List<String> problems) {}

	// order matters: compiled without an initializer BEFORE CONFIG (registerResource calls compile() during static
	// init)
	private static Compiled compiled;

	static final ConfigManager.Config<Root> CONFIG = ConfigManager.registerResource("missions_elite", Root.class, "missions_elite.json",
		EliteConfig::compile);

	private EliteConfig() {}

	static void bootstrap() {
		if (compiled == null) compile(CONFIG.get() == null ? new Root() : CONFIG.get());
	}

	static Compiled get() {
		if (compiled == null) bootstrap();
		return compiled;
	}

	static boolean enabled() {
		return get().root().enabled;
	}

	static Elite byId(String id) {
		return id == null ? null : get().byId().get(id);
	}

	/** In file order, which is the basis of the daily selection. */
	static List<Elite> candidates() {
		List<Elite> list = new ArrayList<>();
		for (Elite e : get().pool()) {
			if (e.def().enabled) list.add(e);
		}
		return list;
	}

	static List<String> problems() {
		return get().problems();
	}

	/** Null without candidates. */
	static Elite pick(long epochDay) {
		return pick(epochDay, candidates(), get().root().seed);
	}

	/**
	 * Purely deterministic from (day, pool, salt), the same for all players. The pool gets shuffled in cycles of {@code
	 * n} days: each mission exactly once per cycle, never the same on two consecutive days, also across a cycle
	 * boundary (if a cycle starts with the previous one's last entry, the first two swap).
	 */
	static <T> T pick(long epochDay, List<T> pool, long seed) {
		int n = pool.size();
		if (n == 0) return null;
		if (n == 1) return pool.get(0);
		if (n == 2) return pool.get((int) Math.floorMod(epochDay + (seed & 1), 2));
		long cycle = Math.floorDiv(epochDay, n);
		int index = (int) Math.floorMod(epochDay, n);
		int[] perm = permutation(cycle, n, seed);
		int[] previous = permutation(cycle - 1, n, seed);
		if (perm[0] == previous[n - 1]) {
			int t = perm[0];
			perm[0] = perm[1];
			perm[1] = t;
		}
		return pool.get(perm[index]);
	}

	/** Fisher-Yates with a fixed seed from (cycle, salt). */
	private static int[] permutation(long cycle, int n, long seed) {
		int[] p = new int[n];
		for (int i = 0; i < n; i++) p[i] = i;
		Random r = new Random(mix(mix(0x5DEECE66DL ^ cycle) ^ seed));
		for (int i = n - 1; i > 0; i--) {
			int j = r.nextInt(i + 1);
			int t = p[i];
			p[i] = p[j];
			p[j] = t;
		}
		return p;
	}

	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static String keyTier(EliteDef d) {
		String t = d.keyTier != null ? d.keyTier : get().root().keyTier;
		return t == null ? "" : t;
	}

	static int keys(EliteDef d) {
		if (!CrateKeys.enabled()) return 0; // keys only with the crates module
		int k = d.keys != null ? d.keys : get().root().keys;
		return k > 0 && MissionConfig.validKeyTier(keyTier(d)) ? k : 0;
	}

	private static void compile(Root root) {
		if (root == null) throw new IllegalArgumentException("missions_elite.json is empty");
		List<String> problems = new ArrayList<>();
		if (root.missions == null) root.missions = new ArrayList<>();
		if (root.keys < 0) {
			problems.add("Daily Elite: keys < 0 – using 0");
			root.keys = 0;
		}
		if (root.keys > 0 && !MissionConfig.validKeyTier(root.keyTier)) {
			problems.add("Daily Elite: invalid crate '" + root.keyTier + "' – no keys");
			root.keys = 0;
		}
		List<Elite> pool = new ArrayList<>();
		Map<String, Elite> byId = new LinkedHashMap<>();
		for (EliteDef def : root.missions) {
			if (def == null) continue;
			Elite e;
			try {
				e = compileDef(def.copy());
			} catch (IllegalArgumentException ex) {
				problems.add("Daily Elite '" + def.id + "': " + ex.getMessage());
				continue;
			}
			if (byId.containsKey(e.id())) {
				problems.add("Daily Elite '" + def.id + "': duplicate ID");
				continue;
			}
			pool.add(e);
			byId.put(e.id(), e);
		}
		for (String p : problems) SmpEconomy.LOG.warn("[SMPEco] missions_elite.json: {}", p);
		compiled = new Compiled(root, Collections.unmodifiableList(pool), Collections.unmodifiableMap(byId), List.copyOf(problems));
		SmpEconomy.LOG.info("[SMPEco] {} Daily Elite missions loaded{}", pool.size(), problems.isEmpty() ? "" : " (" + problems.size() + " problems)");
	}

	/** Throws IllegalArgumentException with an English message. */
	static Elite compileDef(EliteDef d) {
		if (d.id == null || !ID.matcher(d.id).matches()) throw new IllegalArgumentException("ID missing/invalid (only a-z, 0-9, _)");
		Type type = Type.byId(d.type).orElseThrow(() -> new IllegalArgumentException("unknown type '" + d.type + "'"));
		if (d.name == null || d.name.isBlank()) throw new IllegalArgumentException("name missing");
		if (d.description == null || d.description.isBlank()) throw new IllegalArgumentException("description missing");
		if (d.amount < 1 || d.amount > MissionConfig.MAX_AMOUNT) throw new IllegalArgumentException("amount outside 1–" + MissionConfig.MAX_AMOUNT);
		if (d.rewardCents <= 0 || d.rewardCents > Money.MAX) throw new IllegalArgumentException("rewardCents must be > 0");
		if (d.keys != null && d.keys < 0) throw new IllegalArgumentException("keys < 0");
		if (d.keys != null && d.keys > 0 && d.keyTier != null && !MissionConfig.validKeyTier(d.keyTier)) {
			throw new IllegalArgumentException("invalid crate '" + d.keyTier + "'");
		}
		Set<EntityType<?>> entities = Set.of();
		Item item = null;
		switch (type) {
			case KILL -> {
				if (d.entities == null || d.entities.isEmpty()) throw new IllegalArgumentException("entities missing");
				Set<EntityType<?>> set = new HashSet<>();
				for (String raw : d.entities) {
					Identifier id = raw == null ? null : Identifier.tryParse(raw.trim());
					if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) throw new IllegalArgumentException("unknown mob '" + raw + "'");
					if (FORBIDDEN_TARGETS.contains(id.toString())) {
						throw new IllegalArgumentException("mob " + id + " is not allowed (can be created at will, or a player)");
					}
					set.add(BuiltInRegistries.ENTITY_TYPE.getValue(id));
				}
				entities = Collections.unmodifiableSet(set);
			}
			case RAID -> {
				if (d.minRaidLevel < 1 || d.minRaidLevel > MAX_RAID_LEVEL) throw new IllegalArgumentException("minRaidLevel outside 1–" + MAX_RAID_LEVEL);
				if (d.minRaidKills < 1 || d.minRaidKills > 1_000) throw new IllegalArgumentException("minRaidKills outside 1–1000");
				if (!(d.minRaidKillShare >= 0 && d.minRaidKillShare <= 1)) throw new IllegalArgumentException("minRaidKillShare outside 0–1");
			}
			case DELIVER -> {
				item = Items.byId(d.item).orElseThrow(() -> new IllegalArgumentException("unknown item '" + d.item + "'"));
				if (BasePrices.shopBuyPrice(item).isPresent()) {
					throw new IllegalArgumentException("item " + d.item + " is sold in the shop – as a Daily Elite delivery the key would be almost free");
				}
			}
		}
		Item icon = d.icon != null ? Items.byId(d.icon).orElse(null) : null;
		if (d.icon != null && icon == null) throw new IllegalArgumentException("unknown icon '" + d.icon + "'");
		if (icon == null) icon = item != null ? item : type == Type.RAID ? net.minecraft.world.item.Items.BELL : net.minecraft.world.item.Items.NETHERITE_SWORD;
		return new Elite(d, type, entities, item, icon);
	}
}
