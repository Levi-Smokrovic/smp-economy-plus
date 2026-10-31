package dev.smpeconomy.core;

/**
 * {@code config/smpeconomy/core.json}. Changes apply live through {@code /smpeco reload} or {@code /smpeco admin}.
 * {@link #validate} is the single check for the file, the GUI and {@code /smpeco config set}.
 */
public final class CoreConfig {
	public static final double MIN_MULTIPLIER = 0.1;
	public static final double MAX_MULTIPLIER = 10.0;

	public long startBalance = 0;
	/** Paid in once on first startup. */
	public long treasuryStartCapital = 2_500_000;
	/** NPC spending per rolling hour, as a percentage of the balance. */
	public double treasuryHourlySpendPercent = 5.0;
	/**
	 * Zone for the daily rollover (tasks, /daily, daily offers, bank interest, lotto): a zone id such as
	 * "Europe/Berlin", or "system" for the host's zone.
	 */
	public String timezone = TimeUtil.SYSTEM;
	public Branding branding = new Branding();
	public Currency currency = new Currency();
	public Multipliers multipliers = new Multipliers();

	public static final class Branding {
		/** Section codes allowed. Message text starts gray after this. */
		public String chatPrefix = "§6SMP §8» ";
		public String sidebarTitle = "§6§lSMP Economy";
	}

	public static final class Currency {
		/** E.g. "$", "€", "⛃" or " coins" with {@link #symbolAfter}. */
		public String symbol = "$";
		public boolean symbolAfter = false;
		public String nameSingular = "dollar";
		public String namePlural = "dollars";
	}

	public static final class Multipliers {
		/** PriceGuard caps still apply. */
		public double sell = 1.0;
		public double jobPay = 1.0;
	}

	public static final ConfigManager.Config<CoreConfig> CONFIG = ConfigManager.register("core", CoreConfig.class, CoreConfig::new, cfg -> {
		validate(cfg);
		apply(cfg);
	});

	public static CoreConfig get() {
		return CONFIG.get();
	}

	/** Also fills in missing sub-objects. Throws {@link IllegalArgumentException} with a readable message. */
	public static void validate(CoreConfig cfg) {
		if (cfg.startBalance < 0 || cfg.startBalance > Money.MAX) throw new IllegalArgumentException("startBalance must be between 0 and " + Money.MAX);
		if (cfg.treasuryStartCapital < 0 || cfg.treasuryStartCapital > Money.MAX) throw new IllegalArgumentException("treasuryStartCapital must be between 0 and " + Money.MAX);
		if (!(cfg.treasuryHourlySpendPercent >= 0 && cfg.treasuryHourlySpendPercent <= 100)) throw new IllegalArgumentException("treasuryHourlySpendPercent must be between 0 and 100");
		if (cfg.branding == null) cfg.branding = new Branding();
		if (cfg.currency == null) cfg.currency = new Currency();
		if (cfg.multipliers == null) cfg.multipliers = new Multipliers();
		Branding b = cfg.branding;
		if (b.chatPrefix == null) throw new IllegalArgumentException("branding.chatPrefix is missing");
		if (b.chatPrefix.length() > 64) throw new IllegalArgumentException("branding.chatPrefix is too long (max. 64)");
		if (b.sidebarTitle == null || Msg.stripCodes(b.sidebarTitle).isBlank()) throw new IllegalArgumentException("branding.sidebarTitle is missing");
		if (b.sidebarTitle.length() > 128) throw new IllegalArgumentException("branding.sidebarTitle is too long (max. 128)");
		Currency c = cfg.currency;
		if (c.symbol == null || c.symbol.isBlank()) throw new IllegalArgumentException("currency.symbol is missing");
		if (c.symbol.length() > 12) throw new IllegalArgumentException("currency.symbol is too long (max. 12)");
		if (c.symbol.indexOf('§') >= 0) throw new IllegalArgumentException("currency.symbol must not contain § colour codes");
		if (c.symbol.trim().matches(".*[0-9.,+\\-].*")) throw new IllegalArgumentException("currency.symbol must not contain digits, '.', ',', '+' or '-'");
		if (c.nameSingular == null || c.nameSingular.isBlank() || c.nameSingular.length() > 32) throw new IllegalArgumentException("currency.nameSingular must be 1-32 characters");
		if (c.namePlural == null || c.namePlural.isBlank() || c.namePlural.length() > 32) throw new IllegalArgumentException("currency.namePlural must be 1-32 characters");
		if (cfg.timezone == null || cfg.timezone.isBlank()) cfg.timezone = TimeUtil.SYSTEM;
		TimeUtil.parseZone(cfg.timezone);
		checkMultiplier("multipliers.sell", cfg.multipliers.sell);
		checkMultiplier("multipliers.jobPay", cfg.multipliers.jobPay);
	}

	private static void checkMultiplier(String name, double v) {
		if (!(v >= MIN_MULTIPLIER && v <= MAX_MULTIPLIER)) throw new IllegalArgumentException(name + " must be between " + MIN_MULTIPLIER + " and " + MAX_MULTIPLIER);
	}

	private static void apply(CoreConfig cfg) {
		Money.setCurrency(cfg.currency.symbol, cfg.currency.symbolAfter);
		TimeUtil.setZone(TimeUtil.parseZone(cfg.timezone));
	}

	public static void registerMultipliers() {
		Hooks.addSellMultiplier("server", "Server multiplier", (player, item) -> get().multipliers.sell);
		Hooks.addJobMultiplier("server", "Server multiplier", (player, job) -> get().multipliers.jobPay);
	}

	public static String currencyName(long wholeUnits) {
		Currency c = get().currency;
		return Math.abs(wholeUnits) == 1 ? c.nameSingular : c.namePlural;
	}
}
