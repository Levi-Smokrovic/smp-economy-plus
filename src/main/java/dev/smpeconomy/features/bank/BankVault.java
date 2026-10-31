package dev.smpeconomy.features.bank;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import dev.smpeconomy.core.EconomyService;

/**
 * Each player's bank balance is a hidden system account ("shadow account") in economy.json, with a UUID derived from
 * the player UUID. Cash and bank balance sharing one file means deposits, withdrawals and interest are all
 * single-file operations: a crash between files or a bank.json loaded from backup cannot double or destroy money.
 * bank.json only holds deposit lots and interest metadata.
 * <p>Shadow accounts are registered as nameless system accounts: not in {@code /baltop}, not in {@link
 * EconomyService#knownNames()}, not reachable by name through {@code /pay} or {@code /eco}.
 */
final class BankVault {
	static final BankVault LIVE = new BankVault("smpeco-bank:", false);

	private final String namespace;
	private final boolean track;
	private final Set<UUID> created = new HashSet<>();

	private BankVault(String namespace, boolean track) {
		this.namespace = namespace;
		this.track = track;
	}

	/** Separate namespace for selftests; {@link #deleteAll()} removes everything in it. */
	static BankVault forTest(String name) {
		return new BankVault("smpeco-bank-selftest:" + name + ":", true);
	}

	UUID id(UUID player) {
		return UUID.nameUUIDFromBytes((namespace + player).getBytes(StandardCharsets.UTF_8));
	}

	long balance(UUID player) {
		return EconomyService.balance(id(player));
	}

	boolean exists(UUID player) {
		return EconomyService.hasAccount(id(player));
	}

	/** Returns null when no account could be created because economy.json isn't loaded. */
	UUID ensure(UUID player) {
		UUID id = id(player);
		if (!EconomyService.isSystemAccount(id) || !EconomyService.hasAccount(id)) {
			EconomyService.registerSystemAccount(id, "");
			if (track) created.add(player);
		}
		return EconomyService.hasAccount(id) ? id : null;
	}

	boolean registerIfExists(UUID player) {
		UUID id = id(player);
		if (!EconomyService.hasAccount(id)) return false;
		if (!EconomyService.isSystemAccount(id)) EconomyService.registerSystemAccount(id, "");
		if (track) created.add(player);
		return true;
	}

	void delete(UUID player) {
		UUID id = id(player);
		EconomyService.deleteAccount(id);
		EconomyService.unregisterSystemAccount(id);
		created.remove(player);
	}

	void deleteAll() {
		for (UUID player : List.copyOf(created)) delete(player);
	}
}
