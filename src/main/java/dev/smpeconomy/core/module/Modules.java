package dev.smpeconomy.core.module;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import dev.smpeconomy.SmpEconomy;

/**
 * Module switches at runtime. {@link #isEnabled} tells whether a module runs in this server run, fixed from startup.
 * {@link #isConfigured} is the file's state, which takes effect after a restart. {@link #setConfigured} checks
 * dependencies, writes {@code modules.json} and logs.
 */
public final class Modules {
	private static ModulesFile.Resolution running;
	private static Map<String, Boolean> configured;
	private static Map<String, JsonElement> extra = Map.of();
	private static List<String> problems = List.of();
	private static Path file;

	private Modules() {}

	/** Cached, the same state as in the mixin plugin. Idempotent. */
	public static synchronized void load() {
		if (running != null) return;
		ModulesFile.Parsed parsed = ModulesFile.readEarly();
		file = ModulesFile.defaultPath();
		configured = new LinkedHashMap<>(parsed.configured());
		extra = parsed.extra();
		running = ModulesFile.resolve(configured);
		List<String> all = new ArrayList<>(parsed.problems());
		all.addAll(running.problems());
		problems = List.copyOf(all);
		for (String p : parsed.problems()) SmpEconomy.LOG.error("[SMPEco] {}", p);
		for (String p : running.problems()) SmpEconomy.LOG.error("[SMPEco] {} (config/smpeconomy/modules.json or /smpeco admin)", p);
		for (String k : parsed.unknownKeys()) SmpEconomy.LOG.warn("[SMPEco] modules.json: unknown module '{}' – ignored (kept in the file)", k);
		List<String> off = new ArrayList<>();
		for (ModuleInfo m : ModuleCatalog.ALL) if (!running.isEnabled(m.id())) off.add(m.id());
		SmpEconomy.LOG.info("[SMPEco] modules enabled ({}): {}", running.enabled().size(), String.join(", ", running.enabled()));
		SmpEconomy.LOG.info("[SMPEco] modules disabled ({}): {}", off.size(), off.isEmpty() ? "-" : String.join(", ", off));
	}

	private static void ensureLoaded() {
		if (running == null) load();
	}

	public static boolean isEnabled(String id) {
		ensureLoaded();
		return running.isEnabled(id);
	}

	public static synchronized boolean isConfigured(String id) {
		ensureLoaded();
		return Boolean.TRUE.equals(configured.get(id)) || ModuleCatalog.get(id).map(ModuleInfo::locked).orElse(false);
	}

	public static Set<String> enabled() {
		ensureLoaded();
		return running.enabled();
	}

	public static List<ModuleInfo> all() {
		return ModuleCatalog.ALL;
	}

	public static Optional<ModuleInfo> info(String id) {
		return ModuleCatalog.get(id);
	}

	/**
	 * The module id if this root command belongs to a disabled module per the catalog and no running module registers
	 * it. Lets menus and tests say "missing because disabled" rather than "broken".
	 */
	public static Optional<String> disabledOwnerOf(String rootCommand) {
		ensureLoaded();
		for (String id : running.enabled()) {
			if (ModuleCommands.roots(id).contains(rootCommand)) return Optional.empty();
		}
		for (ModuleInfo m : ModuleCatalog.ALL) {
			if (!running.isEnabled(m.id()) && m.commands().contains(rootCommand)) return Optional.of(m.id());
		}
		return Optional.empty();
	}

	public static List<String> problems() {
		ensureLoaded();
		return problems;
	}

	public static synchronized boolean restartPending() {
		return !pendingChanges().isEmpty();
	}

	public static synchronized List<String> pendingChanges() {
		ensureLoaded();
		Map<String, Boolean> after = new LinkedHashMap<>(configured);
		ModulesFile.Resolution next = ModulesFile.resolve(after);
		List<String> out = new ArrayList<>();
		for (ModuleInfo m : ModuleCatalog.ALL) {
			boolean now = running.isEnabled(m.id());
			boolean later = next.isEnabled(m.id());
			if (now != later) out.add(m.id() + ": " + (now ? "on" : "off") + " → " + (later ? "on" : "off"));
		}
		return out;
	}

	public record Result(boolean ok, String message, List<String> changed) {
		static Result error(String message) {
			return new Result(false, message, List.of());
		}
	}

