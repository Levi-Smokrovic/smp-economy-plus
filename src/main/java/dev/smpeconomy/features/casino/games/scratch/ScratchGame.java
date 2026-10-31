package dev.smpeconomy.features.casino.games.scratch;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.features.casino.Casino;

/**
 * Prize and grid are drawn and stored when the card is bought ({@link #buy}), so revealing fields cannot change the outcome.
 * Stake, coverage, limits and payout go through {@link Casino#startInstant} and {@link Casino#settle}.
 */
public final class ScratchGame {
	public static final String ID = "scratch";
	/** Same permission as the other house games. */
	public static final String NODE = "smpeconomy.casino";
	/** The game at the casino; highest possible payout = the top prize (price × 25). */
	public static final Casino.InstantGame GAME = new Casino.InstantGame(ID, "§eScratch Cards", Items.MAP, ScratchMath::maxPayout);
	/** Feature id of the selftests ({@code /smpeco selftest casino-scratch}). */
	public static final String TEST_FEATURE = "casino-scratch";

	private ScratchGame() {}

	public static void register() {
		Casino.registerGame(GAME);
		CommandRegistry.register((dispatcher, registryAccess, environment) -> commands(dispatcher));
		ScratchSelfTests.register();
	}

	/**
	 * Buys a card: casino admission (max bet, Treasury covers price × 25, daily loss limit, one round), price collected,
	 * prize + grid drawn and the payout decided, all stored before anything is shown. The tier is kept in
	 * {@code round.card}, the 9 symbols in {@code round.reels}.
	 */
	public static Casino.Start buy(UUID player, String name, ScratchTier tier, RandomGenerator rng) {
		long price = tier.price();
		return Casino.startInstant(player, name, GAME, price, r -> {
			ScratchMath.Card card = ScratchMath.draw(rng);
			r.card = tier.ordinal();
			r.reels = card.grid();
			return ScratchMath.payout(price, card.prize());
		});
	}

	/** Why this card can't be bought right now (shown in the GUI; null = looks fine). The real check is {@link #buy}. */
	public static String blockedReason(UUID player, ScratchTier tier) {
		long price = tier.price();
		if (!Casino.cfg().enabled) return "§cThe casino is closed right now.";
		long maxBet = Casino.maxBet();
		if (maxBet < price) return "§cLocked: max bet is " + Money.format(Math.max(0, maxBet)) + " §8(2 % of the Treasury)";
		if (ScratchMath.maxPayout(price) > Casino.coverLimit(null)) return "§cLocked: the Treasury can't cover the top prize";
		long room = Casino.lossRoom(player);
		if (room < price) return "§cDaily loss limit reached §8(" + Money.format(room) + " left)";
		if (EconomyService.balance(player) < price) return "§cNot enough money";
		return null;
	}

	static String[] help() {
		return new String[] {
		"§e§lScratch Cards §8– §7buy a card, scratch the 9 fields.",
		"§73 matching symbols win: §6Copper ×1§7, §fIron ×2§7, §eGold ×3§7, §aEmerald ×5§7, §bDiamond ×10§7, §dNether Star ×25§7.",
		"§7Cards: §6Bronze " + Money.formatShort(ScratchTier.BRONZE.price()) + "§7, §fSilver " + Money.formatShort(ScratchTier.SILVER.price())
			+ "§7, §eGold " + Money.formatShort(ScratchTier.GOLD.price()) + "§7. The result is fixed when you buy.",
		"§8/scratch [bronze|silver|gold] · payout rate " + rtpText() + " · /casino"};
	}

	/** "95.5 %". */
	static String rtpText() {
		return String.format(Locale.ROOT, "%.1f %%", ScratchMath.theoreticalRtp() * 100);
	}

	/** "1 in 2.3". */
	static String oddsText() {
		return String.format(Locale.ROOT, "1 in %.1f", 1 / ScratchMath.prizeChance());
	}

	public static GuiElementBuilder hubButton(ServerPlayer player) {
		List<String> lore = new ArrayList<>(List.of(
			"§7Scratch 9 fields, match 3 symbols,",
			"§7win up to §a×" + ScratchMath.maxMultiplier() + "§7. Cards from §a" + Money.format(ScratchTier.BRONZE.price()) + "§7.",
			"§8Payout rate " + rtpText() + " · /scratch"));
		List<String> open = new ArrayList<>();
		for (ScratchTier t : ScratchTier.values()) {
			if (blockedReason(player.getUUID(), t) == null) open.add(t.color() + capitalize(t.id()));
		}
		lore.add(open.isEmpty() ? "§cNo card available right now" : "§7Available: " + String.join("§7, ", open));
		lore.add("");
		lore.add("§eClick to play");
		return Gui.button(GAME.icon(), GAME.colored(), () -> ScratchGui.open(player, null), lore.toArray(String[]::new));
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		var root = Commands.literal("scratch").requires(Perms.require(NODE))
			.executes(ctx -> run(ctx, p -> ScratchGui.open(p, null)))
			.then(Commands.literal("help").executes(ScratchGame::help));
		for (ScratchTier t : ScratchTier.values()) {
			root.then(Commands.literal(t.id()).executes(ctx -> run(ctx, p -> ScratchGui.open(p, t))));
		}
		d.register(root);
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		for (String line : help()) Msg.info(ctx.getSource(), line);
		return 1;
	}

	private static int run(CommandContext<CommandSourceStack> ctx, Consumer<ServerPlayer> action) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!Gui.canOpen(p)) return 0;
		action.accept(p);
		return 1;
	}
}
