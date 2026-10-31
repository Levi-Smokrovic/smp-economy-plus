package dev.smpeconomy.core.module;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import dev.smpeconomy.core.CommandRegistry;

/**
 * Records which commands a module registers: SmpEconomy places a marker callback before and after each module's
 * {@code register()}. Fabric runs command callbacks in registration order, so whatever is new in the tree between the two
 * markers belongs to that module. Used by {@code /smpeco module info} and selftests.
 */
public final class ModuleCommands {
	private static final Map<String, Set<String>> ROOTS = new LinkedHashMap<>();
	private static final Map<String, Set<String>> ADMIN = new LinkedHashMap<>();
	private static Set<String> rootsBefore = Set.of();
	private static Set<String> adminBefore = Set.of();

	private ModuleCommands() {}

	public static void begin(String module) {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			rootsBefore = roots(dispatcher);
			adminBefore = adminChildren(dispatcher);
		});
	}

	public static void end(String module) {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			Set<String> roots = new LinkedHashSet<>(roots(dispatcher));
			roots.removeAll(rootsBefore);
			Set<String> admin = new LinkedHashSet<>(adminChildren(dispatcher));
			admin.removeAll(adminBefore);
			ROOTS.put(module, Collections.unmodifiableSet(roots));
			ADMIN.put(module, Collections.unmodifiableSet(admin));
		});
	}

	public static Set<String> roots(String module) {
		return ROOTS.getOrDefault(module, Set.of());
	}

	public static Set<String> adminSubcommands(String module) {
		return ADMIN.getOrDefault(module, Set.of());
	}

	public static Map<String, Set<String>> allRoots() {
		return Collections.unmodifiableMap(ROOTS);
	}

	private static Set<String> roots(CommandDispatcher<CommandSourceStack> dispatcher) {
		Set<String> out = new HashSet<>();
		for (CommandNode<CommandSourceStack> n : dispatcher.getRoot().getChildren()) out.add(n.getName());
		return out;
	}

	private static Set<String> adminChildren(CommandDispatcher<CommandSourceStack> dispatcher) {
		CommandNode<CommandSourceStack> smpeco = dispatcher.getRoot().getChild("smpeco");
		if (smpeco == null) return Set.of();
		Set<String> out = new HashSet<>();
		for (CommandNode<CommandSourceStack> n : smpeco.getChildren()) out.add(n.getName());
		return out;
	}
}
