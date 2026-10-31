package dev.smpeconomy.features.missions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MobOrigin;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.missions.MissionStore.ActiveMission;
import dev.smpeconomy.features.missions.MissionStore.PlayerState;

/**
 * All on the server thread, with time as a parameter so self tests can drive it.
 *
 * <p>Exploit limits: delivery rewards never exceed {@link PriceGuard#maxNpcOrderPrice}, recomputed on every delivery, and
 * give no crate keys, because the items can be bought from the system and handed back at the price cap. Mining missions
 * count only individually self-mined natural blocks, never the vanilla stat, since Veinminer chains raise that with no event
 * and include placed blocks (see {@link #onBlockBroken}). Hunting missions count only individual kills with a
 * {@link MobOrigin} check (see {@link #onMobKilled}). Rewards are a money source (not the treasury), limited by slots,
 * rotation and time limits.
 */
final class MissionService {
	private static final long ACTIONBAR_INTERVAL_MS = 2_000;
	private static final Map<UUID, Long> LAST_ACTIONBAR = new HashMap<>();

	private MissionService() {}

	/** Day (server time zone) × rounds per day + round of the day. */
	static long period(long epochMs) {
		return period(epochMs, MissionConfig.rotationHours());
	}

	static long period(long epochMs, int rotationHours) {
		ZonedDateTime t = Instant.ofEpochMilli(epochMs).atZone(TimeUtil.zone());
		int perDay = 24 / rotationHours;
		return t.toLocalDate().toEpochDay() * perDay + t.getHour() / rotationHours;
	}

	static long periodStart(long period, int rotationHours) {
		int perDay = 24 / rotationHours;
		LocalDate day = LocalDate.ofEpochDay(Math.floorDiv(period, perDay));
		int hour = (int) Math.floorMod(period, perDay) * rotationHours;
		return day.atTime(hour, 0).atZone(TimeUtil.zone()).toInstant().toEpochMilli();
	}

	/** Exclusive: the start of the next one. */
	static long periodEnd(long period, int rotationHours) {
		return periodStart(period + 1, rotationHours);
	}

	static long periodEnd(long period) {
		return periodEnd(period, MissionConfig.rotationHours());
	}

	/** Deterministic, weighted, without repeats. */
	static List<Mission> board(UUID player, long period, Tier tier) {
		return board(player, period, tier, dragonAvailable(SmpEconomy.server()));
	}

	/** As above; for {@code dragonAvailable} see {@link #dragonAvailable(MinecraftServer)} (selftests pass it in). */
	static List<Mission> board(UUID player, long period, Tier tier, boolean dragonAvailable) {
		List<Mission> candidates = new ArrayList<>(MissionConfig.candidates(tier));
		// explorations pay only once per player: don't offer targets explored before this round. Targets explored
		// during
		// the round stay (as "already explored"), so the board never changes within a round.
		PlayerState s = MissionStore.peek(player);
		if (s != null && !s.explored.isEmpty()) {
			long start = periodStart(period, MissionConfig.rotationHours());
			candidates.removeIf(m -> exploredBefore(s, m, start));
		}
		if (candidates.isEmpty() || tier.settings().offers <= 0) return List.of();
		Random random = new Random(seed(player, period, tier, MissionConfig.get().root().rotationSeed));
		record Keyed(Mission mission, double key) {}
		List<Keyed> keyed = new ArrayList<>(candidates.size());
		for (Mission m : candidates) {
			// Efraimidis-Spirakis: key u^(1/w), the n largest win
			double u = random.nextDouble();
			keyed.add(new Keyed(m, Math.pow(u, 1.0 / m.def.weight)));
		}
		// after the first defeated dragon every later one is a respawn and doesn't count, so no pure dragon hunts.
		// Removed only AFTER the roll: all other offers stay the same, only the dragon slot moves up. Changes at most
		// once
		// per world (also mid-round; accepting then reports "not on the board").
		if (!dragonAvailable) keyed.removeIf(k -> k.mission().dragonOnly());
		int n = Math.min(tier.settings().offers, keyed.size());
		if (n <= 0) return List.of();
		keyed.sort(Comparator.comparingDouble(Keyed::key).reversed().thenComparing(k -> k.mission().id));
		List<Mission> result = new ArrayList<>(n);
		for (int i = 0; i < n; i++) result.add(keyed.get(i).mission());
		return result;
	}

