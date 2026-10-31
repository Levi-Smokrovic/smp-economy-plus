package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;

/**
 * Extension points between features that do not know each other.
 *
 * <p>Multipliers (1.0 is neutral) are registered by features and read through this class or {@link PriceGuard}, never
 * directly. All factors of a type multiply. Invalid values (NaN, infinite, 0 or less) count as 1.0, values above
 * {@link #MAX_FACTOR} are capped, and provider exceptions are logged and count as 1.0. The same {@code id} replaces the
 * previous registration.
 *
 * <p>{@link #onItemsSold} is fired by the feature selling to the system. {@link #onPlayerKilledPlayer} is fired by the
 * core itself (PvP death with a player as cause, no FakePlayer, no suicide); features only listen.
 *
 * <p>Flags ({@link #addFlag}/{@link #flag}) are simple on/off states other features read. Services with methods go
 * through {@link Services}.
 */
public final class Hooks {
	public static final double MAX_FACTOR = 10.0;

	/**
	 * Set by news while auction fever runs: market's NPC buyers buy twice as fast and use
	 * {@link PriceGuard#NPC_BUY_MARKET_FACTOR_MAX}. PriceGuard caps stay.
	 */
	public static final String FLAG_AUCTION_FEVER = "news.auction_fever";

	@FunctionalInterface
	public interface SellMultiplier {
		/** The player may be offline. */
		double get(UUID player, Item item);
	}

	@FunctionalInterface
	public interface JobMultiplier {
		double get(UUID player, String jobId);
	}

	@FunctionalInterface
	public interface ChatGameMultiplier {
		double get(UUID player);
	}

	@FunctionalInterface
	public interface ShopDiscount {
		double get(Item item);
	}

	@FunctionalInterface
	public interface ItemsSoldListener {
		void onItemsSold(ServerPlayer player, long cents, int count);

		/**
		 * {@code lines} is empty if the breakdown is unknown. Listeners that need to know which items were sold
		 * override this variant.
		 */
		default void onItemsSold(ServerPlayer player, long cents, int count, List<SoldLine> lines) {
			onItemsSold(player, cents, count);
		}
	}

	public record SoldLine(Item item, long count, long unitPrice) {}

	@FunctionalInterface
	public interface PlayerKillListener {
		void onPlayerKilledPlayer(ServerPlayer killer, ServerPlayer victim);
	}

	public record Part(String id, String label, double factor) {}

	/** Calling {@link #remove()} repeatedly is harmless. */
	public static final class Registration {
		private final Runnable remover;

		private Registration(Runnable remover) {
			this.remover = remover;
		}

		public static Registration of(Runnable remover) {
			return new Registration(remover);
		}

		public void remove() {
			remover.run();
		}
	}

	private record Entry<P>(String id, String label, P provider) {}

	private static final List<Entry<SellMultiplier>> SELL = new ArrayList<>();
	private static final List<Entry<JobMultiplier>> JOB = new ArrayList<>();
	private static final List<Entry<ChatGameMultiplier>> CHAT = new ArrayList<>();
	private static final List<Entry<ShopDiscount>> SHOP = new ArrayList<>();
	private static final List<ItemsSoldListener> SOLD = new ArrayList<>();
	private static final List<PlayerKillListener> KILLS = new ArrayList<>();
	private static final Map<String, BooleanSupplier> FLAGS = new HashMap<>();
	private static final Set<String> WARNED = new HashSet<>();

	private Hooks() {}

	public static Registration addSellMultiplier(String id, String label, SellMultiplier provider) {
		return add(SELL, id, label, provider);
	}

	public static Registration addJobMultiplier(String id, String label, JobMultiplier provider) {
		return add(JOB, id, label, provider);
	}

	public static Registration addChatGameMultiplier(String id, String label, ChatGameMultiplier provider) {
		return add(CHAT, id, label, provider);
	}

	public static Registration addShopDiscount(String id, String label, ShopDiscount provider) {
		return add(SHOP, id, label, provider);
	}

	public static Registration onItemsSold(ItemsSoldListener listener) {
		SOLD.add(listener);
		return new Registration(() -> SOLD.remove(listener));
	}

	public static Registration onPlayerKilledPlayer(PlayerKillListener listener) {
		KILLS.add(listener);
		return new Registration(() -> KILLS.remove(listener));
	}

	public static Registration addFlag(String id, BooleanSupplier provider) {
		if (id == null || id.isBlank() || provider == null) throw new IllegalArgumentException("Flag id or provider missing");
		FLAGS.put(id, provider);
		return new Registration(() -> FLAGS.remove(id, provider));
	}

	public static boolean flag(String id) {
		BooleanSupplier provider = FLAGS.get(id);
		if (provider == null) return false;
		try {
			return provider.getAsBoolean();
		} catch (RuntimeException e) {
			if (WARNED.add(id + ":flag")) SmpEconomy.LOG.error("[SMPEco] Flag {} threw an exception, counts as off", id, e);
			return false;
		}
	}

