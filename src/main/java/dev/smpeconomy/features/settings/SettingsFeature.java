package dev.smpeconomy.features.settings;

import dev.smpeconomy.core.Feature;

/**
 * {@code /settings} GUI over the switches in core {@code Settings}, mixins for "Mob spawning near me"
 * (NaturalSpawner, patrols, sieges, portal piglins) and "Phantoms" (PhantomSpawner; both only if settings.json
 * enables them, off by default), and the private death message with coordinates. Other features read the switches
 * through {@code Settings.get(player, key)}.
 */
public final class SettingsFeature implements Feature {
	@Override
	public String id() {
		return "settings";
	}

	@Override
	public void register() {
		SettingsConfig.bootstrap();
		SettingsCommand.register();
		DeathCoords.register();
		SettingsSelfTests.register();
	}
}
