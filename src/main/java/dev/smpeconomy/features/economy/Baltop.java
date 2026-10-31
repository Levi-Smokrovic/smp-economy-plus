package dev.smpeconomy.features.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Services;

public final class Baltop {
	public static final int PER_PAGE = 10;

	public record Row(UUID id, String name, long balance, long bank, long total) {}

	public record Page(List<Row> rows, int page, int pages, int offset, int total) {}

	private static boolean bankErrorLogged;

	private Baltop() {}

	public static List<Row> live() {
		Optional<Services.Bank> bank = Services.find(Services.Bank.class);
		return rank(EconomyService.top(), bank.<ToLongFunction<UUID>>map(b -> id -> {
			try {
				return b.balance(id);
			} catch (RuntimeException ex) {
				if (!bankErrorLogged) {
					bankErrorLogged = true;
					SmpEconomy.LOG.error("[SMPEco] Bank service threw in /baltop, bank balance counts as 0", ex);
				}
				return 0;
			}
		}).orElse(null));
	}

	public static boolean hasBank() {
		return Services.find(Services.Bank.class).isPresent();
	}

	/**
	 * Descending by total (account + bank), then name ignoring case, then UUID.
	 * @param bank bank balance per player, or null. Errors and negative values count as 0
	 */
	public static List<Row> rank(List<EconomyService.Entry> entries, ToLongFunction<UUID> bank) {
		List<Row> rows = new ArrayList<>(entries.size());
		for (EconomyService.Entry e : entries) {
			long balance = Math.max(0, e.balance());
			long banked = bank == null ? 0 : safeBank(bank, e.id());
			String name = e.name() == null || e.name().isEmpty() ? "Unknown" : e.name();
			rows.add(new Row(e.id(), name, balance, banked, Money.saturatedAdd(balance, banked)));
		}
		rows.sort(Comparator.comparingLong(Row::total).reversed()
			.thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Row::id));
		return rows;
	}

	public static Page page(List<Row> rows, int requested, int perPage) {
		int per = Math.max(1, perPage);
		int pages = Math.max(1, (rows.size() + per - 1) / per);
		int page = Math.max(1, Math.min(requested, pages));
		int from = (page - 1) * per;
		int to = Math.min(rows.size(), from + per);
		return new Page(List.copyOf(rows.subList(Math.min(from, rows.size()), to)), page, pages, from, rows.size());
	}

	public static int rankOf(List<Row> rows, UUID id) {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).id().equals(id)) return i + 1;
		}
		return 0;
	}

	private static long safeBank(ToLongFunction<UUID> bank, UUID id) {
		try {
			long v = bank.applyAsLong(id);
			return Math.max(0, Math.min(v, Money.MAX));
		} catch (RuntimeException ex) {
			return 0; // live() logs bank service errors once
		}
	}
}
