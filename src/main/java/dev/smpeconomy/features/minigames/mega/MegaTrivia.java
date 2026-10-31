package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;

/**
 * Trivia generated at runtime from the running server's registries and RecipeManager, so answers always fit this
 * exact version and its datapacks.
 * <p>Number kinds are built so guessing is useless: random counts over wide ranges, different values within a
 * question, six rather than three max levels; the four most common solutions cover under 2 % (selftest {@code
 * mega-answer-space}). "Known" items are those in the quiz item list ({@link MegaNames}); names appear as translatable
 * components, so the client shows its own language.
 */
@SuppressWarnings("deprecation") // Ingredient#items is the only way to get at the ingredient items
public final class MegaTrivia {
	public static final List<String> KINDS = List.of("stack", "health", "ingredients", "yield", "enchantment", "smelting");
	static final int STACK_ITEMS = 5;
	static final int ENCHANTS = 6;

	/** Without random or transformable health (no horses, llamas, slimes, wolves). */
	public static final List<String> MOBS = List.of("zombie", "husk", "drowned", "zombie_villager", "skeleton", "stray", "bogged",
		"wither_skeleton", "creeper", "spider", "cave_spider", "enderman", "endermite", "silverfish", "blaze", "ghast", "witch", "guardian",
		"elder_guardian", "shulker", "vex", "evoker", "vindicator", "pillager", "ravager", "piglin", "piglin_brute", "hoglin", "zoglin",
		"zombified_piglin", "phantom", "warden", "wither", "ender_dragon", "breeze", "iron_golem", "snow_golem", "villager", "wandering_trader",
		"cow", "mooshroom", "pig", "sheep", "chicken", "rabbit", "polar_bear", "panda", "fox", "bee", "goat", "turtle", "axolotl", "frog", "sniffer",
		"allay", "camel", "armadillo", "strider", "dolphin", "squid", "glow_squid", "cod", "salmon", "pufferfish", "tropical_fish", "parrot",
		"ocelot", "cat", "bat");

	public record Mob(EntityType<?> type, int health) {}

	public record Craft(Item item, ResourceKey<Recipe<?>> recipe, int slots, int yield) {}

	public record Smelt(Item input, Item result) {}

	public record Ench(Holder<Enchantment> holder, int maxLevel) {}

	/** Built fresh per question, since datapacks can change on /reload. */
	public static final class Data {
		public final Map<Integer, List<Item>> byStack = new TreeMap<>();
		public final List<Mob> mobs = new ArrayList<>();
		public final List<Craft> crafts = new ArrayList<>();
		public final List<Smelt> smelts = new ArrayList<>();
		public final List<Ench> enchants = new ArrayList<>();
	}

	private MegaTrivia() {}

	public static String label(String kind) {
		return switch (kind) {
			case "stack" -> "Stack Sizes";
			case "health" -> "Health Points";
			case "ingredients" -> "Recipes";
			case "yield" -> "Recipe Yield";
			case "enchantment" -> "Enchantments";
			case "smelting" -> "Furnace";
			default -> kind;
		};
	}

