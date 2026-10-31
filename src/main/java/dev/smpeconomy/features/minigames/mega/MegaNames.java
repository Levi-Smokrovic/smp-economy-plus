package dev.smpeconomy.features.minigames.mega;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Items;

/**
 * Item names for answers. {@code config/smpeconomy/quiz_items.json} (or the bundled list) names the shop and everyday
 * items that trivia and the word scramble draw from. English name and item id (with or without {@code minecraft:})
 * are accepted as answers.
 */
public final class MegaNames {
	private static final String ITEMS_FILE = "quiz_items.json";
	private static volatile Set<Item> quizItems;
	private static volatile MegaQuestion.Terms vocabulary;

	private MegaNames() {}

	public static Set<Item> quizItems() {
		Set<Item> cached = quizItems;
		if (cached != null) return cached;
		Set<Item> items = new LinkedHashSet<>();
		Type type = new TypeToken<List<String>>() {}.getType();
		Optional<String> json = Optional.empty();
		try {
			Path file = ConfigManager.configDir().resolve(ITEMS_FILE);
			if (Files.isRegularFile(file)) json = Optional.of(Files.readString(file, StandardCharsets.UTF_8));
		} catch (Exception e) {
			SmpEconomy.LOG.warn("[SMPEco] minigames: {} unreadable, using the bundled list", ITEMS_FILE);
		}
		if (json.isEmpty()) json = ConfigManager.readBundled(ConfigManager.DEFAULTS_DIR + ITEMS_FILE);
		try {
			List<String> raw = json.isEmpty() ? null : DataStore.gson().fromJson(json.get(), type);
			if (raw != null) {
				for (String id : raw) {
					if (id != null) Items.byId(id).ifPresent(items::add);
				}
			}
		} catch (RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] minigames: {} unreadable", ITEMS_FILE, e);
		}
		Set<Item> result = Set.copyOf(items);
		if (SmpEconomy.server() != null) quizItems = result;
		return result;
	}

	public static void clearCache() {
		quizItems = null;
		vocabulary = null;
	}

	/**
	 * Known terms for text questions: a message exactly matching one counts as an attempt. Cached after server
	 * start.
	 */
	public static MegaQuestion.Terms vocabulary() {
		MegaQuestion.Terms cached = vocabulary;
		if (cached != null) return cached;
		Set<String> out = new LinkedHashSet<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == net.minecraft.world.item.Items.AIR) continue;
			out.addAll(answers(item));
		}
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
			out.add(Language.getInstance().getOrDefault(type.getDescriptionId()));
			out.add(id);
			out.add(id.substring(id.indexOf(':') + 1));
		}
		MegaQuestion.Terms result = MegaQuestion.Terms.of(out);
		if (SmpEconomy.server() != null) vocabulary = result;
		return result;
	}

	public static String english(Item item) {
		return Language.getInstance().getOrDefault(item.getDescriptionId());
	}

	public static String display(Item item) {
		return english(item);
	}

	/** For the word scramble: shop and everyday items. */
	public static java.util.Collection<String> englishNames() {
		List<String> out = new ArrayList<>();
		for (Item item : quizItems()) out.add(english(item));
		return out;
	}

	public static List<String> answers(Item item) {
		Set<String> out = new LinkedHashSet<>();
		out.add(english(item));
		String id = Items.id(item);
		out.add(id);
		out.add(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id);
		return new ArrayList<>(out);
	}
}
