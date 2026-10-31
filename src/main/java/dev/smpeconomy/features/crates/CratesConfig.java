package dev.smpeconomy.features.crates;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/crates.json}. A crate ID is also its key type in {@link CrateKeys}, and {@code
 * common}, {@code rare} and {@code epic} must exist. Money is in cents.
 *
 * <p>Invalid files are rejected whole (a reload keeps the old state, startup uses the defaults), because a
 * silently skipped entry would shift all reward chances unnoticed.
 */
public final class CratesConfig {
	static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
	static final int MAX_CRATES = 7;
	static final int MAX_REWARDS = 200;
	static final int MAX_WEIGHT = 1_000_000;
	/** 36 stacks of 64. */
	static final int MAX_ITEM_COUNT = 36 * 64;
	static final int MAX_KEY_COUNT = 64;

	/** Free-form hint in the file, ignored. */
	public String _info;
	public List<CrateDef> crates = new ArrayList<>();

	public static final class CrateDef {
		/** Also the key type ({@code CrateKeys.key(id)}). */
		public String id;
		/** Display name with color codes. */
		public String name;
		public String icon = "minecraft:chest";
		public List<RewardDef> rewards = new ArrayList<>();

		/** Above 0 in a valid config. */
		public long totalWeight() {
			long sum = 0;
			for (RewardDef r : rewards) sum += Math.max(0, r.weight);
			return sum;
		}

		public Optional<RewardDef> reward(String rewardId) {
			for (RewardDef r : rewards) {
				if (r.id != null && r.id.equals(rewardId)) return Optional.of(r);
			}
			return Optional.empty();
		}

		public Item iconItem() {
			return Items.byId(icon).orElse(net.minecraft.world.item.Items.CHEST);
		}
	}

	/** Exactly one of {@code money}, {@code item} or {@code key}. */
	public static final class RewardDef {
		public String id;
		/** 0 disables it. Chance is weight / sum of the crate's weights. */
		public int weight;
		/** In cents. */
		public long money;
		public String item;
		/** Items or keys, default 1. */
		public int count = 1;
		/** ID to level; stored enchantments on books. */
		public Map<String, Integer> enchantments;
		/** Optional, with color codes. */
		public String name;
		/** Key of another crate as the reward. */
		public String key;
		/** Announces the win in chat to players with the "announcements" setting. */
		public boolean announce;

		public Kind kind() {
			if (money > 0) return Kind.MONEY;
			if (key != null) return Kind.KEY;
			return Kind.ITEM;
		}

		public int effectiveCount() {
			return Math.max(1, count);
		}
	}

	public enum Kind { MONEY, ITEM, KEY }

	static final ConfigManager.Config<CratesConfig> CONFIG =
		ConfigManager.registerResource("crates", CratesConfig.class, "crates.json", CratesConfig::onLoad);

	/** Problem of the active config with the running server's registries; crates are locked while set. */
	private static String registryProblem;

	/** Loads the class, and with it the config. */
	static void bootstrap() {
	}

	static CratesConfig get() {
		return CONFIG.get();
	}

	/** Case-insensitive. */
	static Optional<CrateDef> crate(String id) {
		String norm = normalize(id);
		for (CrateDef c : get().crates) {
			if (c.id.equals(norm)) return Optional.of(c);
		}
		return Optional.empty();
	}

	static List<String> crateIds() {
		List<String> ids = new ArrayList<>();
		for (CrateDef c : get().crates) ids.add(c.id);
		return ids;
	}

		static List<String> crateNames() {
		List<String> names = new ArrayList<>();
		for (CrateDef c : get().crates) names.add(c.id);
		return names;
	}

		static String displayName(String crateId) {
		return crate(crateId).map(c -> c.name).orElse("§f" + CrateKeys.label(crateId));
	}

	static String normalize(String id) {
		if (id == null) return "";
		String s = id.trim().toLowerCase(java.util.Locale.ROOT);
		return s;
	}

	static String problem() {
		return registryProblem;
	}

	/** Run at SERVER_STARTED: registries do not exist yet at mod init. */
	static void verifyWithServer(HolderLookup.Provider registries) {
		try {
			validate(get(), registries);
			registryProblem = null;
		} catch (RuntimeException e) {
			registryProblem = e.getMessage();
			SmpEconomy.LOG.error("[SMPEco] crates.json does not match the registries, crates locked: {}", e.getMessage());
		}
	}

	private static void onLoad(CratesConfig cfg) {
		var server = SmpEconomy.server();
		validate(cfg, server == null ? null : server.registryAccess());
		if (server != null) registryProblem = null;
	}