	static Map<Tier, List<Mission>> board(UUID player, long period) {
		boolean dragon = dragonAvailable(SmpEconomy.server());
		Map<Tier, List<Mission>> map = new EnumMap<>(Tier.class);
		for (Tier t : Tier.values()) map.put(t, board(player, period, t, dragon));
		return map;
	}

	/**
	 * Can an ender dragon still appear here that counts for hunt missions? Only the world's first: once one has been
	 * defeated ({@code EndDragonFight#hasPreviouslyKilledDragon}), every later one is a respawn with end crystals
	 * ({@link MobOrigin#RESPAWNED}). No End dimension or dragon fight: no. No server: yes (unknown).
	 */
	static boolean dragonAvailable(MinecraftServer server) {
		if (server == null) return true;
		ServerLevel end = server.getLevel(Level.END);
		return Mc.hasDragonFight(end) && !Mc.dragonPreviouslyKilled(end);
	}

	static boolean isOnBoard(UUID player, long period, Mission mission) {
		for (Mission m : board(player, period, mission.tier)) {
			if (m.id.equals(mission.id)) return true;
		}
		return false;
	}

	static long seed(UUID player, long period, Tier tier, long salt) {
		long h = 0x9E3779B97F4A7C15L;
		h = EliteConfig.mix(h ^ player.getMostSignificantBits());
		h = EliteConfig.mix(h ^ player.getLeastSignificantBits());
		h = EliteConfig.mix(h ^ period);
		h = EliteConfig.mix(h ^ tier.ordinal());
		return EliteConfig.mix(h ^ salt);
	}

	/** With "taken" brought up to the current rotation. */
	static PlayerState state(UUID player, long now) {
		PlayerState s = MissionStore.state(player);
		long p = period(now);
		if (s.period != p) {
			s.period = p;
			s.taken.clear();
			MissionStore.markDirty();
		}
		return s;
	}

	enum AcceptResult {
		OK, UNKNOWN, ROTATED, NOT_ON_BOARD, EXPLORED, TAKEN, ALREADY_ACTIVE, SAME_TARGET, FULL, COOLDOWN;

		String message(PlayerState s, long now) {
			return switch (this) {
				case OK -> "Mission accepted.";
				case UNKNOWN -> "This mission no longer exists.";
				case ROTATED -> "The board has rotated since – open it again.";
				case NOT_ON_BOARD -> "This mission isn't on your board right now.";
				case EXPLORED -> "You've already explored this target – each exploration pays only once.";
				case TAKEN -> "You've already taken this mission this rotation.";
				case ALREADY_ACTIVE -> "This mission is already active.";
				case SAME_TARGET -> "You already have an active exploration with this target.";
				case FULL -> "You already have " + MissionConfig.maxActive() + " active missions.";
				case COOLDOWN -> "You just abandoned a mission – you can take a new one in §f"
					+ TimeUtil.formatDuration(s.cooldownUntil - now) + "§7.";
			};
		}
	}

	/** {@code boardPeriod} is the rotation of the board the offer came from. */
	static AcceptResult canAccept(UUID player, String missionId, long boardPeriod, long now) {
		Mission m = MissionConfig.byId(missionId);
		if (m == null) return AcceptResult.UNKNOWN;
		if (boardPeriod != period(now)) return AcceptResult.ROTATED;
		if (!isOnBoard(player, boardPeriod, m)) return AcceptResult.NOT_ON_BOARD;
		PlayerState s = state(player, now);
		if (isExplored(s, m)) return AcceptResult.EXPLORED;
		if (s.taken.contains(m.id)) return AcceptResult.TAKEN;
		if (s.hasActive(m.id)) return AcceptResult.ALREADY_ACTIVE;
		if (sameTargetActive(s, m)) return AcceptResult.SAME_TARGET;
		if (s.active.size() >= MissionConfig.maxActive()) return AcceptResult.FULL;
		if (now < s.cooldownUntil) return AcceptResult.COOLDOWN;
		return AcceptResult.OK;
	}

