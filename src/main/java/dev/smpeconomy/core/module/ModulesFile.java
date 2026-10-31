package dev.smpeconomy.core.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/smpeconomy/modules.json}. Deliberately not a {@code ConfigManager} config and free of Minecraft
 * classes: {@link ModuleMixinPlugin} reads it before the game is loaded, and {@link Modules} then uses exactly this
 * cached result ({@link #readEarly()}), so mixins and registration always agree. Changes take effect after a restart.
 *
 * <p>A missing file gets defaults and is written. A missing key gets that module's default and the file is normalized.
 * Unknown keys stay in the file with one warning. A non-boolean is a reported problem with that module's default.
 * Broken JSON is a reported problem with defaults for this run, and the file is not overwritten.
 */
public final class ModulesFile {
	public static final String FILE_NAME = "modules.json";
	public static final String INFO_KEY = "_info";
	public static final String INFO = "true = enabled, false = disabled. Changes need a server restart. Edit here or in-game with /smpeco admin.";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static Parsed early;

	/**
	 * {@code configured} holds every known module id in catalog order. {@code extra} holds unknown keys, preserved on
	 * write.
	 */
	public record Parsed(Map<String, Boolean> configured, Map<String, JsonElement> extra, List<String> unknownKeys,
			List<String> problems, boolean needsRewrite, boolean broken) {}

	public record Resolution(Set<String> enabled, List<String> problems) {
		public boolean isEnabled(String id) {
			return enabled.contains(id);
		}
	}

	private ModulesFile() {}

	public static Path defaultPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("smpeconomy").resolve(FILE_NAME);
	}

	public static Map<String, Boolean> defaults() {
		Map<String, Boolean> map = new LinkedHashMap<>();
		for (ModuleInfo m : ModuleCatalog.ALL) map.put(m.id(), m.defaultEnabled());
		return map;
	}

	/** Read once per JVM. Writes a missing or incomplete file back normalized, except for broken JSON. */
	public static synchronized Parsed readEarly() {
		if (early == null) {
			Path file = defaultPath();
			early = read(file);
			if (early.needsRewrite() && !early.broken()) {
				try {
					write(file, early.configured(), early.extra());
				} catch (IOException | RuntimeException e) {
					early = withProblem(early, "modules.json could not be written (" + e.getMessage() + ")");
				}
			}
		}
		return early;
	}

	private static Parsed withProblem(Parsed p, String problem) {
		List<String> problems = new ArrayList<>(p.problems());
		problems.add(problem);
		return new Parsed(p.configured(), p.extra(), p.unknownKeys(), List.copyOf(problems), p.needsRewrite(), p.broken());
	}

	/** Never throws. */
	public static Parsed read(Path file) {
		if (!Files.exists(file)) return new Parsed(defaults(), Map.of(), List.of(), List.of(), true, false);
		String json;
		try {
			json = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return new Parsed(defaults(), Map.of(), List.of(), List.of("modules.json is not readable (" + e.getMessage() + ") – defaults are used"), false, true);
		}
		return parse(json);
	}

	public static Parsed parse(String json) {
		JsonObject obj;
		try {
			JsonElement tree = JsonParser.parseString(json);
			if (tree == null || !tree.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
			obj = tree.getAsJsonObject();
		} catch (RuntimeException e) {
			return new Parsed(defaults(), Map.of(), List.of(),
				List.of("modules.json is invalid (" + e.getMessage() + ") – defaults are used for this run, the file is left untouched"), false, true);
		}
		Map<String, Boolean> configured = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();
		boolean rewrite = !obj.has(INFO_KEY);
		for (ModuleInfo m : ModuleCatalog.ALL) {
			JsonElement v = obj.get(m.id());
			if (v == null) {
				configured.put(m.id(), m.defaultEnabled());
				rewrite = true;
			} else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
				configured.put(m.id(), v.getAsBoolean());
			} else {
				configured.put(m.id(), m.defaultEnabled());
				problems.add("modules.json: '" + m.id() + "' must be true or false (is " + v + ") – default ("
					+ m.defaultEnabled() + ") is used");
			}
		}
		Map<String, JsonElement> extra = new LinkedHashMap<>();
		List<String> unknown = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
			if (e.getKey().equals(INFO_KEY) || ModuleCatalog.exists(e.getKey())) continue;
			extra.put(e.getKey(), e.getValue());
			if (!e.getKey().startsWith("_")) unknown.add(e.getKey());
		}
		return new Parsed(Collections.unmodifiableMap(configured), Collections.unmodifiableMap(extra), List.copyOf(unknown),
			List.copyOf(problems), rewrite, false);
	}

	/** Fixed order: {@code _info}, CORE, EXTRA, then unknown keys. */
	public static String toJson(Map<String, Boolean> configured, Map<String, JsonElement> extra) {
		JsonObject obj = new JsonObject();
		obj.add(INFO_KEY, new JsonPrimitive(INFO));
		for (ModuleGroup g : ModuleGroup.values()) {
			for (ModuleInfo m : ModuleCatalog.ALL) {
				if (m.group() != g) continue;
				Boolean v = configured.get(m.id());
				obj.addProperty(m.id(), v != null ? v : m.defaultEnabled());
			}
		}
		if (extra != null) extra.forEach(obj::add);
		return GSON.toJson(obj) + "\n";
	}

	public static void write(Path file, Map<String, Boolean> configured, Map<String, JsonElement> extra) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, toJson(configured, extra), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * A module runs if configured on (or locked) and all {@code requires} run. Repeats until stable so chains work in
	 * any order.
	 */
	public static Resolution resolve(Map<String, Boolean> configured) {
		Set<String> enabled = new LinkedHashSet<>();
		List<String> problems = new ArrayList<>();
		for (ModuleInfo m : ModuleCatalog.ALL) {
			Boolean on = configured.get(m.id());
			if (m.locked()) {
				if (Boolean.FALSE.equals(on)) problems.add("module " + m.id() + " cannot be disabled – it stays enabled");
				enabled.add(m.id());
			} else if (on != null ? on : m.defaultEnabled()) {
				enabled.add(m.id());
			}
		}
		boolean changed = true;
		while (changed) {
			changed = false;
			for (ModuleInfo m : ModuleCatalog.ALL) {
				if (!enabled.contains(m.id())) continue;
				List<String> missing = m.requires().stream().filter(r -> !enabled.contains(r)).toList();
				if (missing.isEmpty()) continue;
				enabled.remove(m.id());
				problems.add("module " + m.id() + " disabled: requires " + String.join(", ", missing));
				changed = true;
			}
		}
		Set<String> ordered = new LinkedHashSet<>();
		for (ModuleInfo m : ModuleCatalog.ALL) if (enabled.contains(m.id())) ordered.add(m.id());
		return new Resolution(Collections.unmodifiableSet(ordered), List.copyOf(problems));
	}

	public static String moduleOfMixinPackage(String mixinPackage) {
		String prefix = "dev.smpeconomy.features.";
		if (mixinPackage == null || !mixinPackage.startsWith(prefix)) return null;
		String rest = mixinPackage.substring(prefix.length());
		if (rest.endsWith(".")) rest = rest.substring(0, rest.length() - 1);
		int dot = rest.indexOf('.');
		String id = dot < 0 ? rest : rest.substring(0, dot);
		return ModuleCatalog.exists(id) ? id : null;
	}
}