	/**
	 * Throws {@link IllegalArgumentException} describing the first error. Null {@code registries} skips the
	 * enchantment check, for mod init.
	 */
	static void validate(CratesConfig cfg, HolderLookup.Provider registries) {
		if (cfg == null || cfg.crates == null || cfg.crates.isEmpty()) throw new IllegalArgumentException("'crates' is missing or empty");
		if (cfg.crates.size() > MAX_CRATES) throw new IllegalArgumentException("at most " + MAX_CRATES + " crates");
		Set<String> crateIds = new HashSet<>();
		for (CrateDef c : cfg.crates) {
			if (c == null) throw new IllegalArgumentException("empty crate entry");
			if (c.id == null || !ID.matcher(c.id).matches()) throw new IllegalArgumentException("invalid crate ID '" + c.id + "' (only a-z, 0-9, _)");
			if (!crateIds.add(c.id)) throw new IllegalArgumentException("duplicate crate '" + c.id + "'");
		}
		for (String tier : CrateKeys.TIERS) {
			if (!crateIds.contains(tier)) throw new IllegalArgumentException("default crate '" + tier + "' is missing");
		}
		for (CrateDef c : cfg.crates) {
			String where = "crate '" + c.id + "'";
			if (c.name == null || c.name.isBlank()) throw new IllegalArgumentException(where + ": 'name' is missing");
			if (c.icon != null && Items.byId(c.icon).isEmpty()) throw new IllegalArgumentException(where + ": unknown icon '" + c.icon + "'");
			if (c.rewards == null || c.rewards.isEmpty()) throw new IllegalArgumentException(where + ": no rewards");
			if (c.rewards.size() > MAX_REWARDS) throw new IllegalArgumentException(where + ": at most " + MAX_REWARDS + " rewards");
			Set<String> rewardIds = new HashSet<>();
			for (RewardDef r : c.rewards) {
				if (r == null) throw new IllegalArgumentException(where + ": empty reward entry");
				if (r.id == null || !ID.matcher(r.id).matches()) throw new IllegalArgumentException(where + ": invalid reward ID '" + r.id + "'");
				if (!rewardIds.add(r.id)) throw new IllegalArgumentException(where + ": duplicate reward '" + r.id + "'");
				validateReward(r, where + "/" + r.id, crateIds, registries);
			}
			if (c.totalWeight() <= 0) throw new IllegalArgumentException(where + ": total weight is 0");
		}
	}

	static void validateReward(RewardDef r, String where, Set<String> crateIds, HolderLookup.Provider registries) {
		if (r.weight < 0 || r.weight > MAX_WEIGHT) throw new IllegalArgumentException(where + ": weight must be 0.." + MAX_WEIGHT);
		if (r.money < 0) throw new IllegalArgumentException(where + ": negative money amount");
		int kinds = (r.money > 0 ? 1 : 0) + (r.item != null ? 1 : 0) + (r.key != null ? 1 : 0);
		if (kinds != 1) throw new IllegalArgumentException(where + ": set exactly one of 'money', 'item' or 'key'");
		boolean itemOnly = (r.enchantments != null && !r.enchantments.isEmpty()) || r.name != null;
		switch (r.kind()) {
			case MONEY -> {
				if (!Money.isValidAmount(r.money)) throw new IllegalArgumentException(where + ": invalid money amount");
				if (itemOnly) throw new IllegalArgumentException(where + ": 'enchantments'/'name' only for items");
			}
			case KEY -> {
				if (!crateIds.contains(r.key)) throw new IllegalArgumentException(where + ": unknown crate '" + r.key + "' as key");
				if (r.count < 1 || r.count > MAX_KEY_COUNT) throw new IllegalArgumentException(where + ": key count must be 1.." + MAX_KEY_COUNT);
				if (itemOnly) throw new IllegalArgumentException(where + ": 'enchantments'/'name' only for items");
			}
			case ITEM -> {
				if (Items.byId(r.item).isEmpty()) throw new IllegalArgumentException(where + ": unknown item '" + r.item + "'");
				if (r.count < 1 || r.count > MAX_ITEM_COUNT) throw new IllegalArgumentException(where + ": count must be 1.." + MAX_ITEM_COUNT);
				if (r.name != null && (r.name.isBlank() || r.name.length() > 64)) throw new IllegalArgumentException(where + ": 'name' is empty or too long");
				if (r.enchantments != null) {
					for (Map.Entry<String, Integer> e : r.enchantments.entrySet()) {
						Integer level = e.getValue();
						if (level == null || level < 1 || level > 255) throw new IllegalArgumentException(where + ": level of '" + e.getKey() + "' must be 1..255");
						if (net.minecraft.resources.Identifier.tryParse(e.getKey()) == null) throw new IllegalArgumentException(where + ": invalid enchantment '" + e.getKey() + "'");
					}
				}
				// Builds the whole item, which catches unknown or ill-fitting enchantments
				if (registries != null) CrateRewards.buildItem(r, registries);
			}
		}
	}

	static CrateDef testCrate(String id, List<RewardDef> rewards) {
		CrateDef c = new CrateDef();
		c.id = id;
		c.name = "§fTest Crate";
		c.rewards = new ArrayList<>(rewards);
		return c;
	}

	static RewardDef money(String id, int weight, long cents) {
		RewardDef r = new RewardDef();
		r.id = id;
		r.weight = weight;
		r.money = cents;
		return r;
	}

	static RewardDef item(String id, int weight, String item, int count) {
		RewardDef r = new RewardDef();
		r.id = id;
		r.weight = weight;
		r.item = item;
		r.count = count;
		return r;
	}

	static RewardDef book(String id, int weight, String enchantment, int level) {
		RewardDef r = item(id, weight, "minecraft:enchanted_book", 1);
		r.enchantments = new LinkedHashMap<>();
		r.enchantments.put(enchantment, level);
		return r;
	}

	static RewardDef key(String id, int weight, String crate, int count) {
		RewardDef r = new RewardDef();
		r.id = id;
		r.weight = weight;
		r.key = crate;
		r.count = count;
		return r;
	}
}