	public static double sellMultiplier(UUID player, Item item) {
		double product = 1.0;
		for (Entry<SellMultiplier> e : List.copyOf(SELL)) product *= sanitize(e, () -> e.provider().get(player, item));
		return product;
	}

	public static List<Part> sellParts(UUID player, Item item) {
		List<Part> parts = new ArrayList<>();
		for (Entry<SellMultiplier> e : List.copyOf(SELL)) addPart(parts, e, sanitize(e, () -> e.provider().get(player, item)));
		return parts;
	}

	public static double jobMultiplier(UUID player, String jobId) {
		double product = 1.0;
		for (Entry<JobMultiplier> e : List.copyOf(JOB)) product *= sanitize(e, () -> e.provider().get(player, jobId));
		return product;
	}

	public static List<Part> jobParts(UUID player, String jobId) {
		List<Part> parts = new ArrayList<>();
		for (Entry<JobMultiplier> e : List.copyOf(JOB)) addPart(parts, e, sanitize(e, () -> e.provider().get(player, jobId)));
		return parts;
	}

	public static double chatGameMultiplier(UUID player) {
		double product = 1.0;
		for (Entry<ChatGameMultiplier> e : List.copyOf(CHAT)) product *= sanitize(e, () -> e.provider().get(player));
		return product;
	}

	public static List<Part> chatGameParts(UUID player) {
		List<Part> parts = new ArrayList<>();
		for (Entry<ChatGameMultiplier> e : List.copyOf(CHAT)) addPart(parts, e, sanitize(e, () -> e.provider().get(player)));
		return parts;
	}

	/** {@link PriceGuard} keeps discounts from allowing arbitrage. */
	public static double shopDiscount(Item item) {
		double product = 1.0;
		for (Entry<ShopDiscount> e : List.copyOf(SHOP)) product *= sanitize(e, () -> e.provider().get(item));
		return product;
	}

	public static List<Part> shopParts(Item item) {
		List<Part> parts = new ArrayList<>();
		for (Entry<ShopDiscount> e : List.copyOf(SHOP)) addPart(parts, e, sanitize(e, () -> e.provider().get(item)));
		return parts;
	}

	/** Without a breakdown listeners must assume the worst. */
	public static void fireItemsSold(ServerPlayer player, long cents, int count) {
		fireItemsSold(player, cents, count, List.of());
	}

	/** {@code cents} is the total, equal to the sum of count x unitPrice over {@code lines}. */
	public static void fireItemsSold(ServerPlayer player, long cents, int count, List<SoldLine> lines) {
		if (cents <= 0 && count <= 0) return;
		List<SoldLine> posted = lines == null ? List.of() : lines.stream().filter(java.util.Objects::nonNull).toList();
		for (ItemsSoldListener l : List.copyOf(SOLD)) {
			try {
				l.onItemsSold(player, cents, count, posted);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] onItemsSold listener failed", e);
			}
		}
	}

	/** Public for selftests only. Feature code must never call this, or kills would count twice. */
	public static void firePlayerKilledPlayer(ServerPlayer killer, ServerPlayer victim) {
		for (PlayerKillListener l : List.copyOf(KILLS)) {
			try {
				l.onPlayerKilledPlayer(killer, victim);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] onPlayerKilledPlayer listener failed", e);
			}
		}
	}

	public static void registerEvents() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer victim) || Bots.isBot(victim)) return;
			if (!(source.getEntity() instanceof ServerPlayer killer) || Bots.isBot(killer)) return;
			if (killer.getUUID().equals(victim.getUUID())) return;
			firePlayerKilledPlayer(killer, victim);
		});
	}

	private static <P> Registration add(List<Entry<P>> list, String id, String label, P provider) {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("Hook id missing");
		list.removeIf(e -> e.id().equals(id));
		Entry<P> entry = new Entry<>(id, label == null ? id : label, provider);
		list.add(entry);
		return new Registration(() -> list.remove(entry));
	}

	private static void addPart(List<Part> parts, Entry<?> e, double factor) {
		if (factor != 1.0) parts.add(new Part(e.id(), e.label(), factor));
	}

	@FunctionalInterface
	private interface FactorSupplier {
		double get();
	}

	private static double sanitize(Entry<?> e, FactorSupplier supplier) {
		double v;
		try {
			v = supplier.get();
		} catch (RuntimeException ex) {
			if (WARNED.add(e.id() + ":ex")) SmpEconomy.LOG.error("[SMPEco] Hook {} threw an exception, counts as 1.0", e.id(), ex);
			return 1.0;
		}
		if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) {
			if (WARNED.add(e.id() + ":val")) SmpEconomy.LOG.warn("[SMPEco] Hook {} returned invalid factor {}, counts as 1.0", e.id(), v);
			return 1.0;
		}
		return Math.min(v, MAX_FACTOR);
	}
}