	/** Rechecks everything. On OK the mission is active and used up for this rotation. */
	static AcceptResult accept(ServerPlayer player, String missionId, long boardPeriod, long now) {
		AcceptResult r = canAccept(player.getUUID(), missionId, boardPeriod, now);
		if (r != AcceptResult.OK) return r;
		PlayerState s = state(player.getUUID(), now);
		Mission m = MissionConfig.byId(missionId);
		s.taken.add(m.id);
		start(player, s, m, 0, now);
		return AcceptResult.OK;
	}

	/**
	 * Admin and tests: assigns directly, without board, round or cooldown; slots, duplicates and explored targets still
	 * apply. {@code amountOverride > 0} replaces the amount (e.g. short e2e tests).
	 */
	static AcceptResult assign(ServerPlayer player, Mission m, int amountOverride, long now) {
		PlayerState s = state(player.getUUID(), now);
		if (isExplored(s, m)) return AcceptResult.EXPLORED;
		if (s.hasActive(m.id)) return AcceptResult.ALREADY_ACTIVE;
		if (sameTargetActive(s, m)) return AcceptResult.SAME_TARGET;
		if (s.active.size() >= MissionConfig.maxActive()) return AcceptResult.FULL;
		start(player, s, m, amountOverride, now);
		return AcceptResult.OK;
	}

	static boolean isExplored(PlayerState s, Mission m) {
		return m.type == Mission.Type.EXPLORE && m.exploreKey != null && s.explored.containsKey(m.exploreKey);
	}

	static boolean exploredBefore(PlayerState s, Mission m, long before) {
		if (m.type != Mission.Type.EXPLORE || m.exploreKey == null) return false;
		Long at = s.explored.get(m.exploreKey);
		return at != null && at < before;
	}

	/** Would otherwise pay twice on entering. */
	static boolean sameTargetActive(PlayerState s, Mission m) {
		if (m.type != Mission.Type.EXPLORE || m.exploreKey == null) return false;
		for (ActiveMission a : s.active) {
			Mission other = a.mission();
			if (other != null && m.exploreKey.equals(other.exploreKey)) return true;
		}
		return false;
	}

	private static ActiveMission start(ServerPlayer player, PlayerState s, Mission m, int amountOverride, long now) {
		ActiveMission a = new ActiveMission();
		a.instance = s.nextInstance++;
		a.missionId = m.id;
		a.def = m.def.copy();
		if (amountOverride > 0 && m.type != Mission.Type.EXPLORE) a.def.amount = Math.min(MissionConfig.MAX_AMOUNT, amountOverride);
		a.acceptedAt = now;
		a.expiresAt = now + timeLimitMinutes(m) * 60_000L;
		a.reward = reward(m);
		a.keyTier = keyTier(m);
		a.keys = keys(m);
		if (m.type == Mission.Type.STAT) {
			a.baseline = m.statSum(player);
			a.minedEvents = true;
			a.killEvents = true;
		}
		// exploration only counts after entering: whoever already stands at the target has to leave once
		if (m.type == Mission.Type.EXPLORE) a.armed = !m.matchesLocation(player);
		s.active.add(a);
		MissionStore.markDirty();
		return a;
	}

	static int timeLimitMinutes(Mission m) {
		return m.def.timeLimitMinutes > 0 ? m.def.timeLimitMinutes : m.tier.settings().timeLimitMinutes;
	}

	/** Deliveries pay only per piece, otherwise the price cap could be bypassed. */
	static long reward(Mission m) {
		return m.type == Mission.Type.DELIVER ? 0 : m.def.rewardCents;
	}

	static String keyTier(Mission m) {
		String tier = m.def.keyTier != null ? m.def.keyTier : m.tier.settings().keyTier;
		return tier == null ? "" : tier;
	}

	/**
	 * Deliveries never give keys: the items can be bought from the system (shop from pivot + 1, NPC offers from price
	 * cap + 1 cent) and the delivery pays back up to the price cap, so the key would cost only cents to a few dollars.
	 */
	static int keys(Mission m) {
		if (m.type == Mission.Type.DELIVER || !CrateKeys.enabled()) return 0; // keys only with the crates module
		int k = m.def.keys != null ? m.def.keys : m.tier.settings().keys;
		return k > 0 && MissionConfig.validKeyTier(keyTier(m)) ? k : 0;
	}

