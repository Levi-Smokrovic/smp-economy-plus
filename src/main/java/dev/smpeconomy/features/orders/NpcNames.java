package dev.smpeconomy.features.orders;

import java.util.Random;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Items;

final class NpcNames {
	private static final String[] FIRST = {
		"Jack", "Emma", "Oliver", "Lucy", "Harry", "Grace", "George", "Molly", "Charlie", "Alice", "Henry", "Ruby", "Arthur", "Daisy",
		"Alfie", "Rosie", "Freddie", "Ivy", "Tom", "Poppy", "Sam", "Lily", "Ben", "Evie", "Max", "Ella", "Leo", "Isla",
		"Ollie", "Mia", "Finn", "Amber", "Joe", "Holly", "Ted", "Nora"};

	private static final String[][] TRADES = {
		{"Blacksmith", "ingot", "raw_", "_ore", "nugget", "diamond", "emerald", "netherite", "ancient_debris", "coal", "redstone", "lapis",
			"quartz", "iron", "gold", "copper", "amethyst"},
		{"Carpenter", "_log", "_wood", "planks", "_stem", "hyphae", "bamboo", "stick"},
		{"Fisher", "cod", "salmon", "tropical_fish", "pufferfish", "nautilus", "prismarine", "ink_sac", "kelp", "sponge"},
		{"Baker", "wheat", "bread", "cake", "cookie", "pie", "sugar", "egg"},
		{"Farmer", "carrot", "potato", "beetroot", "seeds", "melon", "pumpkin", "cocoa", "berries", "apple", "cactus", "nether_wart",
			"mushroom", "hay"},
		{"Alchemist", "blaze", "ghast", "magma", "spider_eye", "glowstone", "phantom", "slime", "ender", "shulker", "dragon",
			"breeze", "nether_star", "rabbit_foot"},
		{"Hunter", "rotten_flesh", "bone", "string", "gunpowder", "leather", "feather", "beef", "porkchop", "chicken", "mutton",
			"rabbit", "skull", "arrow", "honeycomb", "scute", "wool"},
		{"Dyer", "dye", "flower", "tulip", "poppy", "dandelion", "orchid", "allium", "bluet", "daisy", "cornflower", "lily",
			"rose", "lilac", "peony", "petals", "torchflower", "pitcher"},
		{"Builder", "stone", "cobble", "deepslate", "sand", "gravel", "dirt", "clay", "brick", "glass", "terracotta", "concrete",
			"granite", "diorite", "andesite", "tuff", "calcite", "basalt", "blackstone", "netherrack", "obsidian", "ice", "snow",
			"mud", "moss", "dripstone", "end_stone", "purpur"}};
	private static final String DEFAULT_TRADE = "Trader";

	private NpcNames() {}

	static String trade(Item item) {
		String id = Items.id(item);
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		for (String[] t : TRADES) {
			for (int i = 1; i < t.length; i++) {
				if (path.contains(t[i])) return t[0];
			}
		}
		return DEFAULT_TRADE;
	}

	static String forItem(Item item, Random rnd) {
		return trade(item) + " " + FIRST[rnd.nextInt(FIRST.length)];
	}
}