	public static Data build(MinecraftServer server) {
		Data d = new Data();
		Set<Item> quizItems = MegaNames.quizItems();
		FeatureFlagSet flags = server.getWorldData().enabledFeatures();
		HolderLookup.Provider registries = server.registryAccess();

		// sorted for reproducible selftests
		List<Item> known = new ArrayList<>(quizItems);
		known.removeIf(i -> !i.isEnabled(flags));
		known.sort(Comparator.comparing(Items::id));
		for (Item i : known) d.byStack.computeIfAbsent(i.getDefaultMaxStackSize(), k -> new ArrayList<>()).add(i);

		for (String id : MOBS) {
			Identifier key = Identifier.withDefaultNamespace(id);
			if (!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) continue;
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(key);
			if (!type.isEnabled(flags) || !DefaultAttributes.hasSupplier(type)) continue;
			int hp = baseHealth(type);
			if (hp > 0) d.mobs.add(new Mob(type, hp));
		}

		Map<Item, List<Craft>> byResult = new LinkedHashMap<>();
		Map<Item, Set<Item>> smeltResults = new LinkedHashMap<>();
		List<RecipeHolder<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
		recipes.sort(Comparator.comparing(h -> h.id().identifier().toString()));
		for (RecipeHolder<?> h : recipes) {
			Recipe<?> recipe = h.value();
			if (recipe instanceof ShapedRecipe shaped) {
				ItemStack out = Mc.assemble(shaped, CraftingInput.EMPTY, registries);
				int slots = (int) shaped.getIngredients().stream().filter(java.util.Optional::isPresent).count();
				addCraft(byResult, out, h.id(), slots, flags, shaped.placementInfo().ingredients());
			} else if (recipe instanceof ShapelessRecipe shapeless) {
				ItemStack out = Mc.assemble(shapeless, CraftingInput.EMPTY, registries);
				addCraft(byResult, out, h.id(), shapeless.placementInfo().ingredients().size(), flags, shapeless.placementInfo().ingredients());
			} else if (recipe instanceof SmeltingRecipe smelting) {
				for (Holder<Item> in : smelting.input().items().toList()) {
					Item input = in.value();
					if (!input.isEnabled(flags)) continue;
					ItemStack out = Mc.assemble(smelting, new SingleRecipeInput(new ItemStack(input)), registries);
					if (out.isEmpty()) continue;
					smeltResults.computeIfAbsent(input, k -> new LinkedHashSet<>()).add(out.getItem());
				}
			}
		}
		for (Map.Entry<Item, List<Craft>> e : byResult.entrySet()) {
			// only items with exactly one crafting recipe, otherwise "the recipe" is ambiguous
			if (e.getValue().size() == 1 && quizItems.contains(e.getKey())) d.crafts.add(e.getValue().get(0));
		}
		for (Map.Entry<Item, Set<Item>> e : smeltResults.entrySet()) {
			if (e.getValue().size() != 1) continue;
			Item result = e.getValue().iterator().next();
			if (quizItems.contains(result) && result != e.getKey()) d.smelts.add(new Smelt(e.getKey(), result));
		}

		List<Ench> enchants = new ArrayList<>();
		registries.lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(ref -> {
			int max = ref.value().getMaxLevel();
			if (max >= 1 && max <= 9) enchants.add(new Ench(ref, max));
		});
		enchants.sort(Comparator.comparing(e -> e.holder().unwrapKey().map(k -> k.identifier().toString()).orElse("")));
		d.enchants.addAll(enchants);
		return d;
	}

	private static void addCraft(Map<Item, List<Craft>> byResult, ItemStack out, ResourceKey<Recipe<?>> id, int slots, FeatureFlagSet flags,
		List<Ingredient> ingredients) {
		if (out.isEmpty() || slots < 1 || slots > 9 || !out.getItem().isEnabled(flags)) return;
		for (Ingredient ing : ingredients) {
			if (ing.items().noneMatch(h -> h.value().isEnabled(flags))) return;
		}
		byResult.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(new Craft(out.getItem(), id, slots, out.getCount()));
	}

	@SuppressWarnings("unchecked")
	public static int baseHealth(EntityType<?> type) {
		if (!DefaultAttributes.hasSupplier(type)) return -1;
		AttributeSupplier s = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
		if (s == null || !s.hasAttribute(Attributes.MAX_HEALTH)) return -1;
		double v = s.getBaseValue(Attributes.MAX_HEALTH);
		return v >= 1 && v == Math.rint(v) && v < 10_000 ? (int) v : -1;
	}

	public static boolean available(String kind, Data d) {
		return switch (kind) {
			case "stack" -> d.byStack.size() >= 2 && d.byStack.values().stream().mapToInt(List::size).sum() >= 8;
			case "health" -> d.mobs.size() >= 3;
			case "ingredients", "yield" -> d.crafts.size() >= 3;
			case "enchantment" -> d.enchants.size() >= ENCHANTS;
			case "smelting" -> !d.smelts.isEmpty();
			default -> false;
		};
	}