	/**
	 * Price per delivered piece now: desired price (0 = price cap), always at most {@code
	 * PriceGuard.maxNpcOrderPrice(player, item, Services.marketValue(item))}; 0 means not deliverable right now.
	 */
	static long deliveryPrice(UUID player, Mission m, MissionConfig.MissionDef def) {
		if (m.type != Mission.Type.DELIVER || m.item == null) return 0;
		long market = Services.marketValue(m.item).orElse(0);
		long cap = PriceGuard.maxNpcOrderPrice(player, m.item, market);
		if (cap <= 0) return 0;
		return def.rewardPerItemCents > 0 ? Math.min(def.rewardPerItemCents, cap) : cap;
	}

	/** Starts the cooldown; money already received stays. */
	static boolean cancel(ServerPlayer player, long instance, long now) {
		PlayerState s = MissionStore.peek(player.getUUID());
		ActiveMission a = s == null ? null : s.find(instance);
		if (a == null) return false;
		s.active.remove(a);
		s.cooldownUntil = now + MissionConfig.cancelCooldownMs();
		MissionStore.markDirty();
		return true;
	}

	enum DeliverStatus { OK, NOT_ACTIVE, EXPIRED, WRONG_TYPE, NO_PRICE, NONE, ONLY_MODIFIED, PAYMENT_FAILED, BLOCKED }

	record DeliverResult(DeliverStatus status, int delivered, long paid, long perItem, boolean completed) {
		static DeliverResult of(DeliverStatus status) {
			return new DeliverResult(status, 0, 0, 0, false);
		}
	}

	/**
	 * Takes as many unmodified items as needed and available from the main inventory and pays right away (price per
	 * piece capped, recomputed per delivery). If the credit fails the items go back. Not in creative or spectator
	 * (items from nothing).
	 */
	static DeliverResult deliver(ServerPlayer player, long instance, long now) {
		UUID id = player.getUUID();
		PlayerState s = MissionStore.peek(id);
		ActiveMission a = s == null ? null : s.find(instance);
		if (a == null) return DeliverResult.of(DeliverStatus.NOT_ACTIVE);
		if (now >= a.expiresAt) {
			expire(id, player, s, a);
			return DeliverResult.of(DeliverStatus.EXPIRED);
		}
		Mission m = a.mission();
		if (m == null || m.type != Mission.Type.DELIVER) return DeliverResult.of(DeliverStatus.WRONG_TYPE);
		long remaining = a.amount() - a.progress;
		if (remaining <= 0) {
			complete(id, player, s, a);
			return new DeliverResult(DeliverStatus.OK, 0, 0, 0, true);
		}
		if (Items.isCreativeOrSpectator(player)) return DeliverResult.of(DeliverStatus.BLOCKED);
		long per = deliveryPrice(id, m, a.def);
		if (per <= 0) return DeliverResult.of(DeliverStatus.NO_PRICE);
		int have = Items.countPristine(player, m.item);
		if (have <= 0) {
			return DeliverResult.of(Items.count(player, st -> st.is(m.item)) > 0 ? DeliverStatus.ONLY_MODIFIED : DeliverStatus.NONE);
		}
		int wanted = (int) Math.min(have, remaining);
		// the credit must be possible (account limit), otherwise don't take items at all
		long maxPay = safeMultiply(per, wanted);
		if (maxPay < 0 || maxPay > Money.MAX - EconomyService.balance(id)) return DeliverResult.of(DeliverStatus.PAYMENT_FAILED);
		int removed = Items.remove(player, st -> st.is(m.item) && Items.isPristine(st), wanted);
		if (removed <= 0) return DeliverResult.of(DeliverStatus.NONE);
		long pay = per * removed;
		EconomyService.Result r = EconomyService.deposit(id, pay);
		if (!r.ok()) {
			// lose nothing: the same unmodified items go back
			Items.giveOrDrop(player, new ItemStack(m.item, removed));
			SmpEconomy.LOG.warn("[SMPEco] missions: delivery by {} failed ({}), {} items returned", player.getPlainTextName(), r, removed);
			return DeliverResult.of(DeliverStatus.PAYMENT_FAILED);
		}
		a.progress += removed;
		a.paid += pay;
		s.earnedCents = safeAdd(s.earnedCents, pay);
		MissionStore.markDirty();
		DataStore.requestPlayerSave(player); // items leave the inventory, so save the player too (anti-dupe on crash)
		boolean done = false;
		if (a.progress >= a.amount()) done = complete(id, player, s, a);
		return new DeliverResult(DeliverStatus.OK, removed, pay, per, done);
	}

