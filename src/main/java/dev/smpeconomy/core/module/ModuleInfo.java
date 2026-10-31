package dev.smpeconomy.core.module;

import java.util.List;

/**
 * Module description. Plain data with no Minecraft classes because the mixin plugin reads it before the game is loaded.
 *
 * @param id module id = feature id = key in {@code modules.json} = package {@code dev.smpeconomy.features.<id>}
 * @param locked cannot be turned off (economy)
 * @param requires hard dependencies: the module is turned off at startup without them
 * @param uses soft dependencies: only a hint in the GUI
 * @param commands root commands; a selftest checks a disabled module registers none of them
 */
public record ModuleInfo(String id, String name, String description, String icon, ModuleGroup group, boolean locked,
		List<String> requires, List<String> uses, List<String> commands) {

	public ModuleInfo {
		requires = List.copyOf(requires);
		uses = List.copyOf(uses);
		commands = List.copyOf(commands);
	}

	public boolean defaultEnabled() {
		return group == ModuleGroup.CORE;
	}
}