	/**
	 * Takes effect after a restart. Enabling requires all {@code requires} configured on; disabling requires no
	 * configured-on module to need it. {@code withDeps} toggles those modules too rather than refusing.
	 */
	public static synchronized Result setConfigured(String id, boolean on, boolean withDeps, String actor) {
		ensureLoaded();
		Optional<ModuleInfo> info = ModuleCatalog.get(id);
		if (info.isEmpty()) return Result.error("Unknown module '" + id + "'.");
		if (info.get().locked() && !on) return Result.error(info.get().name() + " is the base of the mod and cannot be disabled.");
		Map<String, Boolean> next = new LinkedHashMap<>(configured);
		Set<String> touched = new LinkedHashSet<>();
		if (on) {
			List<String> missing = missingRequirements(id, next);
			if (!missing.isEmpty() && !withDeps) {
				return Result.error(id + " requires " + String.join(", ", missing) + " – enable " + (missing.size() == 1 ? "it" : "them")
					+ " first (or enable with dependencies).");
			}
			enableWithRequirements(id, next, touched);
		} else {
			List<String> dependents = configuredDependents(id, next);
			if (!dependents.isEmpty() && !withDeps) {
				return Result.error(String.join(", ", dependents) + (dependents.size() == 1 ? " needs " : " need ") + id
					+ " – disable " + (dependents.size() == 1 ? "it" : "them") + " first (or disable with dependents).");
			}
			disableWithDependents(id, next, touched);
		}
		touched.removeIf(m -> Boolean.valueOf(on).equals(configured.get(m)));
		if (touched.isEmpty()) {
			return new Result(true, id + " is already " + (on ? "enabled" : "disabled") + " in modules.json"
				+ (isEnabled(id) == on ? "." : " (restart to apply)."), List.of());
		}
		try {
			ModulesFile.write(file, next, extra);
		} catch (IOException | RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] modules.json not written", e);
			return Result.error("modules.json could not be written: " + e.getMessage());
		}
		configured = next;
		List<String> changed = List.copyOf(touched);
		SmpEconomy.LOG.info("[SMPEco] modules.json: {} {} by {} (restart required)", String.join(", ", changed), on ? "enabled" : "disabled", actor);
		boolean pending = restartPending();
		return new Result(true, String.join(", ", changed) + (on ? " enabled" : " disabled")
			+ (pending ? " – restart the server to apply." : " – no restart needed (matches the running state)."), changed);
	}

	public static synchronized Path file() {
		ensureLoaded();
		return file;
	}

	/** Redirects the file path and replaces the file state. The return value restores the old state. */
	public static synchronized Runnable overrideFileForTest(Path testFile, Map<String, Boolean> state) {
		ensureLoaded();
		Path oldFile = file;
		Map<String, Boolean> oldConfigured = configured;
		file = testFile;
		configured = new LinkedHashMap<>(state);
		return () -> {
			synchronized (Modules.class) {
				file = oldFile;
				configured = oldConfigured;
			}
		};
	}

	public static synchronized Map<String, Boolean> configuredState() {
		ensureLoaded();
		return new LinkedHashMap<>(configured);
	}

	private static List<String> missingRequirements(String id, Map<String, Boolean> state) {
		ModuleInfo m = ModuleCatalog.get(id).orElseThrow();
		List<String> out = new ArrayList<>();
		for (String r : m.requires()) {
			ModuleInfo req = ModuleCatalog.get(r).orElseThrow();
			if (!req.locked() && !Boolean.TRUE.equals(state.get(r))) out.add(r);
		}
		return out;
	}

	private static List<String> configuredDependents(String id, Map<String, Boolean> state) {
		List<String> out = new ArrayList<>();
		for (ModuleInfo d : ModuleCatalog.dependents(id)) if (Boolean.TRUE.equals(state.get(d.id()))) out.add(d.id());
		return out;
	}

	private static void enableWithRequirements(String id, Map<String, Boolean> state, Set<String> touched) {
		if (Boolean.TRUE.equals(state.get(id)) && touched.contains(id)) return;
		state.put(id, true);
		touched.add(id);
		for (String r : ModuleCatalog.get(id).orElseThrow().requires()) {
			if (!Boolean.TRUE.equals(state.get(r))) enableWithRequirements(r, state, touched);
		}
	}

	private static void disableWithDependents(String id, Map<String, Boolean> state, Set<String> touched) {
		state.put(id, false);
		touched.add(id);
		for (ModuleInfo d : ModuleCatalog.dependents(id)) {
			if (Boolean.TRUE.equals(state.get(d.id()))) disableWithDependents(d.id(), state, touched);
		}
	}
}
