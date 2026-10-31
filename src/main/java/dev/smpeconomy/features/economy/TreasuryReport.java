package dev.smpeconomy.features.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import dev.smpeconomy.core.CoreConfig;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Treasury;

public record TreasuryReport(long balance, long maxPerHour, long allowance, long spentLastHour, double capPercent,
	Map<String, Treasury.Flow> flows24h, Treasury.Flow totals24h, Map<String, Treasury.Flow> allTime) {

	public static final int MAX_REASON_LINES = 8;

	public static TreasuryReport live() {
		return new TreasuryReport(Treasury.balance(), Treasury.maxSpendPerHour(), Treasury.allowance(), Treasury.spentLastHour(),
			CoreConfig.get().treasuryHourlySpendPercent, Treasury.stats24h(), Treasury.totals24h(), Treasury.totalsAllTime());
	}

	public static TreasuryReport of(Treasury.Ledger ledger, long now, double capPercent) {
		return new TreasuryReport(ledger.balance(), ledger.hourlyCap(now), ledger.allowance(now), ledger.spentLastHour(now),
			capPercent, ledger.stats24h(now), ledger.totals24h(now), ledger.totalsAllTime());
	}

	public int usedPercent() {
		if (spentLastHour <= 0) return 0;
		if (maxPerHour <= 0) return 100;
		// amounts <= Money.MAX (1e15), so × 100 fits in a long
		long pct = Math.min(spentLastHour, Money.MAX) * 100 / maxPerHour;
		return (int) Math.max(0, Math.min(100, pct));
	}

	public Treasury.Flow allTimeTotal() {
		Treasury.Flow sum = Treasury.Flow.ZERO;
		for (Treasury.Flow f : allTime.values()) sum = sum.plus(f);
		return sum;
	}

	public List<Map.Entry<String, Treasury.Flow>> topReasons24h() {
		List<Map.Entry<String, Treasury.Flow>> list = new ArrayList<>(flows24h.entrySet());
		list.removeIf(e -> e.getValue().income() == 0 && e.getValue().expense() == 0);
		list.sort(Comparator.<Map.Entry<String, Treasury.Flow>>comparingLong(e -> -volume(e.getValue())).thenComparing(Map.Entry::getKey));
		return list.size() > MAX_REASON_LINES ? list.subList(0, MAX_REASON_LINES) : list;
	}

	private static long volume(Treasury.Flow f) {
		long r = f.income() + f.expense();
		return r < 0 ? Long.MAX_VALUE : r;
	}

	public List<String> publicLines() {
		List<String> lines = new ArrayList<>();
		lines.add("§6§lTreasury");
		lines.add("§7Balance: §a" + Money.format(balance));
		lines.add("§7NPC budget per hour: §e" + Money.format(maxPerHour) + " §8(" + percent(capPercent) + " of the Treasury)");
		lines.add("§7Spent last hour: §c" + Money.format(spentLastHour) + " §8(" + usedPercent() + "%) §7· left: §a" + Money.format(allowance));
		lines.add("§7Last 24h: §a+" + Money.format(totals24h.income()) + " §7income · §c-" + Money.format(totals24h.expense())
			+ " §7spent · net " + signed(totals24h.net()));
		for (Map.Entry<String, Treasury.Flow> e : topReasons24h()) lines.add(reasonLine(e.getKey(), e.getValue()));
		return lines;
	}

	public List<String> adminLines() {
		List<String> lines = publicLines();
		Treasury.Flow all = allTimeTotal();
		lines.add("§7All time: §a+" + Money.format(all.income()) + " §7· §c-" + Money.format(all.expense()) + " §7· net " + signed(all.net()));
		lines.add("§8balance=" + balance + " allowance=" + allowance + " spentLastHour=" + spentLastHour + " maxPerHour=" + maxPerHour);
		lines.add("§8Admin: §7/eco treasury deposit <amount> §8· §7/eco treasury withdraw <amount>");
		return lines;
	}

	static String reasonLine(String reason, Treasury.Flow f) {
		StringBuilder sb = new StringBuilder("§8 • §7").append(Treasury.reasonLabel(reason)).append(": ");
		if (f.income() > 0) sb.append("§a+").append(Money.format(f.income()));
		if (f.income() > 0 && f.expense() > 0) sb.append(" §7/ ");
		if (f.expense() > 0) sb.append("§c-").append(Money.format(f.expense()));
		return sb.toString();
	}

	static String signed(long cents) {
		if (cents > 0) return "§a+" + Money.format(cents);
		if (cents < 0) return "§c" + Money.format(cents);
		return "§7" + Money.format(0);
	}

	static String percent(double pct) {
		if (pct == Math.rint(pct)) return String.format(Locale.ROOT, "%d%%", (long) pct);
		return String.format(Locale.ROOT, "%.1f%%", pct);
	}
}
