package dev.smpeconomy.features.missions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Items;

/**
 * {@code config/smpeconomy/missions.json}. Invalid missions are skipped with a warning (like prices.json) so a typo
 * doesn't take the whole board down; {@code /smpeco missions check} lists the problems. Money in cents.
 */
public final class MissionConfig {
	/** Divisors of 24, so rounds always change at the same time of day. */
	static final Set<Integer> ROTATION_HOURS = Set.of(1, 2, 3, 4, 6, 8, 12, 24);
	/** One row in the active menu. */
	static final int MAX_ACTIVE_LIMIT = 7;
	/** One board row. */
	static final int MAX_OFFERS = 7;
	static final int MAX_AMOUNT = 1_000_000;
	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");

	public static final class Root {
		public int maxActive = 3;
		/** Server time zone, from midnight; must divide 24. */
		public int rotationHours = 6;
		/** After abandoning a mission, no new one for this long. */
		public int cancelCooldownMinutes = 30;
		/** Changing it rerolls all boards. */
		public long rotationSeed = 0;
		/** Only to players with the "announcements" setting. */
		public boolean announceElite = true;
		public boolean progressActionbar = true;
		/**
		 * Hunt missions: how much a mob from a spawner or trial spawner counts (0-1, like {@code spawnerMobFactor} for
		 * jobs; 0.25 = four spawner mobs for one kill). Bred, summoned, spawn egg and respawned mobs never count.
		 */
		public double spawnerKillFactor = 0.25;
		public Map<String, TierSettings> tiers = new LinkedHashMap<>();
		public List<MissionDef> missions = new ArrayList<>();
	}

	/** Missing fields take code defaults from {@link Tier}. */
	public static final class TierSettings {
		public String label;
		public String color;
		public String icon;
		public Integer offers;
		public Integer timeLimitMinutes;
		/** Crate key on completion: crate ("rare", "epic", ..., empty = none) and count. */
		public String keyTier;
		public Integer keys;
	}

	/** Type-specific fields: see {@link Mission.Type}. */
	public static final class MissionDef {
		public String id;
		public String tier;
		/** "deliver", "stat", "treasure" or "explore". */
		public String type;
		public String name;
		public String description;
		public String icon;
		public boolean enabled = true;
		/** Selection weight within the tier (> 0). */
		public double weight = 1.0;
		/** Items, kills, blocks, treasures; exploration is always 1. */
		public int amount = 1;
		/** Money on completion in cents. Not for deliveries, which pay per piece. */
		public long rewardCents;
		/**
		 * Deliveries: desired price per piece in cents (0 = price cap), always capped by PriceGuard.maxNpcOrderPrice.
		 */
		public long rewardPerItemCents;
		/** 0 takes the tier's time limit. */
		public int timeLimitMinutes;
		/** Null takes the tier's keys. Deliveries never give keys (keys > 0 on "deliver" is an error). */
		public String keyTier;
		public Integer keys;
		public String item;
		/**
		 * stat: vanilla stats "type:object" (e.g. "killed:skeleton", "mined:diamond_ore", "custom:fish_caught"),
		 * summed; "mined:" doesn't count through the stat but through individually self-mined natural blocks, which is
		 * Veinminer-safe.
		 */
		public List<String> stats;
		/** explore: dimension, biome ("#tag" allowed) and/or structure ("#tag" allowed); all given must match. */
		public String dimension;
		public String biome;
		public String structure;

		/** Deep copy: snapshot for active missions, independent of later reloads. */
		public MissionDef copy() {
			return DataStore.gson().fromJson(DataStore.gson().toJsonTree(this), MissionDef.class);
		}
	}

	record Compiled(Root root, Map<Tier, TierSettings> tiers, List<Mission> pool, Map<String, Mission> byId, List<String> problems) {}

	// order matters: COMPILED without an initializer BEFORE CONFIG (registerResource calls compile() during static
	// init)
	private static Compiled compiled;
	private static Set<String> unavailable = Set.of();
	private static final List<String> RUNTIME_PROBLEMS = new ArrayList<>();

	static final ConfigManager.Config<Root> CONFIG = ConfigManager.registerResource("missions", Root.class, "missions.json", MissionConfig::compile);

	private MissionConfig() {}

