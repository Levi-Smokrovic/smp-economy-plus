package dev.smpeconomy.features.shop;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.TimeUtil;

final class ShopCommand {
	static final String DEALS = "deals";
	static final String NODE_ADMIN = "smpeconomy.admin.shop";
	static final String FAVORITES = "favorites";
	/** Short command names for default categories; a real category ID takes precedence. */
	static final Map<String, String> CATEGORY_ALIASES = Map.of("blocks", "building_blocks");

	private ShopCommand() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("shop")
				.requires(Perms.require(ShopGui.NODE))
				.executes(ctx -> {
					ShopGui.openMain(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("search").then(Commands.argument("text", StringArgumentType.greedyString()).executes(ShopCommand::search)))
				.then(Commands.argument("category", StringArgumentType.greedyString())
					.suggests((ctx, builder) -> {
						List<String> names = new ArrayList<>();
						names.add(DEALS);
						names.add(FAVORITES);
						for (BasePrices.ShopCategory c : BasePrices.shopCategories()) names.add(commandName(c.id()));
						return SharedSuggestionProvider.suggest(names, builder);
					})
					.executes(ShopCommand::category)));

			dispatcher.register(Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("shop")
					.requires(Perms.requireAdmin(NODE_ADMIN))
					.then(Commands.literal("info")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers()).executes(ShopCommand::info)))
					.then(Commands.literal("reset")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers()).executes(ShopCommand::reset)))
					.then(Commands.literal("deals").executes(ShopCommand::deals))));
		});
	}

	private static int search(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return search(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "text"));
	}

	private static int search(ServerPlayer player, String query) {
		String text = query.replace('§', ' ').trim();
		if (!ShopSearch.isValidQuery(text)) {
			Msg.error(player, "Search term: " + ShopSearch.MIN_LENGTH + "–" + ShopSearch.MAX_LENGTH + " characters.");
			return 0;
		}
		List<Item> hits = new ArrayList<>(ShopSearch.search(text));
		hits.removeIf(i -> BasePrices.shopBuyPrice(i).isEmpty());
		if (hits.isEmpty()) {
			Msg.error(player, "No results for \"" + text + "\".");
			return 0;
		}
		ShopGui.openList(player, ShopGui.ListKind.SEARCH, text, 0);
		return hits.size();
	}

	private static int category(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String input = StringArgumentType.getString(ctx, "category").trim();
		int space = input.indexOf(' ');
		String key = (space < 0 ? input : input.substring(0, space)).toLowerCase(Locale.ROOT);
		String rest = space < 0 ? "" : input.substring(space + 1).trim();
		if (!rest.isEmpty()) {
			Msg.error(player, "Unknown category. Use §e/shop§c or §e/shop search <text>§c.");
			return 0;
		}
		if (key.equals(DEALS)) {
			ShopGui.openList(player, ShopGui.ListKind.DEALS, "", 0);
			return 1;
		}
		if (key.equals(FAVORITES)) {
			ShopGui.openList(player, ShopGui.ListKind.FAVORITES, "", 0);
			return 1;
		}
		Optional<BasePrices.ShopCategory> cat = ShopGui.category(key);
		if (cat.isEmpty() && CATEGORY_ALIASES.containsKey(key)) cat = ShopGui.category(CATEGORY_ALIASES.get(key));
		if (cat.isEmpty()) {
			Msg.error(player, "Unknown category. Use §e/shop§c or §e/shop search <text>§c.");
			return 0;
		}
		ShopGui.openList(player, ShopGui.ListKind.CATEGORY, cat.get().id(), 0);
		return 1;
	}

	static String commandName(String categoryId) {
		for (Map.Entry<String, String> e : CATEGORY_ALIASES.entrySet()) {
			if (e.getValue().equals(categoryId) && ShopGui.category(e.getKey()).isEmpty()) return e.getKey();
		}
		return categoryId;
	}

	/**
	 * Machine-readable for the e2e scripts: {@code shop info player=... balance=<cents> favorites=a,b
	 * recent=item:amount,...}.
	 */
	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		List<String> favs = new ArrayList<>(ShopPlayerData.rawFavorites(id));
		List<String> recent = new ArrayList<>();
		for (ShopPlayerData.RecentPurchase r : ShopPlayerData.recent(id)) recent.add(Items.id(r.item()) + ":" + r.amount());
		Msg.send(ctx.getSource(), Msg.legacy("§7shop info player=" + EconomyService.nameOrUnknown(id) + " balance=" + EconomyService.balance(id)
			+ " favorites=" + String.join(",", favs) + " recent=" + String.join(",", recent)));
		return 1;
	}

	/** Money and items stay untouched. */
	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		PlayerDataService.setBlob(id, ShopPlayerData.FAVORITES_KEY, null);
		PlayerDataService.setBlob(id, ShopPlayerData.RECENT_KEY, null);
		Msg.success(ctx.getSource(), "Reset shop favorites and history of " + EconomyService.nameOrUnknown(id) + ".");
		return 1;
	}

	private static int deals(CommandContext<CommandSourceStack> ctx) {
		List<DailyDeals.Deal> deals = DailyDeals.today();
		ServerPlayer self = ctx.getSource().getPlayer();
		UUID viewer = self != null ? self.getUUID() : new UUID(0, 0);
		Msg.send(ctx.getSource(), Msg.legacy("§7shop deals date=" + TimeUtil.today() + " count=" + deals.size()
			+ " resetIn=" + TimeUtil.formatDuration(DailyDeals.millisUntilReset())));
		for (DailyDeals.Deal d : deals) {
			Msg.send(ctx.getSource(), Msg.legacy("§7  deal " + Items.id(d.item()) + " pct=" + d.percent()
				+ " base=" + BasePrices.shopBuyPrice(d.item()).orElse(-1)
				+ " buy=" + ShopService.unitBuyPrice(viewer, d.item()).orElse(-1)
				+ " guard=" + PriceGuard.effectiveShopBuyPrice(viewer, d.item()).orElse(-1)
				+ " recipeFloor=" + RecipeGuard.floor(d.item())
				+ " pivot=" + PriceGuard.pivotPrice(d.item())));
		}
		return deals.size();
	}
}
