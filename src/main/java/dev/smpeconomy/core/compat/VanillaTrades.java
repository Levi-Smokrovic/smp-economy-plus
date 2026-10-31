package dev.smpeconomy.core.compat;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import dev.smpeconomy.SmpEconomy;
//? if >=26.1 {
/*import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.Unit;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.TradeSets;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
*///?} else {
import java.util.Map;
import net.minecraft.world.entity.npc.villager.VillagerTrades;
import net.minecraft.world.flag.FeatureFlags;
import org.apache.commons.lang3.tuple.Pair;
//?}

/**
 * Vanilla villager and wandering trader trades as plain offer data. The only place that knows the trading API; used by
 * {@code core.TraderGoods}, {@code market.ValueEngine} and {@code news.NewsVillagerRoutes}.
 *
 * <p>On 1.21.11: static tables {@code VillagerTrades.TRADES}, {@code EXPERIMENTAL_TRADES},
 * {@code WANDERING_TRADER_TRADES}; each {@code ItemListing} is evaluated with {@code getOffer(level, null, random)} without a
 * villager entity (seed 0 per call; all professions and levels, then trader pools). {@link Kind} decides which listings
 * are evaluated, which also affects the random sequence.
 *
 * <p>On 26.x: trades are data-driven (registries {@code TRADE_SET}, {@code VILLAGER_TRADE}). Trade sets of all
 * professions (levels ascending) and the wandering trader are evaluated with
 * {@code VillagerTrade.getOffer(LootContext)} as in {@code AbstractVillager#addOffersFromTradeSet}, but with a dummy villager
 * that is never spawned, seed 0 per trade and no world position (ORIGIN absent). That avoids structure searches and map
 * data; explorer maps yield nothing and count as skipped. Biome-bound trades are tried with every villager type and the
 * first match counts; if all fail the trade is skipped. {@link Kind} is approximated from the offer's shape since no
 * listing classes exist. Rebalance trades are only in the registry with datapack {@code trade_rebalance};
 * {@link Experimental#ALWAYS} reads them directly from the built-in pack (after professions, before trader sets, sorted by
 * id). Books whose enchantment tags exist only in the pack yield nothing and count as skipped, as on 1.21.11. Emerald
 * costs come first as on 1.21.11. Vanilla 26.x caps costs at the stack size (book and quill: 1 rather than 2), so
 * offers show what a villager really charges.
 */
public final class VanillaTrades {
	public enum Kind {
		/**
		 * All listings; those needing a real villager (biome, enchantment) throw and count in {@link Result#skipped}.
		 */
		ALL,
		/**
		 * Only items for emeralds ({@code EmeraldForItems}, which does not read the entity). 26.x: result is emerald,
		 * cost A only.
		 */
		EMERALDS_FOR_ITEMS,
		/**
		 * Only listings without entity or world access or side effects ({@code ItemsForEmeralds},
		 * {@code ItemsAndEmeraldsToItems}, {@code EmeraldForItems}, also inside {@code TypeSpecificTrade}). Treasure maps etc
		 * would otherwise create data. 26.x: complete offers with no components on costs or result.
		 */
		ENTITY_FREE
	}

	public enum Experimental {
		/** Cautious rating: includes trades that only exist with the experiment. */
		ALWAYS,
		/** Like {@code Villager#updateTrades}. */
		IF_ENABLED
	}

	public record Offer(boolean wandering, ItemStack costA, ItemStack costB, ItemStack result) {}

	/** {@code skipped}: trades that failed to evaluate without a villager. */
	public record Result(List<Offer> offers, int skipped) {}

	private VanillaTrades() {}