	static void bootstrap() {
		if (compiled == null) compile(CONFIG.get() == null ? new Root() : CONFIG.get());
	}

	static Compiled get() {
		if (compiled == null) bootstrap();
		return compiled;
	}

	static int maxActive() {
		return get().root().maxActive;
	}

	static int rotationHours() {
		return get().root().rotationHours;
	}

	static double spawnerKillFactor() {
		return get().root().spawnerKillFactor;
	}

	static long cancelCooldownMs() {
		return get().root().cancelCooldownMinutes * 60_000L;
	}

	static TierSettings tier(Tier tier) {
		return get().tiers().get(tier);
	}

	static Mission byId(String id) {
		return id == null ? null : get().byId().get(id);
	}

	/** Enabled, valid, and the biome, structure or dimension exists. */
	static List<Mission> candidates(Tier tier) {
		List<Mission> list = new ArrayList<>();
		for (Mission m : get().pool()) {
			if (m.tier == tier && m.def.enabled && !unavailable.contains(m.id)) list.add(m);
		}
		return list;
	}

	static List<String> problems() {
		List<String> all = new ArrayList<>(get().problems());
		all.addAll(RUNTIME_PROBLEMS);
		return all;
	}

	/**
	 * Checks biomes, structures and dimensions against the running server's registries, which only exist after start.
	 * Missions with unknown targets don't get offered.
	 */
	static void checkAgainstServer(MinecraftServer server) {
		RUNTIME_PROBLEMS.clear();
		Set<String> missing = new HashSet<>();
		if (server != null) {
			for (Mission m : get().pool()) {
				String problem = m.checkTargets(server);
				if (problem != null) {
					missing.add(m.id);
					RUNTIME_PROBLEMS.add("Mission '" + m.id + "': " + problem);
					SmpEconomy.LOG.warn("[SMPEco] missions.json: mission '{}': {}, not offered", m.id, problem);
				}
			}
		}
		unavailable = Collections.unmodifiableSet(missing);
	}

	private static void compile(Root root) {
		if (root == null) throw new IllegalArgumentException("missions.json is empty");
		List<String> problems = new ArrayList<>();
		if (root.maxActive < 1 || root.maxActive > MAX_ACTIVE_LIMIT) {
			problems.add("maxActive " + root.maxActive + " invalid (1–" + MAX_ACTIVE_LIMIT + ") – using 3");
			root.maxActive = 3;
		}
		if (!ROTATION_HOURS.contains(root.rotationHours)) {
			problems.add("rotationHours " + root.rotationHours + " invalid (must divide 24) – using 6");
			root.rotationHours = 6;
		}
		if (root.cancelCooldownMinutes < 0) {
			problems.add("cancelCooldownMinutes < 0 – using 0");
			root.cancelCooldownMinutes = 0;
		}
		if (!(root.spawnerKillFactor >= 0 && root.spawnerKillFactor <= 1)) {
			problems.add("spawnerKillFactor " + root.spawnerKillFactor + " invalid (0–1) – using 0.25");
			root.spawnerKillFactor = 0.25;
		}
		if (root.tiers == null) root.tiers = new LinkedHashMap<>();
		if (root.missions == null) root.missions = new ArrayList<>();

		Map<Tier, TierSettings> tiers = new EnumMap<>(Tier.class);
		for (Tier t : Tier.values()) tiers.put(t, resolveTier(t, root.tiers.get(t.id), problems));
		for (String key : root.tiers.keySet()) {
			if (Tier.byId(key).isEmpty()) problems.add("Unknown tier in \"tiers\": " + key);
		}

		List<Mission> pool = new ArrayList<>();
		Map<String, Mission> byId = new LinkedHashMap<>();
		for (MissionDef def : root.missions) {
			if (def == null) continue;
			String error = validate(def);
			Mission m = null;
			if (error == null) {
				try {
					m = Mission.compile(def.copy());
				} catch (IllegalArgumentException e) {
					error = e.getMessage();
				}
			}
			if (error == null && byId.containsKey(def.id)) error = "duplicate ID";
			if (error != null) {
				problems.add("Mission '" + def.id + "': " + error);
				continue;
			}
			pool.add(m);
			byId.put(m.id, m);
		}
		for (String p : problems) SmpEconomy.LOG.warn("[SMPEco] missions.json: {}", p);
		compiled = new Compiled(root, Collections.unmodifiableMap(tiers), Collections.unmodifiableList(pool), Collections.unmodifiableMap(byId),
			List.copyOf(problems));
		SmpEconomy.LOG.info("[SMPEco] {} missions loaded{}", pool.size(), problems.isEmpty() ? "" : " (" + problems.size() + " problems)");
		MinecraftServer server = SmpEconomy.server();
		if (server != null) checkAgainstServer(server);
	}

