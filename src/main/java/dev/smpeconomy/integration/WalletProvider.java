package dev.smpeconomy.integration;

import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.CommonEconomy;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;
import eu.pb4.common.economy.api.EconomyTransaction;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
//? if >=26.1 {
/*import java.math.BigInteger;
*///?}

/**
 * Common Economy API view of the player wallets. One raw unit is one cent. Only {@link EconomyService} changes a balance,
 * so its limits and change listeners apply to other mods as well. The bank and system accounts such as the Treasury are
 * not exposed.
 */
final class WalletProvider implements EconomyProvider {
	static final WalletProvider INSTANCE = new WalletProvider();
	static final String ACCOUNT = "wallet";
	static final String CURRENCY_ID = "money";
	static final Currency CURRENCY = new Currency();

	private static final Component NOT_AVAILABLE = Component.literal("The economy is not available right now.");
	private static boolean warnedThread;

	private WalletProvider() {}

	static void register() {
		CommonEconomy.register(SmpEconomy.MOD_ID, INSTANCE);
	}

	@Override
	public Component name() {
		return Component.literal("SMP Economy+");
	}

	@Override
	public EconomyAccount getAccount(MinecraftServer server, GameProfile profile, String accountId) {
		return ACCOUNT.equals(accountId) ? wallet(profile) : null;
	}

	@Override
	public Collection<EconomyAccount> getAccounts(MinecraftServer server, GameProfile profile) {
		Wallet wallet = wallet(profile);
		return wallet == null ? List.of() : List.of(wallet);
	}

	@Override
	public EconomyCurrency getCurrency(MinecraftServer server, String currencyId) {
		return CURRENCY_ID.equals(currencyId) ? CURRENCY : null;
	}

	@Override
	public Collection<EconomyCurrency> getCurrencies(MinecraftServer server) {
		return List.of(CURRENCY);
	}

	@Override
	public String defaultAccount(MinecraftServer server, GameProfile profile, EconomyCurrency currency) {
		return currency == CURRENCY ? ACCOUNT : null;
	}

	private static Wallet wallet(GameProfile profile) {
		if (profile == null || profile.id() == null || EconomyService.isSystemAccount(profile.id())) return null;
		return new Wallet(profile.id(), profile.name());
	}

	/** Account data is not thread-safe; other mods calling from a worker thread get a failed transaction. */
	private static boolean writable() {
		MinecraftServer server = SmpEconomy.server();
		if (server == null || !EconomyService.isLoaded()) return false;
		if (server.isSameThread()) return true;
		if (!warnedThread) {
			warnedThread = true;
			SmpEconomy.LOG.warn("[SMPEco] Another mod changed a balance outside the server thread; refused", new IllegalStateException());
		}
		return false;
	}

	private record Wallet(UUID owner, String ownerName) implements EconomyAccount {
		@Override
		public Component name() {
			return Component.literal("Wallet");
		}

		@Override
		public Identifier id() {
			return Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, ACCOUNT);
		}

		@Override
		public EconomyProvider provider() {
			return INSTANCE;
		}

		@Override
		public EconomyCurrency currency() {
			return CURRENCY;
		}

		private long cents() {
			return EconomyService.isLoaded() ? EconomyService.balance(owner) : 0;
		}

		private EconomyTransaction credit(long amount, boolean apply) {
			long before = cents();
			if (!writable()) return failure(NOT_AVAILABLE, before, amount);
			if (amount < 0 || amount > Money.MAX) return failure(EconomyService.Result.INVALID_AMOUNT, before, amount);
			if (before > Money.MAX - amount) return failure(EconomyService.Result.LIMIT_EXCEEDED, before, amount);
			if (apply && amount > 0) {
				ensureAccount();
				before = cents();
				EconomyService.Result result = EconomyService.deposit(owner, amount);
				if (!result.ok()) return failure(result, before, amount);
			}
			return success(before, amount);
		}

		private EconomyTransaction debit(long amount, boolean apply) {
			long before = cents();
			if (!writable()) return failure(NOT_AVAILABLE, before, -amount);
			if (amount < 0 || amount > Money.MAX) return failure(EconomyService.Result.INVALID_AMOUNT, before, -amount);
			if (before < amount) return failure(EconomyService.Result.INSUFFICIENT_FUNDS, before, -amount);
			if (apply && amount > 0) {
				EconomyService.Result result = EconomyService.withdraw(owner, amount);
				if (!result.ok()) return failure(result, before, -amount);
			}
			return success(before, -amount);
		}

