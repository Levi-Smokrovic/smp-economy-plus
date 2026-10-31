package dev.smpeconomy.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;

/**
 * Services between features that compile independently. The provider implements an interface below and registers it
 * with {@link #register}; consumers call {@link #find} on each use and carry on without it if the feature is off. No
 * reflection or Class.forName on other feature packages. Simple flags go through {@link Hooks#addFlag}.
 *
 * <p>Provider to consumers: {@link MarketValues} market to orders, news, crates; {@link NpcOrders} orders to news;
 * {@link DoubleOrNothing} casino to minigames; {@link PlayerStats} stats to hud, holo; {@link Bank} bank to economy,
 * holo; {@link DailyTasks} tasks to hud; {@link TraderRanks} events to stats; {@link Missions} missions to hud;
 * {@link JobLevels} jobs to hud.
 */
public final class Services {
	/** Used when market supplies no value of its own. */
	public static final double DEFAULT_MARKET_FACTOR = 1.6;

	private static final Map<Class<?>, Object> SERVICES = new HashMap<>();

	private Services() {}

	/** {@code remove()} only unregisters exactly this implementation. */
	public static <T> Hooks.Registration register(Class<T> type, T impl) {
		if (type == null || impl == null) throw new IllegalArgumentException("Service type or implementation missing");
		Object previous = SERVICES.put(type, impl);
		if (previous != null && previous != impl) SmpEconomy.LOG.warn("[SMPEco] Service {} replaced", type.getSimpleName());
		return Hooks.Registration.of(() -> SERVICES.remove(type, impl));
	}

	public static <T> Optional<T> find(Class<T> type) {
		return Optional.ofNullable(type.cast(SERVICES.get(type)));
	}

	/**
	 * From market ({@link MarketValues}), otherwise {@link #defaultMarketValue}; includes
	 * {@link PriceModifiers#get(Item)}. Empty means no estimate, so NPCs do not trade the item. All NPC prices and PriceGuard
	 * caps must use this same value.
	 */
	public static OptionalLong marketValue(Item item) {
		Optional<MarketValues> provider = find(MarketValues.class);
		if (provider.isPresent()) {
			try {
				OptionalLong v = provider.get().marketValue(item);
				return v != null && v.isPresent() && v.getAsLong() > 0 ? v : OptionalLong.empty();
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] MarketValues service threw, using default market value", e);
			}
		}
		return defaultMarketValue(item);
	}

	public static OptionalLong defaultMarketValue(Item item) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return OptionalLong.empty();
		double v = base.getAsLong() * DEFAULT_MARKET_FACTOR * PriceModifiers.get(item);
		long cents = v >= Money.MAX ? Money.MAX : (long) Math.floor(v + 1e-9);
		return cents > 0 ? OptionalLong.of(cents) : OptionalLong.empty();
	}

	@FunctionalInterface
	public interface MarketValues {
		OptionalLong marketValue(Item item);
	}

	/**
	 * Time-limited NPC bulk order for news. Orders sets the price itself (market value capped by
	 * {@link PriceGuard#maxNpcOrderPrice}, paid from {@link Treasury}).
	 */
	public interface NpcOrders {
		/** {@code amount} can be below the requested one because of the treasury budget. */
		record BulkOrder(long id, int amount) {}

		/** @return the created order, or null (budget too small, item not tradable) */
		BulkOrder startBulkOrder(Item item, int amount, long durationMs, String label);

		/**
		 * False once fulfilled, cancelled, expired or unknown. True while orders cannot tell because data is not
		 * loaded.
		 */
		boolean isOpen(long orderId);
	}

	/**
	 * Offers a player "double or nothing" against the house right after winning a PvP coinflip. {@code cents} is
	 * already on the player's account; the stake is only charged on acceptance.
	 *
	 * @return false if not offered (player offline, treasury cannot cover the win, limit)
	 */
	@FunctionalInterface
	public interface DoubleOrNothing {
		boolean offer(ServerPlayer player, long cents);
	}

	public interface PlayerStats {
		long playerKills(UUID player);

		long deaths(UUID player);

		long mobKills(UUID player);
	}

	@FunctionalInterface
	public interface Bank {
		long balance(UUID player);
	}

	public interface DailyTasks {
		int done(UUID player);

		int total(UUID player);
	}

	/**
	 * Mission state for the sidebar. Never creates player data. {@link #elite} pins the day's elite on the first call
	 * of a new day, like the board does.
	 */
	public interface Missions {
		int activeCount(UUID player);

		int maxActive();

		MissionProgress nearest(UUID player);

		EliteStatus elite(UUID player);

		record MissionProgress(String name, long progress, long amount) {}

		enum EliteStatus { NONE, OPEN, DONE }
	}

	@FunctionalInterface
	public interface JobLevels {
		JobProgress progress(UUID player, String jobId);

		/** {@code xpForNext} of 0 or less is unknown; meaningless at max level. */
		record JobProgress(int level, int maxLevel, long xp, long xpForNext) {}
	}

	public interface TraderRanks {
		String rankName(UUID player);

		double bonusPercent(UUID player);

		long lifetimeSales(UUID player);
	}

	/**
	 * Live "NPCs visible" switch for the admin GUI. Without this service the GUI only offers the module toggle, which
	 * needs a restart.
	 */
	public interface NpcSwitch {
		boolean npcsVisible();

		/** Returns null on success, otherwise an error message. */
		String setNpcsVisible(boolean visible, String actor);

		default void openManager(ServerPlayer admin) {}
	}

	/**
	 * Captures the starter kit from the admin's inventory, with confirmation. Without this service the GUI runs
	 * {@code /smpeco starter capture} if that command exists.
	 */
	public interface StarterKit {
		void openCapture(ServerPlayer admin);

		default String summary() {
			return "";
		}
	}
}
