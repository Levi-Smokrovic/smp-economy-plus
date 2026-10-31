package dev.smpeconomy.integration;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.CommonEconomy;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyTransaction;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
//? if >=26.1 {
/*import java.math.BigInteger;
*///?}

final class WalletProviderSelfTests {
	private WalletProviderSelfTests() {}

	static void register() {
		SelfTests.register(Integrations.FEATURE, "economy-api-registered", WalletProviderSelfTests::registered);
		SelfTests.register(Integrations.FEATURE, "economy-api-moves-money", WalletProviderSelfTests::movesMoney);
		SelfTests.register(Integrations.FEATURE, "economy-api-refuses", WalletProviderSelfTests::refuses);
		SelfTests.register(Integrations.FEATURE, "economy-api-listeners", WalletProviderSelfTests::listeners);
		SelfTests.register(Integrations.FEATURE, "economy-api-format", WalletProviderSelfTests::format);
	}

	private static EconomyAccount account(TestContext ctx, String name) {
		var player = ctx.player(name);
		EconomyService.deleteAccount(player.getUUID());
		EconomyService.ensureAccount(player.getUUID(), name);
		return CommonEconomy.getAccount(player, Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, WalletProvider.ACCOUNT));
	}

	private static long cents(EconomyAccount account) {
		return raw(account.balance());
	}

	//? if >=26.1 {
	/*private static long raw(BigInteger value) {
		return value.longValueExact();
	}
	*///?} else {
	private static long raw(long value) {
		return value;
	}
	//?}

	private static void registered(TestContext ctx) {
		isTrue(CommonEconomy.providers().contains(WalletProvider.INSTANCE), "provider listed");
		EconomyCurrency currency = CommonEconomy.getCurrency(ctx.server(), Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, WalletProvider.CURRENCY_ID));
		isTrue(currency == WalletProvider.CURRENCY, "currency by id");
		var player = ctx.player("ApiReg");
		eq(1, CommonEconomy.getAccounts(player, currency).size(), "one account per player");
		var byDefault = WalletProvider.INSTANCE.getDefaultAccount(player, currency);
		eq(player.getUUID(), byDefault.owner(), "default account owner");
	}

	private static void movesMoney(TestContext ctx) {
		EconomyAccount a = account(ctx, "ApiMove");
		UUID id = a.owner();
		EconomyTransaction in = a.increaseBalance(12_345L);
		isTrue(in.isSuccessful(), "credit");
		eq(12_345L, EconomyService.balance(id), "credit reaches the wallet");
		eq(12_345L, raw(in.finalBalance()), "final after credit");
		eq(12_345L, raw(in.transactionAmount()), "credit amount");

		EconomyTransaction probe = a.canDecreaseBalance(345L);
		isTrue(probe.isSuccessful(), "probe");
		eq(12_000L, raw(probe.finalBalance()), "probe predicts the balance");
		eq(12_345L, EconomyService.balance(id), "probe changes nothing");

		EconomyTransaction out = a.decreaseBalance(345L);
		isTrue(out.isSuccessful(), "debit");
		eq(12_000L, cents(a), "balance after debit");
		eq(-345L, raw(out.transactionAmount()), "debit amount is negative");

		isTrue(a.increaseBalance(0L).isSuccessful(), "zero is a no-op");
		eq(12_000L, cents(a), "zero changes nothing");
		a.setBalance(5L);
		eq(5L, EconomyService.balance(id), "set");
	}

	private static void refuses(TestContext ctx) {
		ctx.preserveTreasury();
		EconomyAccount a = account(ctx, "ApiRefuse");
		UUID id = a.owner();
		EconomyService.set(id, 100);
		EconomyTransaction tooMuch = a.decreaseBalance(101L);
		isFalse(tooMuch.isSuccessful(), "no overdraft");
		eq(100L, raw(tooMuch.finalBalance()), "failed final equals previous");
		eq(100L, EconomyService.balance(id), "unchanged after overdraft");
		isFalse(a.decreaseBalance(-1L).isSuccessful(), "negative debit would be a credit");
		isFalse(a.increaseBalance(-1L).isSuccessful(), "negative credit would be a debit");
		a.setBalance(-1L);
		eq(100L, EconomyService.balance(id), "set negative refused");

		EconomyService.set(id, Money.MAX);
		isFalse(a.increaseBalance(1L).isSuccessful(), "balance limit");
		eq(Money.MAX, EconomyService.balance(id), "unchanged at limit");
		a.setBalance(Money.MAX + 1);
		eq(Money.MAX, EconomyService.balance(id), "set above limit refused");
		isFalse(a.decreaseBalance(Long.MAX_VALUE).isSuccessful(), "amount above limit");
		//? if >=26.1 {
		/*isFalse(a.increaseBalance(BigInteger.TWO.pow(70)).isSuccessful(), "amount beyond long");
		isFalse(a.decreaseBalance(BigInteger.TWO.pow(70).negate()).isSuccessful(), "negative amount beyond long");
		*///?}

		GameProfile treasury = new GameProfile(Treasury.ID, Treasury.NAME);
		eq(null, WalletProvider.INSTANCE.getAccount(ctx.server(), treasury, WalletProvider.ACCOUNT), "no treasury access");
		isTrue(WalletProvider.INSTANCE.getAccounts(ctx.server(), treasury).isEmpty(), "no treasury account listed");
		eq(null, WalletProvider.INSTANCE.getAccount(ctx.server(), new GameProfile(id, "ApiRefuse"), "bank"), "only the wallet");
	}

	private static void listeners(TestContext ctx) {
		EconomyAccount a = account(ctx, "ApiListen");
		long[] last = {-1};
		// listeners cannot be removed: the guard keeps this one quiet after the test
		boolean[] active = {true};
		EconomyService.addListener((id, before, after) -> {
			if (active[0] && id.equals(a.owner())) last[0] = after;
		});
		ctx.cleanup(() -> active[0] = false);
		a.increaseBalance(777L);
		eq(777L, last[0], "change listeners see API changes");
	}

	private static void format(TestContext ctx) {
		EconomyCurrency c = WalletProvider.CURRENCY;
		eq(Money.format(123_456), c.formatValue(123_456L, true), "precise");
		eq(Money.formatShort(123_456), c.formatValue(123_456L, false), "short");
		eq(123_456L, raw(c.parseValue(c.formatValue(123_456L, true))), "round trip");
		eq(0L, raw(c.parseValue(c.formatValue(0L, true))), "zero round trip");
		boolean refused = false;
		try {
			c.parseValue("1.005");
		} catch (NumberFormatException e) {
			refused = true;
		}
		isTrue(refused, "fractions of a cent are refused, not rounded");
	}
}
