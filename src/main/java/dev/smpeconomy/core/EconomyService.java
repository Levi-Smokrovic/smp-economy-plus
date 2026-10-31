package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import dev.smpeconomy.SmpEconomy;

/**
 * Accounts (UUID to cents) plus the name cache, stored in {@code economy.json}. Operations are all-or-nothing, reject
 * amounts of 0 or less, and keep every balance in {@code [0, Money.MAX]}.
 */
public final class EconomyService {
	/** {@link #message()} is a short error message for players. */
	public enum Result {
		OK("OK"),
		INVALID_AMOUNT("Invalid amount."),
		INSUFFICIENT_FUNDS("Not enough money."),
		LIMIT_EXCEEDED("Balance limit reached."),
		SAME_ACCOUNT("You can't send money to yourself."),
		NO_ACCOUNT("Account not found."),
		/** {@link Treasury} only: the treasury's hourly budget is used up. */
		TREASURY_LIMIT("The Treasury has reached its hourly budget.");

		private final String message;

		Result(String message) {
			this.message = message;
		}

		public boolean ok() {
			return this == OK;
		}

		public String message() {
			return message;
		}
	}

	public record Entry(UUID id, String name, long balance) {}

	@FunctionalInterface
	public interface ChangeListener {
		void onChange(UUID id, long oldBalance, long newBalance);
	}

	static final class Account {
		String name = "";
		long balance;
		long lastSeen;
	}

	static final class Data {
		Map<UUID, Account> accounts = new HashMap<>();
	}

	private static final DataStore<Data> STORE = new DataStore<>("economy.json", Data.class, Data::new);
	private static final Map<String, UUID> BY_NAME = new HashMap<>();
	private static final List<ChangeListener> LISTENERS = new ArrayList<>();
	private static final Map<UUID, String> SYSTEM_ACCOUNTS = new HashMap<>();

	static {
		STORE.onLoad(data -> {
			data.accounts.values().removeIf(a -> a == null);
			data.accounts.values().forEach(a -> {
				if (a.name == null) a.name = "";
				if (a.balance < 0) {
					SmpEconomy.LOG.warn("[SMPEco] Corrected negative balance: {} {}", a.name, a.balance);
					a.balance = 0;
				}
			});
			SYSTEM_ACCOUNTS.forEach(EconomyService::createSystemAccount);
			rebuildNameIndex();
		});
	}

	private EconomyService() {}

	/**
	 * Forces class loading from onInitialize. Otherwise the store would be created on first access, possibly after the
	 * shared load, and be missing from the restore point and startup check.
	 */
	public static void bootstrap() {
	}

	public static boolean isLoaded() {
		return STORE.isLoaded();
	}

	public static boolean hasAccount(UUID id) {
		return accounts().containsKey(id);
	}

	public static long balance(UUID id) {
		Account a = accounts().get(id);
		return a == null ? 0 : a.balance;
	}

	public static boolean has(UUID id, long amount) {
		return amount >= 0 && balance(id) >= amount;
	}

