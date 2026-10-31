package dev.smpeconomy.features.sell;

import dev.smpeconomy.core.ConfigManager;

public final class SellConfig {
	/** Window for confirming {@code /sell all}, in seconds. */
	public int confirmSeconds = 10;
	/**
	 * How far the /sell all total may drop between preview and confirm, in percent (a price modifier fading in or
	 * out). A bigger drop or changed amounts give a new preview instead of a sale. A rise sells, since the player
	 * gets more than shown. 0 means every drop needs a new confirm.
	 */
	public double confirmPriceTolerancePercent = 2.0;
	/**
	 * Off by default: items appear from nothing there, so selling would print money. The GUI hands everything back on
	 * close.
	 */
	public boolean allowCreative = false;
	/** Maximum lines in the summary hover. */
	public int summaryHoverLines = 12;

	public static final ConfigManager.Config<SellConfig> CONFIG = ConfigManager.register("sell", SellConfig.class, SellConfig::new, cfg -> {
		if (cfg.confirmSeconds < 1 || cfg.confirmSeconds > 300) throw new IllegalArgumentException("confirmSeconds must be between 1 and 300");
		if (!(cfg.confirmPriceTolerancePercent >= 0 && cfg.confirmPriceTolerancePercent <= 10)) {
			throw new IllegalArgumentException("confirmPriceTolerancePercent must be between 0 and 10");
		}
		if (cfg.summaryHoverLines < 1 || cfg.summaryHoverLines > 50) throw new IllegalArgumentException("summaryHoverLines must be between 1 and 50");
	});

	public static SellConfig get() {
		return CONFIG.get();
	}
}