	public static MegaQuestion create(String kind, Data d, Random r) {
		return create(kind, d, MegaQuestion.Terms.NONE, r);
	}

	public static MegaQuestion create(String kind, Data d, MegaQuestion.Terms vocabulary, Random r) {
		if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unknown trivia question: " + kind + " (allowed: " + KINDS + ")");
		if (!available(kind, d)) throw new IllegalStateException("Not enough data for " + kind);
		MegaQuestion.Builder b = MegaQuestion.builder(MegaChallenge.Type.TRIVIA, kind, label(kind));
		switch (kind) {
			case "stack" -> stack(b, d, r);
			case "health" -> health(b, d, r);
			case "ingredients" -> ingredients(b, d, r);
			case "yield" -> recipeYield(b, d, r);
			case "enchantment" -> enchant(b, d, r);
			default -> smelt(b, d, vocabulary, r);
		}
		return b.build();
	}

	private static void stack(MegaQuestion.Builder b, Data d, Random r) {
		List<Integer> sizes = new ArrayList<>(d.byStack.keySet());
		List<Item> items = new ArrayList<>();
		List<Integer> slots = new ArrayList<>();
		Set<Integer> usedSizes = new LinkedHashSet<>();
		int total = 0;
		while (items.size() < STACK_ITEMS) {
			int size = sizes.get(r.nextInt(sizes.size()));
			List<Item> pool = d.byStack.get(size);
			Item item = pool.get(r.nextInt(pool.size()));
			if (items.contains(item)) continue;
			// at least two different stack sizes, otherwise it's just one multiplication
			if (items.size() == STACK_ITEMS - 1 && usedSizes.size() == 1 && usedSizes.contains(size)) continue;
			int n = MegaMath.between(r, 1, 9);
			items.add(item);
			slots.add(n);
			usedSizes.add(size);
			total += n;
		}
		long sum = 0;
		MutableComponent list = Component.empty();
		MutableComponent sizesText = Msg.legacy("§7");
		StringBuilder calc = new StringBuilder();
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			int size = item.getDefaultMaxStackSize();
			sum += (long) slots.get(i) * size;
			if (i > 0) list.append(Msg.legacy("§8, "));
			list.append(Msg.legacy("§e" + slots.get(i) + (slots.get(i) == 1 ? " Slot " : " Slots "))).append(name(item));
			if (i > 0) sizesText.append(Msg.legacy("§7, "));
			sizesText.append(name(item)).append(Msg.legacy(" §7up to " + size));
			calc.append(i > 0 ? " + " : "").append(slots.get(i)).append("·").append(size);
		}
		b.line("§fA double chest is filled like this – every slot with a §efull stack§f:");
		b.line(list);
		b.line("§fHow many items are in the chest in total? §7(" + total + " of 54 slots used)");
		b.number(sum);
		b.explain(Msg.legacy("§7Stack sizes: ").append(sizesText));
		b.explain("§7" + calc + " = §a" + sum);
		b.fact("items", List.copyOf(items));
		b.fact("slots", List.copyOf(slots));
	}

	private static void health(MegaQuestion.Builder b, Data d, Random r) {
		List<Mob> mobs = pickDistinctBy(d.mobs, 3, Mob::health, r);
		long sum = 0;
		MutableComponent q = Msg.legacy("§fHow many health points do ");
		MutableComponent ex = Msg.legacy("§7");
		StringBuilder calc = new StringBuilder();
		List<Integer> counts = new ArrayList<>();
		for (int i = 0; i < mobs.size(); i++) {
			Mob m = mobs.get(i);
			int k = MegaMath.between(r, 2, 30);
			counts.add(k);
			sum += (long) k * m.health();
			if (i > 0) q.append(Msg.legacy(i == mobs.size() - 1 ? " §fand " : "§f, "));
			q.append(Msg.legacy("§e" + k + "× ")).append(mobName(m.type()));
			if (i > 0) ex.append(Msg.legacy("§7, "));
			ex.append(mobName(m.type())).append(Msg.legacy("§7: " + m.health()));
			calc.append(i > 0 ? " + " : "").append(k).append("·").append(m.health());
		}
		q.append(Msg.legacy(" §fhave together?"));
		b.line(q);
		b.line("§8(Base value of max health, without effects/bonuses – 1 heart = 2 health points)");
		b.number(sum);
		b.explain(ex);
		b.explain("§7" + calc + " = §a" + sum);
		b.fact("mobs", List.copyOf(mobs));
		b.fact("counts", List.copyOf(counts));
	}

	private static void ingredients(MegaQuestion.Builder b, Data d, Random r) {
		List<Craft> crafts = pickDistinctBy(d.crafts, 3, Craft::slots, r);
		long sum = 0;
		StringBuilder calc = new StringBuilder();
		MutableComponent q = Msg.legacy("§fHow many items do you put into the crafting grid in total if you craft ");
		MutableComponent ex = Msg.legacy("§7");
		List<Integer> times = new ArrayList<>();
		for (int i = 0; i < crafts.size(); i++) {
			Craft c = crafts.get(i);
			int k = MegaMath.between(r, 3, 60);
			times.add(k);
			sum += (long) k * c.slots();
			if (i > 0) q.append(Msg.legacy(i == crafts.size() - 1 ? " §fand " : "§f, "));
			q.append(name(c.item())).append(Msg.legacy(" §e" + k + (k == 1 ? " time" : " times")));
			if (i > 0) ex.append(Msg.legacy("§7, "));
			ex.append(name(c.item())).append(Msg.legacy("§7: " + c.slots() + (c.slots() == 1 ? " item" : " items") + " per recipe"));
			calc.append(i > 0 ? " + " : "").append(k).append("·").append(c.slots());
		}
		q.append(Msg.legacy("§f?"));
		b.line(q);
		b.line("§8(Each time with its only crafting table recipe; every filled slot = 1 item)");
		b.number(sum);
		b.explain(ex);
		b.explain("§7" + calc + " = §a" + sum);
		b.fact("crafts", List.copyOf(crafts));
		b.fact("times", List.copyOf(times));
	}

	private static void recipeYield(MegaQuestion.Builder b, Data d, Random r) {
		// different yields (at most one recipe with 1 item), otherwise the sum is almost only k1 + k2 + k3
		List<Craft> crafts = pickDistinctBy(d.crafts, 3, Craft::yield, r);
		long sum = 0;
		StringBuilder calc = new StringBuilder();
		MutableComponent q = Msg.legacy("§fYou craft the recipe for ");
		MutableComponent ex = Msg.legacy("§7");
		List<Integer> times = new ArrayList<>();
		for (int i = 0; i < crafts.size(); i++) {
			Craft c = crafts.get(i);
			int k = MegaMath.between(r, 5, 99);
			times.add(k);
			sum += (long) k * c.yield();
			if (i > 0) q.append(Msg.legacy(i == crafts.size() - 1 ? " §fand the one for " : "§f, the one for "));
			q.append(name(c.item())).append(Msg.legacy(" §e" + k + (k == 1 ? " time" : " times")));
			if (i > 0) ex.append(Msg.legacy("§7, "));
			ex.append(name(c.item())).append(Msg.legacy("§7: " + c.yield() + " per recipe"));
			calc.append(i > 0 ? " + " : "").append(k).append("·").append(c.yield());
		}
		q.append(Msg.legacy("§f."));
		b.line(q);
		b.line("§fHow many items do you get in total?");
		b.number(sum);
		b.explain(ex);
		b.explain("§7" + calc + " = §a" + sum);
		b.fact("crafts", List.copyOf(crafts));
		b.fact("times", List.copyOf(times));
	}

	private static void enchant(MegaQuestion.Builder b, Data d, Random r) {
		List<Ench> list = pickDistinct(d.enchants, ENCHANTS, r);
		StringBuilder digits = new StringBuilder();
		MutableComponent q = Msg.legacy("§fWrite the max levels of ");
		MutableComponent ex = Msg.legacy("§7");
		for (int i = 0; i < list.size(); i++) {
			Ench e = list.get(i);
			digits.append(e.maxLevel());
			if (i > 0) q.append(Msg.legacy(i == list.size() - 1 ? " §fand " : "§f, "));
			q.append(enchName(e));
			if (i > 0) ex.append(Msg.legacy("§7, "));
			ex.append(enchName(e)).append(Msg.legacy("§7: " + e.maxLevel()));
		}
		q.append(Msg.legacy(" §fone after another."));
		b.line(q);
		b.line("§7(Example: max levels 5, 3, 1, 4, 2 and 3 → 531423)");
		b.digits(digits.toString());
		b.explain(ex.append(Msg.legacy(" §7→ §a" + digits)));
		b.fact("enchants", List.copyOf(list));
	}

	private static void smelt(MegaQuestion.Builder b, Data d, MegaQuestion.Terms vocabulary, Random r) {
		Smelt s = d.smelts.get(r.nextInt(d.smelts.size()));
		b.line(Msg.legacy("§fWhat do you get when you smelt ").append(name(s.input())).append(Msg.legacy(" §fin a furnace?")));
		b.line("§7(Answer: the item name)");
		b.answers(MegaNames.answers(s.result()));
		// all possible furnace results: messages with one stay private, whichever is correct
		Set<Item> results = new LinkedHashSet<>();
		for (Smelt o : d.smelts) results.add(o.result());
		List<String> guard = new ArrayList<>();
		for (Item i : results) guard.addAll(MegaNames.answers(i));
		b.guard(guard);
		b.vocabulary(vocabulary);
		b.solution(name(s.result()), MegaNames.display(s.result()));
		b.explain(Msg.legacy("§7").append(name(s.input())).append(Msg.legacy(" §7→ furnace → §a")).append(Msg.itemName(s.result()).withStyle(ChatFormatting.GREEN)));
		b.fact("smelt", s);
	}

	static MutableComponent name(Item item) {
		return Msg.itemName(item).withStyle(ChatFormatting.YELLOW);
	}

	static MutableComponent mobName(EntityType<?> type) {
		return type.getDescription().copy().withStyle(ChatFormatting.YELLOW);
	}

	static MutableComponent enchName(Ench e) {
		return e.holder().value().description().copy().withStyle(ChatFormatting.YELLOW);
	}

	/**
	 * Tries for pairwise different keys (health, slots, yield); if there aren't enough, fills up with arbitrary
	 * entries.
	 */
	static <T> List<T> pickDistinctBy(List<T> from, int n, java.util.function.Function<T, Object> key, Random r) {
		if (from.size() < n) throw new IllegalStateException("not enough entries");
		List<T> copy = new ArrayList<>(from);
		java.util.Collections.shuffle(copy, r);
		List<T> out = new ArrayList<>();
		Set<Object> keys = new java.util.HashSet<>();
		for (T t : copy) {
			if (out.size() >= n) break;
			if (keys.add(key.apply(t))) out.add(t);
		}
		for (T t : copy) {
			if (out.size() >= n) break;
			if (!out.contains(t)) out.add(t);
		}
		return out;
	}

	static <T> List<T> pickDistinct(List<T> from, int n, Random r) {
		if (from.size() < n) throw new IllegalStateException("not enough entries");
		List<T> copy = new ArrayList<>(from);
		List<T> out = new ArrayList<>();
		for (int i = 0; i < n; i++) out.add(copy.remove(r.nextInt(copy.size())));
		return out;
	}

	public static Map<Item, Integer> craftingRecipeCount(MinecraftServer server) {
		Map<Item, Integer> count = new HashMap<>();
		HolderLookup.Provider registries = server.registryAccess();
		for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
			if (h.value() instanceof ShapedRecipe s) count.merge(Mc.assemble(s, CraftingInput.EMPTY, registries).getItem(), 1, Integer::sum);
			else if (h.value() instanceof ShapelessRecipe s) count.merge(Mc.assemble(s, CraftingInput.EMPTY, registries).getItem(), 1, Integer::sum);
		}
		return count;
	}
}