	static String deliverMessage(DeliverResult r) {
		return switch (r.status()) {
			case OK -> r.delivered() > 0 ? "§aDelivered " + r.delivered() + "× §7(+" + Msg.moneyStr(r.paid()) + "§7)." : "Mission complete.";
			case NOT_ACTIVE -> "This mission is no longer active.";
			case EXPIRED -> "This mission has expired.";
			case WRONG_TYPE -> "This mission isn't a delivery.";
			case NO_PRICE -> "This item has no market value right now – try again later.";
			case NONE -> "You don't have any matching items in your inventory.";
			case ONLY_MODIFIED -> "Only unmodified items count (no custom names, enchantments or damage).";
			case PAYMENT_FAILED -> "Payment not possible (balance limit) – nothing delivered.";
			case BLOCKED -> "You can't deliver in Creative or Spectator mode.";
		};
	}

	static void update(ServerPlayer player, long now) {
		update(player, now, () -> dragonAvailable(player.level().getServer()));
	}

	/** As above; {@code dragonAvailable} is only queried for pure dragon missions (selftests pass it in). */
	static void update(ServerPlayer player, long now, BooleanSupplier dragonAvailable) {
		UUID id = player.getUUID();
		PlayerState s = MissionStore.peek(id);
		if (s == null || s.active.isEmpty()) return;
		for (ActiveMission a : List.copyOf(s.active)) {
			if (now >= a.expiresAt) {
				// check stat progress first: a target reached in time must not expire unpaid
				Mission late = a.mission();
				if (late != null && late.type == Mission.Type.STAT) {
					migrate(player, a, late);
					long progress = statProgress(player, a, late);
					if (progress >= a.amount()) {
						a.progress = progress;
						MissionStore.markDirty();
						complete(id, player, s, a);
						continue;
					}
				}
				expire(id, player, s, a);
				continue;
			}
			Mission m = a.mission();
			if (m == null) continue;
			switch (m.type) {
				case STAT -> {
					migrate(player, a, m);
					long progress = statProgress(player, a, m);
					if (progress != a.progress) {
						boolean up = progress > a.progress;
						a.progress = progress;
						MissionStore.markDirty();
						if (up && progress < a.amount()) progressActionbar(player, a, now);
					}
					if (progress >= a.amount()) complete(id, player, s, a);
					else if (m.dragonOnly() && !dragonAvailable.getAsBoolean()) dropUnreachable(id, player, s, a);
				}
				case TREASURE, DELIVER -> {
					if (a.progress >= a.amount()) complete(id, player, s, a);
				}
				case EXPLORE -> {
					String at = m.locate(player);
					if (at == null) {
						if (!a.armed) {
							a.armed = true;
							MissionStore.markDirty();
						}
					} else if (a.armed) {
						reachTarget(id, player, s, a, m, at, now);
					}
				}
			}
		}
	}

	/**
	 * Exploration target entered (mission armed): pays only if target and structure instance are new for this player.
	 * {@code instance} comes from {@link Mission#locate}, "" without a structure target. Target and instance get
	 * recorded BEFORE the payout.
	 */
	static boolean reachTarget(UUID id, ServerPlayer online, PlayerState s, ActiveMission a, Mission m, String instance, long now) {
		if (!s.active.contains(a) || m.exploreKey == null) return false;
		if (s.explored.containsKey(m.exploreKey)) {
			// only possible with old saves or a reload (accepting checks this): remove without reward instead of
			// blocking the slot
			s.active.remove(a);
			MissionStore.markDirty();
			String text = "§cMission removed: §f" + a.name() + " §7(target already explored – pays only once).";
			if (online != null) Msg.info(online, text);
			else Msg.sendOrQueue(id, text);
			return false;
		}
		if (instance != null && !instance.isEmpty() && s.exploredStructures.contains(instance)) {
			if (online != null) actionbar(online, "§cYou've already explored this structure – find another one.", now);
			return false;
		}
		s.explored.put(m.exploreKey, now);
		if (instance != null && !instance.isEmpty()) s.exploredStructures.add(instance);
		a.progress = 1;
		MissionStore.markDirty();
		return complete(id, online, s, a);
	}