	//? if >=26.1 {
	/*/^* Trade sets of the wandering trader (same order as the constants). ^/
	private static final List<ResourceKey<TradeSet>> WANDERING = List.of(TradeSets.WANDERING_TRADER_BUYING, TradeSets.WANDERING_TRADER_COMMON,
		TradeSets.WANDERING_TRADER_UNCOMMON);
	private static final String REBALANCE_PACK = "trade_rebalance";
	/^* Like LootContextParamSets.VILLAGER_TRADE, but without ORIGIN (no world position, see the class doc). ^/
	private static final ContextKeySet WITHOUT_POSITION = new ContextKeySet.Builder()
		.required(LootContextParams.THIS_ENTITY)
		.required(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED)
		.build();

	/^*
	 * Evaluates the vanilla trades. {@code logTag} (e.g. "market") logs skipped trades at debug level, null =
	 * silent. Server thread.
	 ^/
	public static Result offers(ServerLevel level, Kind kind, Experimental experimental, String logTag) {
		if (level == null) return new Result(List.of(), 0);
		Villager dummy = EntityType.VILLAGER.create(level, EntitySpawnReason.LOAD); // never in the world
		if (dummy == null) return new Result(List.of(), 0);
		List<Holder.Reference<VillagerType>> types = level.registryAccess().lookupOrThrow(Registries.VILLAGER_TYPE).listElements().toList();
		Registry<TradeSet> sets = level.registryAccess().lookupOrThrow(Registries.TRADE_SET);
		List<Offer> out = new ArrayList<>();
		int[] skipped = {0};
		for (Holder.Reference<VillagerProfession> profession : level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).listElements().toList()) {
			Int2ObjectMap<ResourceKey<TradeSet>> byLevel = profession.value().tradeSetsByLevel();
			int[] levels = byLevel.keySet().toIntArray();
			Arrays.sort(levels);
			for (int villagerLevel : levels) addSet(out, skipped, false, sets.getOptional(byLevel.get(villagerLevel)), level, dummy, types, kind, logTag);
		}
		if (experimental == Experimental.ALWAYS) addRebalance(out, skipped, level, dummy, types, kind, logTag);
		for (ResourceKey<TradeSet> key : WANDERING) addSet(out, skipped, true, sets.getOptional(key), level, dummy, types, kind, logTag);
		return new Result(List.copyOf(out), skipped[0]);
	}

	private static void addSet(List<Offer> out, int[] skipped, boolean wandering, Optional<TradeSet> set, ServerLevel level, Villager dummy,
			List<Holder.Reference<VillagerType>> types, Kind kind, String logTag) {
		if (set.isEmpty()) return;
		for (Holder<VillagerTrade> trade : trades(set.get())) add(out, skipped, wandering, trade, level, dummy, types, kind, logTag);
	}

	/^*
	 * Trades of the built-in pack {@code trade_rebalance}, if it is not active in this world (otherwise they are already
	 * in the registry). All files of the pack count, like {@code EXPERIMENTAL_TRADES} on top of the table on 1.21.11.
	 ^/
	private static void addRebalance(List<Offer> out, int[] skipped, ServerLevel level, Villager dummy,
			List<Holder.Reference<VillagerType>> types, Kind kind, String logTag) {
		PackRepository packs = level.getServer().getPackRepository();
		if (packs.getSelectedIds().contains(REBALANCE_PACK)) return;
		Pack pack = packs.getPack(REBALANCE_PACK);
		if (pack == null) return;
		RegistryOps<JsonElement> ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		Map<Identifier, Holder<VillagerTrade>> trades = new TreeMap<>();
		for (PackResources resources : open(pack)) {
			try (resources) {
				resources.listResources(PackType.SERVER_DATA, Identifier.DEFAULT_NAMESPACE, Registries.elementsDirPath(Registries.VILLAGER_TRADE), (id, file) -> {
					try (InputStream in = file.get(); InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
						trades.put(id, Holder.direct(VillagerTrade.CODEC.parse(ops, JsonParser.parseReader(reader)).getOrThrow()));
					} catch (IOException | RuntimeException e) {
						skipped[0]++;
						if (logTag != null) SmpEconomy.LOG.debug("[SMPEco] {}: Trade {} not readable: {}", logTag, id, e.toString());
					}
				});
			}
		}
		for (Holder<VillagerTrade> trade : trades.values()) add(out, skipped, false, trade, level, dummy, types, kind, logTag);
	}

	//? if >=26.3 {
	/^private static List<PackResources> open(Pack pack) {
		return pack.open().toList();
	}
	^///?} else {
	private static List<PackResources> open(Pack pack) {
		return List.of(pack.open());
	}
	//?}

	//? if >=26.3 {
	/^private static HolderSet<VillagerTrade> trades(TradeSet set) {
		return set.trades();
	}
	^///?} else {
	private static HolderSet<VillagerTrade> trades(TradeSet set) {
		return set.getTrades();
	}
	//?}

	private static void add(List<Offer> out, int[] skipped, boolean wandering, Holder<VillagerTrade> trade, ServerLevel level, Villager dummy,
			List<Holder.Reference<VillagerType>> types, Kind kind, String logTag) {
		MerchantOffer offer = null;
		try {
			for (Holder<VillagerType> type : types) {
				dummy.setVillagerData(dummy.getVillagerData().withType(type));
				offer = trade.value().getOffer(context(level, dummy));
				if (offer != null) break;
			}
		} catch (RuntimeException e) {
			skipped[0]++;
			if (logTag != null) SmpEconomy.LOG.debug("[SMPEco] {}: Trade {} skipped: {}", logTag, trade.getRegisteredName(), e.toString());
			return;
		}
		if (offer == null) {
			// cannot be evaluated for any villager type without a world position (e.g. explorer maps)
			skipped[0]++;
			if (logTag != null) SmpEconomy.LOG.debug("[SMPEco] {}: Trade {} skipped, no offer", logTag, trade.getRegisteredName());
			return;
		}
		ItemStack a = offer.getBaseCostA();
		ItemStack b = offer.getCostB();
		// Same order as 1.21.11 ("emerald + items to items"): the 26.x data lists the items first there
		if (!a.is(net.minecraft.world.item.Items.EMERALD) && b.is(net.minecraft.world.item.Items.EMERALD)) {
			ItemStack swap = a;
			a = b;
			b = swap;
		}
		if (!accepts(kind, a, b, offer.getResult())) return;
		out.add(new Offer(wandering, a, b, offer.getResult()));
	}

	/^* Context as for a real villager, but without a position and with its own fixed seed (not the world's random source). ^/
	private static LootContext context(ServerLevel level, Villager dummy) {
		LootParams params = new LootParams.Builder(level)
			.withParameter(LootContextParams.THIS_ENTITY, dummy)
			.withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
			.create(WITHOUT_POSITION);
		return new LootContext.Builder(params).withOptionalRandomSource(RandomSource.create(0)).create(Optional.empty());
	}

	/^*
	 * {@link Kind} from the shape of the offer (26.x no longer has listing classes). "Items to emerald", like EmeraldForItems
	 * on 1.21.11, also with components on the cost (water bottle at the wandering trader).
	 ^/
	private static boolean accepts(Kind kind, ItemStack a, ItemStack b, ItemStack result) {
		return switch (kind) {
			case ALL -> true;
			case EMERALDS_FOR_ITEMS -> result.is(net.minecraft.world.item.Items.EMERALD) && plain(result) && !a.isEmpty() && b.isEmpty();
			case ENTITY_FREE -> !a.isEmpty() && !result.isEmpty() && plain(a) && plain(b) && plain(result);
		};
	}

	private static boolean plain(ItemStack stack) {
		return stack.getComponentsPatch().isEmpty();
	}
	*///?} else {
	/**
	 * Evaluates the vanilla trades. {@code logTag} (e.g. "market") logs skipped trades at debug level, null =
	 * silent. Server thread.
	 */
	public static Result offers(ServerLevel level, Kind kind, Experimental experimental, String logTag) {
		RandomSource random = RandomSource.create(0);
		List<Offer> out = new ArrayList<>();
		int[] skipped = {0};
		List<Map<ResourceKey<VillagerProfession>, Int2ObjectMap<VillagerTrades.ItemListing[]>>> maps = new ArrayList<>();
		maps.add(VillagerTrades.TRADES);
		if (experimental == Experimental.ALWAYS
			|| (level != null && level.enabledFeatures().contains(FeatureFlags.TRADE_REBALANCE))) maps.add(VillagerTrades.EXPERIMENTAL_TRADES);
		for (Map<ResourceKey<VillagerProfession>, Int2ObjectMap<VillagerTrades.ItemListing[]>> map : maps) {
			for (Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel : map.values()) {
				for (VillagerTrades.ItemListing[] listings : byLevel.values()) addAll(out, skipped, false, listings, level, random, kind, logTag);
			}
		}
		for (Pair<VillagerTrades.ItemListing[], Integer> pool : VillagerTrades.WANDERING_TRADER_TRADES) {
			addAll(out, skipped, true, pool.getLeft(), level, random, kind, logTag);
		}
		return new Result(List.copyOf(out), skipped[0]);
	}