	private static TierSettings resolveTier(Tier t, TierSettings raw, List<String> problems) {
		TierSettings s = new TierSettings();
		s.label = raw != null && raw.label != null && !raw.label.isBlank() ? raw.label : t.defaultLabel;
		s.color = raw != null && raw.color != null ? raw.color : t.defaultColor;
		s.icon = raw != null && raw.icon != null && Items.byId(raw.icon).isPresent() ? raw.icon : t.defaultIcon;
		if (raw != null && raw.icon != null && Items.byId(raw.icon).isEmpty()) problems.add("Tier " + t.id + ": unknown icon " + raw.icon);
		int offers = raw != null && raw.offers != null ? raw.offers : t.defaultOffers;
		if (offers < 0 || offers > MAX_OFFERS) {
			problems.add("Tier " + t.id + ": offers " + offers + " invalid (0–" + MAX_OFFERS + ")");
			offers = Math.max(0, Math.min(MAX_OFFERS, offers));
		}
		s.offers = offers;
		int limit = raw != null && raw.timeLimitMinutes != null ? raw.timeLimitMinutes : t.defaultTimeLimitMinutes;
		if (limit < 1) {
			problems.add("Tier " + t.id + ": timeLimitMinutes < 1");
			limit = t.defaultTimeLimitMinutes;
		}
		s.timeLimitMinutes = limit;
		String keyTier = raw != null && raw.keyTier != null ? raw.keyTier : t.defaultKeyTier;
		int keys = raw != null && raw.keys != null ? raw.keys : t.defaultKeys;
		if (keys < 0) keys = 0;
		if (keys > 0 && !validKeyTier(keyTier)) {
			problems.add("Tier " + t.id + ": invalid crate '" + keyTier + "' – no keys");
			keys = 0;
		}
		s.keyTier = keys > 0 ? keyTier : "";
		s.keys = keys;
		return s;
	}

	static boolean validKeyTier(String keyTier) {
		return keyTier != null && CrateKeys.TIERS.contains(keyTier);
	}

	/** General fields; {@link Mission#compile} checks type-specific ones. Null if ok. */
	static String validate(MissionDef d) {
		if (d.id == null || !ID.matcher(d.id).matches()) return "ID missing/invalid (only a-z, 0-9, _)";
		if (Tier.byId(d.tier).isEmpty()) return "unknown tier '" + d.tier + "'";
		if (Mission.Type.byId(d.type).isEmpty()) return "unknown type '" + d.type + "'";
		if (d.name == null || d.name.isBlank()) return "name missing";
		if (d.description == null || d.description.isBlank()) return "description missing";
		if (d.amount < 1 || d.amount > MAX_AMOUNT) return "amount outside 1–" + MAX_AMOUNT;
		if (!(d.weight > 0) || Double.isInfinite(d.weight)) return "weight must be > 0";
		if (d.rewardCents < 0 || d.rewardCents > dev.smpeconomy.core.Money.MAX) return "rewardCents invalid";
		if (d.rewardPerItemCents < 0 || d.rewardPerItemCents > dev.smpeconomy.core.Money.MAX) return "rewardPerItemCents invalid";
		if (d.timeLimitMinutes < 0) return "timeLimitMinutes < 0";
		if (d.keys != null && d.keys < 0) return "keys < 0";
		if (d.keys != null && d.keys > 0 && d.keyTier != null && !validKeyTier(d.keyTier)) return "invalid crate '" + d.keyTier + "'";
		if (d.keys != null && d.keys > 0 && Mission.Type.byId(d.type).orElse(null) == Mission.Type.DELIVER) {
			return "deliveries never give keys (the items can be bought from the server – the key would be almost free)";
		}
		if (d.icon != null && Items.byId(d.icon).isEmpty()) return "unknown icon '" + d.icon + "'";
		return null;
	}
}