	static int sweepOffline(MinecraftServer server, long now) {
		return sweepOffline(server, now, id -> true);
	}

	/**
	 * Like {@link #sweepOffline(MinecraftServer, long)}, but only for players matching {@code filter} (selftests: only
	 * the test player).
	 */
	static int sweepOffline(MinecraftServer server, long now, Predicate<UUID> filter) {
		int expired = 0;
		for (Map.Entry<UUID, PlayerState> e : List.copyOf(MissionStore.players().entrySet())) {
			if (!filter.test(e.getKey())) continue;
			if (server != null && server.getPlayerList().getPlayer(e.getKey()) != null) continue; // online: update() handles it
			PlayerState s = e.getValue();
			for (ActiveMission a : List.copyOf(s.active)) {
				if (now >= a.expiresAt) {
					expire(e.getKey(), null, s, a);
					expired++;
				}
			}
		}
		return expired;
	}

	/**
	 * Mining missions ({@code mined:}) count ONLY here: a block the player breaks in the vanilla flow ({@code
	 * PlayerBlockBreakEvents.AFTER} from {@code ServerPlayerGameMode#destroyBlock}), not placed or generator-made, and
	 * only when vanilla would award the stat (not creative, correct tool; AFTER runs before {@code mineBlock}, so the
	 * tool is still unchanged).
	 * <p>{@code BLOCK_MINED} is deliberately not used: Veinminer calls {@code awardStat(BLOCK_MINED, chain - 1)} and
	 * removes chain blocks with no AFTER event, self-placed ones too (silk touch ore, logs from the shop), which would
	 * mean money and keys with no real mining. So chain blocks don't count, only the self-mined first block.
	 */
	static void onBlockBroken(ServerPlayer player, Level level, BlockPos pos, BlockState state) {
		PlayerState s = MissionStore.peek(player.getUUID());
		if (s == null || s.active.isEmpty()) return;
		if (player.preventsBlockDrops() || !player.hasCorrectToolForDrops(state)) return;
		Block block = state.getBlock();
		Boolean placed = null;
		for (ActiveMission a : s.active) {
			Mission m = a.mission();
			if (m == null || m.type != Mission.Type.STAT || !m.countsMined(block)) continue;
			if (placed == null) placed = PlacedBlockTracker.isPlayerPlaced(level, pos);
			if (placed) return;
			migrate(player, a, m);
			a.mined++;
			MissionStore.markDirty();
		}
	}

	/**
	 * Hunt missions ({@code killed:}) count ONLY here: the player killed the mob personally ({@code
	 * ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY}, fires exactly where vanilla awards {@code ENTITY_KILLED}),
	 * not in creative or spectator. How much a kill counts depends on the mob's origin ({@link #killMilli}).
	 * <p>{@code ENTITY_KILLED} is deliberately not used: it counts every mob, including a respawned ender dragon (4 end
	 * crystals, all ingredients in the shop, about $124 for $15,000 + an epic key) and shulkers from a splitting farm
	 * (unlimited).
	 */
	static void onMobKilled(ServerPlayer player, LivingEntity killed) {
		PlayerState s = MissionStore.peek(player.getUUID());
		if (s == null || s.active.isEmpty()) return;
		if (player.isCreative() || player.isSpectator()) return;
		long milli = killMilli(killed.getTags());
		if (milli <= 0) return;
		EntityType<?> type = killed.getType();
		long now = System.currentTimeMillis();
		for (ActiveMission a : s.active) {
			Mission m = a.mission();
			if (m == null || m.type != Mission.Type.STAT || now >= a.expiresAt || !m.countsKill(type)) continue;
			migrate(player, a, m);
			a.killMilli = Math.min(a.amount() * 1000L, Math.max(0, a.killMilli) + milli);
			MissionStore.markDirty();
		}
	}