	private static void addAll(List<Offer> out, int[] skipped, boolean wandering, VillagerTrades.ItemListing[] listings, ServerLevel level,
			RandomSource random, Kind kind, String logTag) {
		if (listings == null) return;
		for (VillagerTrades.ItemListing listing : listings) add(out, skipped, wandering, listing, level, random, kind, logTag, 0);
	}

	private static void add(List<Offer> out, int[] skipped, boolean wandering, VillagerTrades.ItemListing listing, ServerLevel level,
			RandomSource random, Kind kind, String logTag, int depth) {
		switch (kind) {
			case ALL -> {}
			case EMERALDS_FOR_ITEMS -> {
				if (!(listing instanceof VillagerTrades.EmeraldForItems)) return;
			}
			case ENTITY_FREE -> {
				if (listing instanceof VillagerTrades.TypeSpecificTrade typed) {
					if (depth < 4 && typed.trades() != null) {
						for (VillagerTrades.ItemListing inner : typed.trades().values()) {
							add(out, skipped, wandering, inner, level, random, kind, logTag, depth + 1);
						}
					}
					return;
				}
				if (!(listing instanceof VillagerTrades.ItemsForEmeralds || listing instanceof VillagerTrades.ItemsAndEmeraldsToItems
						|| listing instanceof VillagerTrades.EmeraldForItems)) return;
			}
		}
		MerchantOffer offer;
		try {
			// without an entity, trades tied to a villager (biome, enchantment provider) throw here
			offer = listing.getOffer(level, null, random);
		} catch (RuntimeException e) {
			skipped[0]++;
			if (logTag != null) SmpEconomy.LOG.debug("[SMPEco] {}: Trade skipped: {}", logTag, e.toString());
			return;
		}
		if (offer == null) return;
		out.add(new Offer(wandering, offer.getBaseCostA(), offer.getCostB(), offer.getResult()));
	}
	//?}
}
