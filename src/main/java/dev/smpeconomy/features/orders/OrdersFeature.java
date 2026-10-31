package dev.smpeconomy.features.orders;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.orders.OrderBook.CancelResult;
import dev.smpeconomy.features.orders.OrderBook.ClaimResult;
import dev.smpeconomy.features.orders.OrderData.AwayNote;
import dev.smpeconomy.features.orders.OrderData.Order;

public final class OrdersFeature implements Feature {
	public static final String ID = "orders";
	public static final String NODE = "smpeconomy.orders";
	public static final String ADMIN_NODE = "smpeconomy.admin.orders";
	static final int TICK_PERIOD = 20 * 30;

	static final DataStore<OrderData> STORE = new DataStore<>("orders.json", OrderData.class, OrderData::new);
	static final ConfigManager.Config<OrdersConfig> CONFIG = ConfigManager.register("orders", OrdersConfig.class, OrdersConfig::new, OrdersConfig::validate);
	static final OrderBook BOOK = new OrderBook(STORE::get, STORE::markDirty, System::currentTimeMillis, OrderFunding.treasury(), new Random(),
		CONFIG::get, () -> STORE.isLoaded() && SmpEconomy.server() != null);

	static {
		STORE.onLoad(OrderData::fixNulls);
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void register() {
		Services.register(Services.NpcOrders.class, new Services.NpcOrders() {
			@Override
			public BulkOrder startBulkOrder(Item item, int amount, long durationMs, String label) {
				Order o = BOOK.startBulk(item, amount, durationMs, label);
				if (o == null) return null;
				SmpEconomy.LOG.info("[SMPEco] orders: NPC bulk order #{} {}x {} at {}", o.id, o.amount, o.item, Money.format(o.price));
				return new BulkOrder(o.id, o.amount);
			}

			@Override
			public boolean isOpen(long orderId) {
				return BOOK.isOpen(orderId);
			}
		});
		TestPlayers.onCleanup(id -> {
			if (STORE.isLoaded()) BOOK.purge(id);
		});
		OrderSort.register();
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("orders")
				.requires(Perms.require(NODE))
				.executes(ctx -> openMain(ctx))
				.then(Commands.literal("create")
					.then(Commands.argument("item", ItemArgument.item(registryAccess))
						.then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000_000))
							.then(Commands.argument("price", StringArgumentType.greedyString())
								.executes(OrdersFeature::create)))))
				.then(Commands.literal("my").executes(ctx -> {
					OrdersGui.openMine(ctx.getSource().getPlayerOrException(), 0);
					return 1;
				}))
				.then(Commands.literal("claim").executes(OrdersFeature::claim))
				.then(Commands.literal("cancel")
					.then(Commands.argument("id", LongArgumentType.longArg(1)).executes(OrdersFeature::cancel)))
				.then(Commands.literal("admin")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.literal("list").executes(OrdersFeature::adminList))
					.then(Commands.literal("info")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(OrdersFeature::adminInfo)))
					.then(Commands.literal("expire")
						.then(Commands.argument("id", LongArgumentType.longArg(1)).executes(OrdersFeature::adminExpire)))
					.then(Commands.literal("cancel")
						.then(Commands.argument("id", LongArgumentType.longArg(1)).executes(OrdersFeature::adminCancel)))
					.then(Commands.literal("bulk")
						.then(Commands.argument("item", ItemArgument.item(registryAccess))
							.then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000_000))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 7 * 24 * 60))
									.executes(OrdersFeature::adminBulk)))))
					.then(Commands.literal("rotate").executes(OrdersFeature::adminRefill))
					.then(Commands.literal("board").executes(OrdersFeature::adminBoard)
						.then(Commands.literal("refill").executes(OrdersFeature::adminRefill))
						.then(Commands.literal("clear").executes(OrdersFeature::adminBoardClear))
						.then(Commands.literal("spawn")
							.then(Commands.argument("item", ItemArgument.item(registryAccess)).executes(OrdersFeature::adminBoardSpawn)))
						.then(Commands.literal("item")
							.then(Commands.argument("item", ItemArgument.item(registryAccess)).executes(OrdersFeature::adminBoardItem)))
						.then(Commands.literal("reset")
							.then(Commands.argument("item", ItemArgument.item(registryAccess)).executes(OrdersFeature::adminBoardReset))))
					.then(Commands.literal("bonus")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(ctx -> adminBonus(ctx, false))
							.then(Commands.literal("reset").executes(ctx -> adminBonus(ctx, true)))))
					.then(Commands.literal("quote")
						.then(Commands.argument("id", LongArgumentType.longArg(1))
							.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
								.executes(OrdersFeature::adminQuote))))));
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID id = handler.getPlayer().getUUID();
			// JOIN runs on the server thread; hand over anyway to be safe
			Runnable schedule = () -> Scheduler.runLater(60, () -> greet(server, id));
			if (server.isSameThread()) schedule.run();
			else server.execute(schedule);
		});
		OrdersSelfTests.register();
		OrderSortSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		BOOK.migrateLegacy(); // old regular NPC orders (escrow) move to the board
		BOOK.tick(); // fill the board now rather than after 20 s
		Scheduler.runRepeating(20 * 20, TICK_PERIOD, BOOK::tick);
	}

	private static void greet(MinecraftServer server, UUID id) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player == null || !BOOK.ready()) return;
		List<AwayNote> notes = BOOK.takeAwayNotes(id);
		if (!Settings.get(id, Settings.NOTIFICATIONS)) return;
		if (!notes.isEmpty()) {
			Msg.info(player, "§eDeliveries to your orders while you were away:");
			for (AwayNote n : notes) {
				MutableComponent line = Msg.legacy("§8#" + n.orderId + " §f" + Msg.number(n.count) + "× ")
					.append(OrderBook.itemComponent(n.item).copy().withStyle(ChatFormatting.WHITE))
					.append(Msg.legacy(" §8(" + Money.format(n.cents) + ")"));
				Msg.send(player, Msg.prefixed(line));
			}
		}
		long waiting = BOOK.claimTotal(id);
		if (waiting > 0) {
			Msg.send(player, Msg.prefixed(Msg.legacy("§7Claim Chest: §f" + Msg.number(waiting) + " items §7waiting – ")
				.append(Msg.clickRun("§e[Open]", "/orders", "§7Open orders"))));
		}
	}

	private static int openMain(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!BOOK.ready()) {
			Msg.error(player, OrderBook.Status.NOT_READY.message());
			return 0;
		}
		OrdersGui.openMain(player);
		return 1;
	}

	private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ItemInput input = ItemArgument.getItem(ctx, "item");
		Item item = Mc.inputItem(input);
		// components in the command (e.g. enchanted_book[stored_enchantments={"minecraft:mending":1}]) make a
		// template
		ItemStack template = OrderBook.templateOf(Mc.inputStack(input, 1, false));
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		long price = Args.money(ctx, "price");
		OrdersGui.confirmCreate(player, item, template, amount, price, false, null);
		return 1;
	}

	private static int claim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ClaimResult r = BOOK.claimAll(player);
		OrdersGui.reportClaim(player, r);
		return r.status().ok() ? 1 : 0;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		long id = LongArgumentType.getLong(ctx, "id");
		CancelResult r = BOOK.cancel(player.getUUID(), id, false);
		if (!r.status().ok()) {
			Msg.error(player, r.status().message());
			Gui.failSound(player);
			return 0;
		}
		Msg.success(player, "Order §f#" + id + " §acancelled. Refunded: " + Msg.moneyStr(r.refunded()) + "§a.");
		Gui.successSound(player);
		return 1;
	}

	/**
	 * Player and bulk orders only; the board has about 400 entries, see {@code /orders admin board [item <item>]}.
	 */
	private static int adminList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		List<Order> all = BOOK.all().stream().filter(o -> !o.board).toList();
		Msg.send(src, Msg.legacy("§7orders count=" + all.size() + " escrow=" + BOOK.totalEscrow() + " board=" + BOOK.board().size()));
		for (Order o : all) Msg.send(src, Msg.legacy("§7" + line(o)));
		return all.size();
	}

	static String line(Order o) {
		String base = "#" + o.id + " npc=" + o.npc + " bulk=" + o.bulk + " item=" + o.item + " delivered=" + o.delivered + " amount=" + o.amount
			+ " price=" + o.price + " escrow=" + o.escrow + " owner=" + (o.npc ? "-" : EconomyService.nameOrUnknown(o.owner))
			+ " expiresIn=" + Math.max(0, (o.expiresAt - BOOK.now()) / 1000) + "s" + " template=" + (o.spec != null) + " anyEnchant=" + o.allowEnchanted;
		if (!o.board) return base;
		return base + " board=true demand=" + fmt(o.demand) + " factor=" + fmt(BOOK.board().factor(o)) + " label=" + o.label.replace(' ', '_');
	}

	private static String fmt(double v) {
		return String.format(java.util.Locale.ROOT, "%.4f", v);
	}

	/**
	 * Board adds reference (/sell without bonuses), demand, factor, supply pressure, daily bonus and {@code sell},
	 * this player's /sell price with their bonuses. -1 means no limit.
	 */
	private static int adminQuote(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		long id = LongArgumentType.getLong(ctx, "id");
		UUID player = Args.knownPlayer(ctx, "player");
		Optional<Order> o = BOOK.get(id);
		if (o.isEmpty()) {
			Msg.error(ctx.getSource(), "Order #" + id + " not found.");
			return 0;
		}
		OrderBook.Quote q = BOOK.quote(player, o.get());
		String extra = "";
		if (o.get().board) {
			NpcBoard b = BOOK.board();
			NpcBoard.Payout p = b.payout(player, o.get(), 64);
			extra = " ref=" + NpcBoard.referencePrice(o.get().itemType().orElseThrow()) + " demand=" + fmt(o.get().demand) + " factor="
				+ fmt(b.factor(o.get())) + " pressure=" + fmt(b.pressure(o.get().item)) + " bonusLeft=" + b.bonusLeft(player) + " stack="
				+ p.total() + " trend=" + b.trend(o.get()).name() + " sell=" + p.sell() + " stackBonus=" + p.bonus();
		}
		Msg.send(ctx.getSource(), Msg.legacy("§7orders-quote id=" + id + " player=" + EconomyService.nameOrUnknown(player) + " unit=" + q.unit()
			+ " price=" + o.get().price + " guard=" + q.guard() + " conv=" + q.conversion() + extra));
		return 1;
	}

	private static int adminBoard(CommandContext<CommandSourceStack> ctx) {
		NpcBoard b = BOOK.board();
		OrderData.BoardStats st = b.stats(b.today());
		Map<String, Integer> counts = b.counts();
		Msg.send(ctx.getSource(), Msg.legacy("§7orders-board count=" + b.size() + " items=" + counts.size() + " target=" + BOOK.config().boardSize
			+ " eligible=" + b.eligible(true).size() + " enabled=" + b.enabled() + " paidToday=" + st.paidToday + " bonusToday=" + st.bonusToday
			+ " unitsToday=" + st.unitsToday + " deliveriesToday=" + st.deliveriesToday + " paidTotal=" + st.paidTotal + " bonusTotal=" + st.bonusTotal
			+ " unitsTotal=" + st.unitsTotal + " migrated=" + st.migrated + " migratedEscrow=" + st.migratedEscrow
			+ " bonusPerDay=" + BOOK.config().boardDailyBonusPerPlayer));
		return b.size();
	}

	private static int adminRefill(CommandContext<CommandSourceStack> ctx) {
		NpcBoard b = BOOK.board();
		b.eligible(true);
		int n = b.refill(Integer.MAX_VALUE, false);
		Msg.send(ctx.getSource(), Msg.legacy("§7orders-board refill=" + n + " count=" + b.size()
			+ (b.enabled() ? "" : " §c(Order Board disabled)")));
		return n;
	}

	private static int adminBoardClear(CommandContext<CommandSourceStack> ctx) {
		int n = BOOK.board().clear();
		Msg.success(ctx.getSource(), n + " board orders removed (refills on the next tick if enabled).");
		return n;
	}

	private static int adminBoardSpawn(CommandContext<CommandSourceStack> ctx) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		NpcBoard b = BOOK.board();
		if (!BOOK.ready() || !b.enabled() || !b.isEligible(item, java.util.Set.of())) {
			Msg.error(ctx.getSource(), "Can't create a board order (board off, no /sell value, no market value or price cap below /sell).");
			return 0;
		}
		Order o = b.spawn(item, false, false);
		if (o == null) {
			Msg.error(ctx.getSource(), "No board order created.");
			return 0;
		}
		Msg.send(ctx.getSource(), Msg.legacy("§7orders-board spawned " + line(o)));
		return 1;
	}

	private static int adminBoardItem(CommandContext<CommandSourceStack> ctx) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		String id = dev.smpeconomy.core.Items.id(item);
		NpcBoard b = BOOK.board();
		List<Order> list = BOOK.all().stream().filter(o -> o.board && o.item.equals(id)).toList();
		Msg.send(ctx.getSource(), Msg.legacy("§7orders-board item=" + id + " count=" + list.size() + " ref=" + NpcBoard.referencePrice(item)
			+ " pressure=" + fmt(b.pressure(id))));
		for (Order o : list) Msg.send(ctx.getSource(), Msg.legacy("§7" + line(o)));
		return list.size();
	}

	private static int adminBoardReset(CommandContext<CommandSourceStack> ctx) {
		String id = dev.smpeconomy.core.Items.id(Mc.inputItem(ItemArgument.getItem(ctx, "item")));
		boolean had = BOOK.data().supply.remove(id) != null;
		STORE.markDirty();
		Msg.success(ctx.getSource(), "Supply for " + id + (had ? " reset." : " was already neutral."));
		return 1;
	}

	private static int adminBonus(CommandContext<CommandSourceStack> ctx, boolean reset) throws CommandSyntaxException {
		UUID player = Args.knownPlayer(ctx, "player");
		NpcBoard b = BOOK.board();
		if (reset) {
			BOOK.data().bonus.remove(player);
			STORE.markDirty();
		}
		Msg.send(ctx.getSource(), Msg.legacy("§7orders-bonus player=" + EconomyService.nameOrUnknown(player) + " used=" + b.bonusUsed(player)
			+ " left=" + b.bonusLeft(player) + " day=" + b.today() + (reset ? " reset=true" : "")));
		return 1;
	}

	private static int adminInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		List<Order> own = BOOK.ownedBy(id);
		String ids = own.stream().map(o -> Long.toString(o.id)).collect(Collectors.joining(","));
		Msg.send(ctx.getSource(), Msg.legacy("§7orders-info player=" + EconomyService.nameOrUnknown(id) + " balance=" + EconomyService.balance(id)
			+ " escrow=" + BOOK.escrowOf(id) + " orders=" + own.size() + " claim=" + BOOK.claimTotal(id) + " ids=" + (ids.isEmpty() ? "-" : ids)));
		return 1;
	}

	private static int adminExpire(CommandContext<CommandSourceStack> ctx) {
		long id = LongArgumentType.getLong(ctx, "id");
		Optional<Order> o = BOOK.get(id);
		if (o.isEmpty()) {
			Msg.error(ctx.getSource(), "Order #" + id + " not found.");
			return 0;
		}
		o.get().expiresAt = BOOK.now();
		STORE.markDirty();
		int n = BOOK.expireDue();
		Msg.success(ctx.getSource(), "Order #" + id + " expired (" + n + " refunded).");
		return 1;
	}

	private static int adminCancel(CommandContext<CommandSourceStack> ctx) {
		long id = LongArgumentType.getLong(ctx, "id");
		CancelResult r = BOOK.cancel(null, id, true);
		if (!r.status().ok()) {
			Msg.error(ctx.getSource(), r.status().message());
			return 0;
		}
		Msg.success(ctx.getSource(), "Order #" + id + " cancelled, refunded: " + Money.format(r.refunded()) + ".");
		return 1;
	}

	private static int adminBulk(CommandContext<CommandSourceStack> ctx) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
		Order o = BOOK.startBulk(item, amount, minutes * OrderBook.MINUTE_MS, OrderBook.BULK_LABEL);
		if (o == null) {
			Msg.error(ctx.getSource(), "Bulk order not created (no market value, price cap 0, item only exists with properties, limit reached or Treasury budget too small).");
			return 0;
		}
		Msg.success(ctx.getSource(), "Bulk order created: " + line(o));
		return 1;
	}
}
