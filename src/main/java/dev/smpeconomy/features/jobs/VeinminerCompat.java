package dev.smpeconomy.features.jobs;

import java.io.IOException;
import java.io.InputStream;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Which path Veinminer breaks take. In 2.11 and older {@code VeinMinerEvent#destroyBlock} breaks follow-up blocks itself
 * without {@code PlayerBlockBreakEvents}, which is where {@code mixin.VeinminerCompatMixin} hooks in. From 2.12 the
 * method is gone and follow-up blocks go through {@code ServerPlayerGameMode#destroyBlock} and the normal break events.
 * <p>Loaded from the mixin plugin, so it must not touch vanilla classes; it only reads class bytes.
 */
public final class VeinminerCompat {
	private static final String TARGET = "de/miraculixx/veinminer/event/VeinMinerEvent";
	private static final String MIXIN = "dev/smpeconomy/features/jobs/mixin/VeinminerCompatMixin";
	private static final String TARGET_METHOD = "destroyBlock";
	private static final String HANDLER = "smpeco$onVeinmined";
	private static final String NESTED_TARGET_METHOD = "onBlockBreakBefore";
	private static final String NESTED_HANDLER = "smpeco$noNestedVein";

	private VeinminerCompat() {}

	/**
	 * Do the target methods exist with the mixin handlers' parameters? Without a match the mixin must not apply: a
	 * same-named method with a different signature would crash the server at class load ({@code require = 0} only
	 * covers a missing method). Both sides come from class bytes, so they agree under any mapping.
	 */
	public static boolean hookMatches() {
		ClassNode target = read(TARGET);
		ClassNode mixin = read(MIXIN);
		if (target == null || mixin == null) return false;
		return matches(mixin, HANDLER, target, TARGET_METHOD, Type.VOID_TYPE)
			&& matches(mixin, NESTED_HANDLER, target, NESTED_TARGET_METHOD, Type.BOOLEAN_TYPE);
	}

	private static boolean matches(ClassNode mixin, String handler, ClassNode target, String method, Type returnType) {
		for (MethodNode h : mixin.methods) {
			if (!h.name.equals(handler)) continue;
			String wanted = targetDescriptor(h.desc, returnType);
			for (MethodNode m : target.methods) {
				if (m.name.equals(method) && m.desc.equals(wanted)) return true;
			}
		}
		return false;
	}

	/** For logs and the selftest; null without Veinminer. */
	public static String describe() {
		if (!FabricLoader.getInstance().isModLoaded("veinminer")) return null;
		return hookMatches() ? "mixin hook on VeinMinerEvent#destroyBlock" : "per-block break events (no matching VeinMinerEvent#destroyBlock)";
	}

	/** Handler parameters without the trailing {@code CallbackInfo}. */
	static String targetDescriptor(String handlerDescriptor, Type returnType) {
		Type[] args = Type.getArgumentTypes(handlerDescriptor);
		Type[] params = new Type[args.length - 1];
		System.arraycopy(args, 0, params, 0, params.length);
		return Type.getMethodDescriptor(returnType, params);
	}

	private static ClassNode read(String internalName) {
		try (InputStream in = VeinminerCompat.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
			if (in == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
