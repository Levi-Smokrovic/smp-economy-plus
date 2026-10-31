package dev.smpeconomy.core.module;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin plugin of all module mixin configs ({@code smpeconomy.<module>.mixins.json}): mixins of a disabled module are
 * never applied. Reads {@link ModulesFile#readEarly()}, the same state {@link Modules#load()} uses later. The core
 * mixin config has no plugin.
 */
public final class ModuleMixinPlugin implements IMixinConfigPlugin {
	private static final Logger LOG = LoggerFactory.getLogger("SMP Economy+");
	private String module;
	private boolean enabled = true;

	@Override
	public void onLoad(String mixinPackage) {
		module = ModulesFile.moduleOfMixinPackage(mixinPackage);
		if (module == null) {
			LOG.warn("[SMPEco] Mixin package {} belongs to no module, applying mixins", mixinPackage);
			return;
		}
		ModulesFile.Parsed parsed = ModulesFile.readEarly();
		enabled = ModulesFile.resolve(parsed.configured()).isEnabled(module);
		if (!enabled) LOG.info("[SMPEco] module {} is disabled – its mixins are not applied", module);
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!enabled) return false;
		// Optional mod: only apply if the target method really matches, otherwise a startup warning or a crash at class
		// load.
		if (mixinClassName.endsWith(".VeinminerCompatMixin")) return dev.smpeconomy.features.jobs.VeinminerCompat.hookMatches();
		if (mixinClassName.endsWith(".TreeHarvesterCompatMixin")) return classExists("com/natamus/treeharvester_common_fabric/events/TreeCutEvents.class");
		return true;
	}

	/** Asking Mixin about a {@code @Pseudo} target that doesn't exist logs a class load error at startup. */
	private static boolean classExists(String resource) {
		return ModuleMixinPlugin.class.getClassLoader().getResource(resource) != null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