	/**
	 * A kill's share in thousandths by mob origin ({@link MobOrigin}, tags on the mob): bred, summoned, spawn egg and
	 * respawned dragon 0; spawner and trial spawner × {@code spawnerKillFactor}; otherwise 1000.
	 */
	static long killMilli(Set<String> tags) {
		if (MobOrigin.isArtificial(tags)) return 0;
		if (MobOrigin.fromSpawner(tags)) return Math.round(MissionConfig.spawnerKillFactor() * 1000);
		return 1000;
	}

	/**
	 * Progress of a stat mission: difference of the other stats (never negative) + counted mining + counted kills
	 * (whole).
	 */
	static long statProgress(ServerPlayer player, ActiveMission a, Mission m) {
		long counted = Math.max(0, m.statSum(player) - a.baseline);
		long total = safeAdd(safeAdd(counted, Math.max(0, a.mined)), Math.max(0, a.killMilli) / 1000);
		return Math.min(a.amount(), total);
	}

	/** Converts old saves: mining first, then hunting, because the baseline rules build on each other. */
	static void migrate(ServerPlayer player, ActiveMission a, Mission m) {
		migrateMined(player, a, m);
		migrateKills(player, a, m);
	}

	/**
	 * Old saves accepted before mining counted through events, baseline including the mining stat: moves existing
	 * progress to {@link ActiveMission#mined} and sets the baseline to the other stats. From then on mining counts only
	 * through {@link #onBlockBroken}, and progress stays the same.
	 */
	static void migrateMined(ServerPlayer player, ActiveMission a, Mission m) {
		if (a.minedEvents || m.type != Mission.Type.STAT) return;
		if (m.hasMined()) {
			a.mined = Math.max(0, Math.min(a.amount(), a.progress));
			a.baseline = m.statSumWithoutMined(player);
		}
		a.minedEvents = true;
		MissionStore.markDirty();
	}

	/**
	 * Old saves accepted before hunting counted through events, baseline including the hunting stat, mining already
	 * converted: moves the stat difference to {@link ActiveMission#killMilli} and sets the baseline to the other stats.
	 * From then on kills count only through {@link #onMobKilled}, and progress stays the same.
	 */
	static void migrateKills(ServerPlayer player, ActiveMission a, Mission m) {
		if (a.killEvents || m.type != Mission.Type.STAT) return;
		if (m.hasKilled()) {
			long old = Math.max(0, m.statSumWithoutMined(player) - a.baseline);
			a.killMilli = Math.min(a.amount(), old) * 1000L;
			a.baseline = m.statSum(player);
		}
		a.killEvents = true;
		MissionStore.markDirty();
	}

	/** Mixin on FishingRodHookedTrigger. Treasures count for treasure missions. */
	static void onFishingLoot(ServerPlayer player, Collection<ItemStack> loot) {
		if (player == null || loot == null || loot.isEmpty()) return;
		int treasures = 0;
		for (ItemStack stack : loot) {
			if (Mission.isTreasure(stack)) treasures++;
		}
		if (treasures == 0) return;
		PlayerState s = MissionStore.peek(player.getUUID());
		if (s == null || s.active.isEmpty()) return;
		long now = System.currentTimeMillis();
		for (ActiveMission a : List.copyOf(s.active)) {
			Mission m = a.mission();
			if (m == null || m.type != Mission.Type.TREASURE || now >= a.expiresAt) continue;
			a.progress = Math.min(a.amount(), a.progress + treasures);
			MissionStore.markDirty();
			if (a.progress >= a.amount()) complete(player.getUUID(), player, s, a);
			else progressActionbar(player, a, 0);
		}
	}