	/** Case-insensitive. With duplicate names, the most recently seen wins. */
	public static Optional<UUID> findByName(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(name.toLowerCase(Locale.ROOT)));
	}

	public static Optional<String> nameOf(UUID id) {
		Account a = accounts().get(id);
		return a == null || a.name.isEmpty() ? Optional.empty() : Optional.of(a.name);
	}

	public static String nameOrUnknown(UUID id) {
		return nameOf(id).orElse("Unknown");
	}

	public static Collection<String> knownNames() {
		List<String> names = new ArrayList<>();
		accounts().forEach((id, a) -> {
			if (!a.name.isEmpty() && !SYSTEM_ACCOUNTS.containsKey(id)) names.add(a.name);
		});
		return names;
	}

	/** Descending by balance, then by name. Test and system accounts are hidden. */
	public static List<Entry> top() {
		List<Entry> list = new ArrayList<>();
		accounts().forEach((id, a) -> {
			if (!SYSTEM_ACCOUNTS.containsKey(id) && !dev.smpeconomy.core.test.TestPlayers.isTestPlayer(id)) list.add(new Entry(id, a.name, a.balance));
		});
		list.sort(Comparator.comparingLong(Entry::balance).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	/** 1-based, 0 without an account. */
	public static int rank(UUID id) {
		List<Entry> top = top();
		for (int i = 0; i < top.size(); i++) {
			if (top.get(i).id().equals(id)) return i + 1;
		}
		return 0;
	}

	public static long totalMoney() {
		long sum = 0;
		for (Account a : accounts().values()) sum = Math.addExact(sum, a.balance);
		return sum;
	}

	public static boolean isSystemAccount(UUID id) {
		return SYSTEM_ACCOUNTS.containsKey(id);
	}

	public static long startBalance() {
		return CoreConfig.get().startBalance;
	}

	/**
	 * Selftest players always start at 0 so selftests can assert exact amounts under any configured
	 * {@code startBalance}.
	 */
	private static long startBalanceFor(UUID id) {
		return dev.smpeconomy.core.test.TestPlayers.isTestPlayer(id) ? 0 : startBalance();
	}

	/**
	 * Registers an account with no player behind it, such as the treasury. It starts at $0, keeps its name and stays
	 * out of {@link #top()}.
	 */
	public static void registerSystemAccount(UUID id, String name) {
		SYSTEM_ACCOUNTS.put(id, name);
		if (STORE.isLoaded()) {
			createSystemAccount(id, name);
			rebuildNameIndex();
		}
	}

	public static void unregisterSystemAccount(UUID id) {
		SYSTEM_ACCOUNTS.remove(id);
	}

	private static void createSystemAccount(UUID id, String name) {
		Account a = accounts().get(id);
		if (a == null) {
			a = new Account();
			accounts().put(id, a);
			STORE.markDirty();
		}
		if (!name.equals(a.name)) {
			a.name = name;
			STORE.markDirty();
		}
	}

	public static void ensureAccount(UUID id, String name) {
		Account a = accounts().get(id);
		if (a == null) {
			a = new Account();
			a.balance = startBalanceFor(id);
			accounts().put(id, a);
			fire(id, 0, a.balance);
		}
		if (name != null && !name.isEmpty()) a.name = name;
		a.lastSeen = System.currentTimeMillis();
		rebuildNameIndex();
		STORE.markDirty();
	}

	public static Result deposit(UUID id, long amount) {
		if (!Money.isValidAmount(amount)) return Result.INVALID_AMOUNT;
		Account a = accounts().get(id);
		long old = a == null ? 0 : a.balance;
		long base = a == null ? startBalanceFor(id) : old;
		if (base > Money.MAX - amount) return Result.LIMIT_EXCEEDED;
		if (a == null) {
			a = new Account();
			accounts().put(id, a);
		}
		a.balance = base + amount;
		STORE.markDirty();
		fire(id, old, a.balance);
		return Result.OK;
	}

	public static Result withdraw(UUID id, long amount) {
		if (!Money.isValidAmount(amount)) return Result.INVALID_AMOUNT;
		Account a = accounts().get(id);
		if (a == null) return Result.INSUFFICIENT_FUNDS;
		if (a.balance < amount) return Result.INSUFFICIENT_FUNDS;
		long old = a.balance;
		a.balance = old - amount;
		STORE.markDirty();
		fire(id, old, a.balance);
		return Result.OK;
	}

	/** Checks everything first, then changes both accounts or neither. The recipient needs an account. */
	public static Result transfer(UUID from, UUID to, long amount) {
		if (!Money.isValidAmount(amount)) return Result.INVALID_AMOUNT;
		if (from.equals(to)) return Result.SAME_ACCOUNT;
		Account src = accounts().get(from);
		Account dst = accounts().get(to);
		if (dst == null) return Result.NO_ACCOUNT;
		if (src == null || src.balance < amount) return Result.INSUFFICIENT_FUNDS;
		if (dst.balance > Money.MAX - amount) return Result.LIMIT_EXCEEDED;
		long oldSrc = src.balance;
		long oldDst = dst.balance;
		src.balance = oldSrc - amount;
		dst.balance = oldDst + amount;
		STORE.markDirty();
		fire(from, oldSrc, src.balance);
		fire(to, oldDst, dst.balance);
		return Result.OK;
	}

	public static Result set(UUID id, long amount) {
		if (amount < 0 || amount > Money.MAX) return Result.INVALID_AMOUNT;
		Account a = accounts().computeIfAbsent(id, k -> new Account());
		long old = a.balance;
		a.balance = amount;
		STORE.markDirty();
		fire(id, old, amount);
		return Result.OK;
	}

	public static void deleteAccount(UUID id) {
		Account removed = accounts().remove(id);
		if (removed != null) {
			rebuildNameIndex();
			STORE.markDirty();
			fire(id, removed.balance, 0);
		}
	}

	public static void addListener(ChangeListener listener) {
		LISTENERS.add(listener);
	}

	private static Map<UUID, Account> accounts() {
		return STORE.get().accounts;
	}

	private static void fire(UUID id, long oldBalance, long newBalance) {
		if (oldBalance == newBalance) return;
		for (ChangeListener l : LISTENERS) {
			try {
				l.onChange(id, oldBalance, newBalance);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Economy listener failed", e);
			}
		}
	}

	private static void rebuildNameIndex() {
		BY_NAME.clear();
		Map<String, Long> seen = new HashMap<>();
		accounts().forEach((id, a) -> {
			if (a.name.isEmpty()) return;
			String key = a.name.toLowerCase(Locale.ROOT);
			Long prev = seen.get(key);
			if (prev == null || a.lastSeen > prev) {
				seen.put(key, a.lastSeen);
				BY_NAME.put(key, id);
			}
		});
	}
}
