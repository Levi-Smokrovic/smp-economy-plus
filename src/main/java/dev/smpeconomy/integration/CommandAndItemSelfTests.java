package dev.smpeconomy.integration;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.UnknownItems;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.auction.AuctionConfig;
import dev.smpeconomy.features.orders.OrdersConfig;

final class CommandAndItemSelfTests {
	private static final String MISSING = "somemod_not_installed:thing";

	private CommandAndItemSelfTests() {}

	static void register() {
		SelfTests.register(Integrations.FEATURE, "commands-disable", ctx -> disable());
		SelfTests.register(Integrations.FEATURE, "commands-rename", ctx -> rename());
		SelfTests.register(Integrations.FEATURE, "commands-other-mod-keeps-name", ctx -> taken());
		SelfTests.register(Integrations.FEATURE, "commands-brigadier-merges", ctx -> brigadierMerges());
		SelfTests.register(Integrations.FEATURE, "commands-gui-tree-complete", CommandAndItemSelfTests::guiTree);
		SelfTests.register(Integrations.FEATURE, "items-unknown-skipped", ctx -> unknownSkipped());
	}

	private static CommandDispatcher<CommandSourceStack> ours() {
		CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
		var shop = d.register(Commands.literal("shop").executes(c -> 1).then(Commands.literal("search").executes(c -> 2)));
		d.register(Commands.literal("market").executes(c -> 3).redirect(shop));
		d.register(Commands.literal("pay").executes(c -> 4));
		return d;
	}

	private static CommandRegistry.Config config(List<String> disabled, Map<String, String> renamed) {
		CommandRegistry.Config config = new CommandRegistry.Config();
		config.disabled = new ArrayList<>(disabled);
		config.renamed = renamed;
		return config;
	}

	private static void disable() {
		CommandDispatcher<CommandSourceStack> target = new CommandDispatcher<>();
		CommandRegistry.publish(ours().getRoot(), target.getRoot(), config(List.of("pay"), Map.of()), (n, e) -> {});
		isTrue(target.getRoot().getChild("pay") == null, "disabled root missing");
		isTrue(target.getRoot().getChild("shop") != null, "others stay");
	}

	private static void rename() {
		CommandDispatcher<CommandSourceStack> target = new CommandDispatcher<>();
		Map<String, String> renamed = CommandRegistry.publish(ours().getRoot(), target.getRoot(), config(List.of(), Map.of("shop", "store")), (n, e) -> {});
		eq(Map.of("shop", "store"), renamed, "reported");
		isTrue(target.getRoot().getChild("shop") == null, "old name gone");
		var store = target.getRoot().getChild("store");
		isTrue(store != null && store.getChild("search") != null, "children kept");
		isTrue(target.getRoot().getChild("market").getRedirect() == store, "alias follows the rename");
	}

	private static void taken() {
		CommandDispatcher<CommandSourceStack> target = new CommandDispatcher<>();
		var theirs = target.register(Commands.literal("pay").then(Commands.argument("x", IntegerArgumentType.integer()).executes(c -> 9)));
		List<String> warned = new ArrayList<>();
		CommandRegistry.publish(ours().getRoot(), target.getRoot(), config(List.of(), Map.of()), (n, e) -> warned.add(n));
		eq(List.of("pay"), warned, "one report");
		isTrue(target.getRoot().getChild("pay") == theirs, "their node untouched");
		isTrue(theirs.getCommand() == null && theirs.getChildren().size() == 1, "nothing merged in");

		warned.clear();
		CommandDispatcher<CommandSourceStack> second = new CommandDispatcher<>();
		second.register(Commands.literal("store").executes(c -> 8));
		CommandRegistry.publish(ours().getRoot(), second.getRoot(), config(List.of(), Map.of("shop", "store")), (n, e) -> warned.add(n));
		eq(List.of("store"), warned, "a new name can be taken too");
	}

	/** Documents the behaviour CommandRegistry avoids, in case a Brigadier update changes it. */
	private static void brigadierMerges() {
		CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
		d.register(Commands.literal("x").requires(s -> false).executes(c -> 1));
		d.register(Commands.literal("x").requires(s -> true).executes(c -> 2).then(Commands.literal("y")));
		var node = d.getRoot().getChild("x");
		isFalse(node.getRequirement().test(null), "first requirement stays");
		isTrue(node.getChild("y") != null, "children merged");
	}

	private static void guiTree(TestContext ctx) {
		isTrue(CommandRegistry.root().getChild("smpeco") != null, "private tree has /smpeco");
		isTrue(ctx.server().getCommands().getDispatcher().getRoot().getChild("smpeco") != null, "public tree has /smpeco");
		eq("/smpeco status", CommandRegistry.publicCommand("/smpeco status"), "unrenamed text unchanged");
	}

	private static void unknownSkipped() {
		eq("[SMPEco] x.json: skipped unknown items (mod not installed?): " + MISSING, UnknownItems.warning("x.json", List.of(MISSING, MISSING)), "one line, ids once");
		isTrue(dev.smpeconomy.core.Items.byId(MISSING).isEmpty(), "unknown id");
		isTrue(dev.smpeconomy.core.Items.byId("minecraft:diamond").orElseThrow() == Items.DIAMOND, "vanilla id");
		isTrue(!BasePrices.sellPrices().isEmpty(), "prices loaded");

		OrdersConfig orders = new OrdersConfig();
		orders.boardCommonItems = new ArrayList<>(List.of(MISSING));
		orders.boardExclude = new ArrayList<>(List.of(MISSING));
		OrdersConfig.Conversion conversion = new OrdersConfig.Conversion();
		conversion.from = MISSING;
		conversion.to = "minecraft:diamond";
		orders.npcConversions = new ArrayList<>(List.of(conversion));
		orders.validate();
		eq(1, orders.boardCommonItems.size(), "orders.json keeps the id for when the mod returns");

		AuctionConfig auction = new AuctionConfig();
		auction.blockedItems = new ArrayList<>(List.of(MISSING));
		isFalse(auction.isBlocked(Items.DIAMOND), "unknown blocked id blocks nothing");
	}
}
