package dev.smpeconomy.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import dev.smpeconomy.SmpEconomy;

/**
 * Configs under {@code config/smpeconomy/<name>.json}.
 *
 * <p>With code defaults ({@link #register}), a missing file gets the defaults written and missing top-level keys are
 * filled in after an update. With bundled defaults ({@link #registerResource}), a missing file is copied verbatim from
 * the jar and nothing is ever filled in, so deliberately deleted prices stay deleted.
 *
 * <p>Broken JSON never overwrites the file. Each successful load copies the file to {@code lastgood/<name>.json}. If
 * the file is invalid at startup the last good copy applies, never the defaults: a typo must not bring back removed
 * prices or a disabled casino. Without a last good copy, startup aborts. On reload the old value stays active. Admins
 * see these problems on join and in {@code /smpeco status} ({@link #problems()}).
 *
 * <p>Read {@code CONFIG.get()} every time rather than caching it, because {@code /smpeco reload} replaces the object.
 */
public final class ConfigManager {
	private static final List<Config<?>> DEFERRED = new ArrayList<>();
	public static final String DEFAULTS_DIR = "smpeconomy_defaults/";
	public static final String LASTGOOD_DIR = "lastgood";
	private static final List<Config<?>> ALL = new ArrayList<>();

	private ConfigManager() {}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("smpeconomy");
	}

	public static <T> Config<T> register(String name, Class<T> type, Supplier<T> defaults) {
		return register(name, type, defaults, null);
	}

	/**
	 * {@code onLoad} runs after every successful load. If it throws, the load counts as failed and the old value stays,
	 * which makes it suitable for validation.
	 */
	public static <T> Config<T> register(String name, Class<T> type, Supplier<T> defaults, Consumer<T> onLoad) {
		return register(name, (Type) type, defaults, onLoad);
	}

	public static <T> Config<T> register(String name, Type type, Supplier<T> defaults, Consumer<T> onLoad) {
		return add(new Config<>(name, type, defaults, onLoad, null));
	}

	public static <T> Config<T> registerResource(String name, Type type, String resourceFile, Consumer<T> onLoad) {
		String resource = DEFAULTS_DIR + resourceFile;
		Supplier<T> defaults = () -> {
			String json = readBundled(resource).orElseThrow(() -> new IllegalStateException("Bundled file missing: " + resource));
			T value = DataStore.gson().fromJson(json, type);
			if (value == null) throw new IllegalStateException("Bundled file is empty: " + resource);
			return value;
		};
		return add(new Config<>(name, type, defaults, onLoad, resource));
	}

	private static <T> Config<T> add(Config<T> config) {
		ALL.add(config);
		config.initialLoad(SmpEconomy.server() == null);
		return config;
	}

	/** Not part of {@link #all()}/{@link #reloadAll()}. Used by selftests. */
	public static <T> Config<T> unmanaged(String name, Class<T> type, Supplier<T> defaults, Consumer<T> onLoad) {
		return new Config<>(name, type, defaults, onLoad, null);
	}

	public static List<String> problems() {
		List<String> out = new ArrayList<>();
		for (Config<?> c : ALL) {
			if (c.problem != null) out.add(c.problem);
		}
		return out;
	}

	public static List<String> reloadAll() {
		List<String> errors = new ArrayList<>();
		for (Config<?> c : ALL) {
			String error = c.reload();
			if (error != null) errors.add(c.name + ": " + error);
		}
		return errors;
	}

	/**
	 * Runs at SERVER_STARTING, when every mod has registered its items. Configs read during mod init may have missed ids
	 * from mods that initialized later. Without modded items there is nothing to retry except a failed file.
	 */
	public static void retryAfterAllModsInitialized(boolean moddedItemsPresent) {
		if (moddedItemsPresent) {
			for (String error : reloadAll()) SmpEconomy.LOG.error("[SMPEco] Config {}", error);
		}
		for (Config<?> c : List.copyOf(DEFERRED)) {
			String error = c.reload();
			if (error != null) {
				throw new IllegalStateException("[SMPEco] Config " + c.name + ".json is invalid (" + error
					+ ") and there is no usable last good version. Fix or delete the file, then restart.");
			}
		}
		DEFERRED.clear();
	}

	public static List<Config<?>> all() {
		return List.copyOf(ALL);
	}

	public static Optional<String> readBundled(String resourcePath) {
		String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
		try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(path)) {
			if (in != null) return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			SmpEconomy.LOG.error("[SMPEco] Could not read {} from the jar", path, e);
		}
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(SmpEconomy.MOD_ID);
		if (mod.isPresent()) {
			Optional<Path> file = mod.get().findPath(path);
			if (file.isPresent()) {
				try {
					return Optional.of(Files.readString(file.get(), StandardCharsets.UTF_8));
				} catch (IOException e) {
					SmpEconomy.LOG.error("[SMPEco] Could not read {}", file.get(), e);
				}
			}
		}
		return Optional.empty();
	}

	public static final class Config<T> {
		private final String name;
		private final Type type;
		private final Supplier<T> defaults;
		private final Consumer<T> onLoad;
		private final String resource;
		private T value;
		private String problem;

		private Config(String name, Type type, Supplier<T> defaults, Consumer<T> onLoad, String resource) {
			this.name = name;
			this.type = type;
			this.defaults = defaults;
			this.onLoad = onLoad;
			this.resource = resource;
		}

		public T get() {
			return value;
		}

		public String name() {
			return name;
		}

		public Path path() {
			return configDir().resolve(name + ".json");
		}

		public Path lastGoodPath() {
			return configDir().resolve(LASTGOOD_DIR).resolve(name + ".json");
		}

		public String problem() {
			return problem;
		}

		/**
		 * Falls back to the last good copy if the file is invalid, leaving the file untouched. With no usable copy:
		 * {@code strict} throws {@link IllegalStateException} (mod init, aborts startup), otherwise defaults with a
		 * reported problem (a class loaded late on a running server).
		 */
		public void initialLoad(boolean strict) {
			String error = reload();
			if (error == null) return;
			String fallback = loadLastGood();
			if (fallback == null) {
				problem = name + ".json is invalid (" + error + ") – the last good version is active (" + LASTGOOD_DIR + "/" + name
					+ ".json). Fix the file, then /smpeco reload";
				SmpEconomy.LOG.error("[SMPEco] Config {}", problem);
				return;
			}
			String msg = "Config " + name + ".json is invalid (" + error + ") and there is no usable last good copy (" + fallback
				+ "). Refusing to start on default values (prices, switches, limits). Repair the file (or delete it to rewrite defaults "
				+ "), then restart.";
			if (strict && !UnknownItems.registriesComplete()) {
				// A mod that initializes after this one may own the ids this file failed on, so decide at server start.
				T fallbackValue = defaults.get();
				if (onLoad != null) onLoad.accept(fallbackValue);
				this.value = fallbackValue;
				DEFERRED.add(this);
				return;
			}
			if (strict) {
				SmpEconomy.LOG.error("[SMPEco] {}", msg);
				throw new IllegalStateException("[SMPEco] " + msg);
			}
			T value = defaults.get();
			if (onLoad != null) onLoad.accept(value);
			this.value = value;
			problem = name + ".json is invalid (" + error + "), no last good version – defaults active";
			SmpEconomy.LOG.error("[SMPEco] {} (class loaded after startup, using defaults)", msg);
		}

		private String loadLastGood() {
			Path file = lastGoodPath();
			if (!Files.isRegularFile(file)) return "none present";
			try {
				T loaded = DataStore.gson().fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)), type);
				if (loaded == null) return "empty";
				if (onLoad != null) onLoad.accept(loaded);
				value = loaded;
				return null;
			} catch (IOException e) {
				return "IO error: " + e.getMessage();
			} catch (RuntimeException e) {
				return "invalid: " + e.getMessage();
			}
		}

		private void writeLastGood(Path file) {
			try {
				byte[] bytes = Files.readAllBytes(file);
				Path target = lastGoodPath();
				if (Files.isRegularFile(target) && java.util.Arrays.equals(bytes, Files.readAllBytes(target))) return;
				Files.createDirectories(target.getParent());
				DataStore.writeAtomic(target, bytes);
			} catch (IOException | RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Last good copy of {} not saved", name, e);
			}
		}

		public T defaults() {
			return defaults.get();
		}

		public String reload() {
			Path file = path();
			try {
				Files.createDirectories(file.getParent());
				T loaded;
				boolean writeBack = false;
				if (!Files.exists(file)) {
					if (resource != null) {
						String bundled = readBundled(resource).orElseThrow(() -> new IOException("Bundled file missing: " + resource));
						DataStore.writeAtomic(file, bundled.getBytes(StandardCharsets.UTF_8));
						loaded = DataStore.gson().fromJson(bundled, type);
					} else {
						loaded = defaults.get();
						writeBack = true;
					}
				} else {
					String json = Files.readString(file, StandardCharsets.UTF_8);
					JsonElement tree = JsonParser.parseString(json);
					loaded = DataStore.gson().fromJson(tree, type);
					if (loaded == null) return failed("file is empty");
					// Only for code defaults: bundled configs must keep deleted entries deleted.
					writeBack = resource == null && tree.isJsonObject()
						&& hasMissingKeys(tree.getAsJsonObject(), DataStore.gson().toJsonTree(loaded, type));
				}
				if (onLoad != null) onLoad.accept(loaded);
				value = loaded;
				problem = null;
				if (writeBack) write(file, loaded);
				writeLastGood(file);
				return null;
			} catch (IOException e) {
				return failed("IO error: " + e.getMessage());
			} catch (RuntimeException e) {
				return failed("Invalid: " + e.getMessage());
			}
		}

		private String failed(String error) {
			if (value != null) problem = name + ".json is invalid (" + error + ") – the previous version stays active";
			return error;
		}

		public void save() throws IOException {
			Path file = path();
			write(file, value);
			writeLastGood(file);
		}

		private boolean hasMissingKeys(JsonObject file, JsonElement full) {
			if (!full.isJsonObject()) return false;
			for (String key : full.getAsJsonObject().keySet()) {
				if (!file.has(key)) return true;
			}
			return false;
		}

		private void write(Path file, Object v) throws IOException {
			DataStore.writeAtomic(file, DataStore.gson().toJson(v, type).getBytes(StandardCharsets.UTF_8));
		}
	}
}