		private void assign(long amount) {
			if (!writable()) return;
			ensureAccount();
			EconomyService.Result result = EconomyService.set(owner, amount);
			if (!result.ok()) SmpEconomy.LOG.warn("[SMPEco] Another mod tried to set the balance of {} to {} cents; refused", ownerName, amount);
		}

		/** An account created through the API starts like one created on first join. */
		private void ensureAccount() {
			if (!EconomyService.hasAccount(owner)) EconomyService.ensureAccount(owner, ownerName);
		}

		private EconomyTransaction failure(EconomyService.Result result, long balance, long amount) {
			return failure(Component.literal(result.message()), balance, amount);
		}

		//? if >=26.1 {
		/*@Override
		public BigInteger balance() {
			return BigInteger.valueOf(cents());
		}

		@Override
		public EconomyTransaction increaseBalance(BigInteger value) {
			return credit(toCents(value), true);
		}

		@Override
		public EconomyTransaction increaseBalance(long value) {
			return credit(value, true);
		}

		@Override
		public EconomyTransaction canIncreaseBalance(BigInteger value) {
			return credit(toCents(value), false);
		}

		@Override
		public EconomyTransaction decreaseBalance(BigInteger value) {
			return debit(toCents(value), true);
		}

		@Override
		public EconomyTransaction decreaseBalance(long value) {
			return debit(value, true);
		}

		@Override
		public EconomyTransaction canDecreaseBalance(BigInteger value) {
			return debit(toCents(value), false);
		}

		@Override
		public void setBalance(BigInteger value) {
			assign(toCents(value));
		}

		private EconomyTransaction failure(Component message, long balance, long amount) {
			return new EconomyTransaction.Simple(false, message, BigInteger.valueOf(balance), BigInteger.valueOf(balance), BigInteger.valueOf(amount), this);
		}

		private EconomyTransaction success(long before, long amount) {
			return new EconomyTransaction.Simple(true, Component.literal("OK"), BigInteger.valueOf(before + amount), BigInteger.valueOf(before), BigInteger.valueOf(amount), this);
		}
		*///?} else {
		@Override
		public long balance() {
			return cents();
		}

		@Override
		public EconomyTransaction increaseBalance(long value) {
			return credit(value, true);
		}

		@Override
		public EconomyTransaction canIncreaseBalance(long value) {
			return credit(value, false);
		}

		@Override
		public EconomyTransaction decreaseBalance(long value) {
			return debit(value, true);
		}

		@Override
		public EconomyTransaction canDecreaseBalance(long value) {
			return debit(value, false);
		}

		@Override
		public void setBalance(long value) {
			assign(value);
		}

		private EconomyTransaction failure(Component message, long balance, long amount) {
			return new EconomyTransaction.Simple(false, message, balance, balance, amount, this);
		}

		private EconomyTransaction success(long before, long amount) {
			return new EconomyTransaction.Simple(true, Component.literal("OK"), before + amount, before, amount, this);
		}
		//?}
	}

	static final class Currency implements EconomyCurrency {
		@Override
		public Component name() {
			return Component.literal("Money");
		}

		@Override
		public Identifier id() {
			return Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, CURRENCY_ID);
		}

		@Override
		public EconomyProvider provider() {
			return INSTANCE;
		}

		private static String format(long cents, boolean precise) {
			return precise ? Money.format(cents) : Money.formatShort(cents);
		}

		private static long parse(String value) {
			return Money.parseAllowZero(value).orElseThrow(() -> new NumberFormatException(value));
		}

		//? if >=26.1 {
		/*@Override
		public String formatValue(BigInteger value, boolean precise) {
			return format(value.bitLength() > 63 ? (value.signum() < 0 ? -Money.MAX : Money.MAX) : value.longValue(), precise);
		}

		@Override
		public BigInteger parseValue(String value) {
			return BigInteger.valueOf(parse(value));
		}
		*///?} else {
		@Override
		public String formatValue(long value, boolean precise) {
			return format(value, precise);
		}

		@Override
		public long parseValue(String value) {
			return parse(value);
		}
		//?}
	}

	//? if >=26.1 {
	/*/^* Too large for a long is too large for any balance: both limits map to values the checks reject. ^/
	private static long toCents(BigInteger value) {
		return value.bitLength() > 63 ? (value.signum() < 0 ? -1 : Long.MAX_VALUE) : value.longValue();
	}
	*///?}
}