	/**
	 * Completes and pays once: the mission leaves the list first, so a second call or a later tick doesn't find
	 * it and pays nothing.
	 */
	static boolean complete(UUID id, ServerPlayer online, PlayerState s, ActiveMission a) {
		if (!s.active.remove(a)) return false;
		s.completed++;
		MissionStore.markDirty();
		StringBuilder reward = new StringBuilder();
		if (a.reward > 0) {
			EconomyService.Result r = EconomyService.deposit(id, a.reward);
			if (r.ok()) {
				s.earnedCents = safeAdd(s.earnedCents, a.reward);
				reward.append(Msg.moneyStr(a.reward));
			} else {
				SmpEconomy.LOG.error("[SMPEco] missions: reward {} for {} not credited: {}", a.reward, id, r);
				reward.append("§c").append(r.message()).append("§7");
			}
		} else if (a.paid > 0) {
			reward.append(Msg.moneyStr(a.paid)).append(" §8(deliveries)§7");
		}
		// deliveries never give keys, also not from an older snapshot, see keys(Mission)
		boolean deliver = Mission.Type.byId(a.def.type).orElse(null) == Mission.Type.DELIVER;
		if (a.keys > 0 && !deliver && MissionConfig.validKeyTier(a.keyTier) && CrateKeys.enabled()) {
			CrateKeys.give(id, a.keyTier, a.keys);
			if (!reward.isEmpty()) reward.append(" + ");
			reward.append("§d").append(a.keys).append("× ").append(CrateKeys.label(a.keyTier)).append(" Key§7");
		}
		String text = "§a§lMission complete: §f" + a.name() + "§a!" + (reward.isEmpty() ? "" : " §7Reward: " + reward);
		if (online != null) {
			Msg.success(online, text);
			if (Items.canReceive(online)) Gui.successSound(online);
		} else {
			Msg.sendOrQueue(id, text);
		}
		Mission m = a.mission();
		if (m != null && m.tier == Tier.ELITE && MissionConfig.get().root().announceElite) announce(id, a);
		return true;
	}

	private static void announce(UUID id, ActiveMission a) {
		MinecraftServer server = SmpEconomy.server();
		if (server == null || dev.smpeconomy.core.test.TestPlayers.isTestPlayer(id)) return;
		String name = EconomyService.nameOrUnknown(id);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.getUUID().equals(id) || !Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
			Msg.info(p, "§d" + name + " §7completed the Elite mission §f" + a.name() + "§7!");
		}
	}

	static void expire(UUID id, ServerPlayer online, PlayerState s, ActiveMission a) {
		if (!s.active.remove(a)) return;
		MissionStore.markDirty();
		String text = "§cMission expired: §f" + a.name() + (a.paid > 0 ? " §7(deliveries stay paid: " + Msg.moneyStr(a.paid) + "§7)" : "");
		if (online != null) Msg.info(online, text);
		else Msg.sendOrQueue(id, text);
	}

	/**
	 * Pure dragon mission after the world's dragon was defeated (every later one is a respawn and doesn't count):
	 * removed with no reward and no cooldown so the slot isn't blocked until expiry.
	 */
	static void dropUnreachable(UUID id, ServerPlayer online, PlayerState s, ActiveMission a) {
		if (!s.active.remove(a)) return;
		MissionStore.markDirty();
		String text = "§cMission removed: §f" + a.name() + " §7(the Ender Dragon has already been defeated – respawned dragons don't count).";
		if (online != null) Msg.info(online, text);
		else Msg.sendOrQueue(id, text);
	}

	private static void progressActionbar(ServerPlayer player, ActiveMission a, long now) {
		if (!MissionConfig.get().root().progressActionbar) return;
		actionbar(player, "§6Mission §8» §f" + a.name() + " §7" + a.progress + "/" + a.amount(), now);
	}

	/** {@code now} 0 means now. */
	static void actionbar(ServerPlayer player, String text, long now) {
		long t = now > 0 ? now : System.currentTimeMillis();
		Long last = LAST_ACTIONBAR.get(player.getUUID());
		if (last != null && t - last < ACTIONBAR_INTERVAL_MS && t >= last) return;
		LAST_ACTIONBAR.put(player.getUUID(), t);
		Msg.actionBar(player, text);
	}

	/** Runs with the offline sweep. */
	static void pruneActionbar(MinecraftServer server) {
		if (server == null) return;
		LAST_ACTIONBAR.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	static long safeMultiply(long a, long b) {
		try {
			return Math.multiplyExact(a, b);
		} catch (ArithmeticException e) {
			return -1;
		}
	}

	static long safeAdd(long a, long b) {
		try {
			return Math.addExact(a, b);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}
}
