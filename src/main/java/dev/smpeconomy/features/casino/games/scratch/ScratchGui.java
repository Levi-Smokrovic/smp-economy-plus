package dev.smpeconomy.features.casino.games.scratch;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoData;

/**
 * Scratch card GUI (6 rows). The card in the middle (3×3 fields inside a frame in the tier's colour), prize table on top,
 * help and wallet on the left, status and "Scratch all"/"Buy another" on the right, the three cards in the bottom row.
 *
 * <p>The prize is fixed and stored when the card is bought; scratching only shows it. The round is settled
 * ({@link Casino#settle}) when the last field is scratched or the window is closed in any way (ESC, other menu, death,
 * disconnect, server stop), the casino watchdog leaves it alone while this window is open ({@link Casino.Window}, {@link Casino#bind}).
 */
final class ScratchGui extends SmpGui implements Casino.Window {
	static final String TITLE = "§8Scratch Cards";
	static final int ROWS = 6;
	/** Field 0–8 → slot (columns 3–5, rows 1–3). */
	static final int[] CELL_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32};
	static final int SLOT_PAYTABLE = 4;
	static final int SLOT_HELP = 10;
	static final int SLOT_STATUS = 16;
	static final int SLOT_WALLET = 19;
	static final int SLOT_ACTION = 25;
	static final int SLOT_BACK = 45;
	static final int[] TIER_SLOTS = {47, 49, 51};
	static final int SLOT_CLOSE = 53;
	/** Ticks between two fields of "Scratch all". */
	private static final int REVEAL_STEP = 3;

	static final Item[] SYMBOL_ITEMS = {Items.COPPER_INGOT, Items.IRON_INGOT, Items.GOLD_INGOT, Items.EMERALD, Items.DIAMOND, Items.NETHER_STAR};
	static final String[] SYMBOL_COLORS = {"§6", "§f", "§e", "§a", "§b", "§d"};

	private final ServerPlayer player;
	/** Card shown when nothing has been bought yet. */
	private ScratchTier tier;
	/** Open card (bought, not settled yet) or null. */
	private CasinoData.Round round;
	/** Card on display (open or last finished); null before the first purchase. */
	private ScratchTier shownTier;
	private int[] grid;
	private final boolean[] revealed = new boolean[ScratchMath.CELLS];
	/** Decided payout of the card on display. */
	private long payout;
	private boolean finished;
	/** ≥ 0 while "Scratch all" runs (ticks since start), else -1. */
	private int animTick = -1;
	private int lastServerTick = Integer.MIN_VALUE;

	private ScratchGui(ServerPlayer player, ScratchTier tier) {
		super(Gui.menuType(ROWS), player, false);
		this.player = player;
		this.tier = tier;
		setTitle(Msg.legacy(TITLE));
	}

	/** Opens the GUI; with {@code buyNow} a card of that tier is bought right away. */
	static void open(ServerPlayer player, ScratchTier buyNow) {
		ScratchGui gui = new ScratchGui(player, buyNow != null ? buyNow : lastTier(player));
		gui.render();
		if (!gui.open()) return;
		if (buyNow != null) gui.buy(buyNow);
	}

	private static ScratchTier lastTier(ServerPlayer player) {
		CasinoData.PlayerStats s = Casino.peekStats(player.getUUID());
		Long last = s == null ? null : s.lastStake.get(ScratchGame.ID);
		ScratchTier t = last == null ? null : ScratchTier.byPrice(last);
		return t != null ? t : ScratchTier.BRONZE;
	}

	private boolean playing() {
		return round != null && !finished;
	}

	private void buy(ScratchTier t) {
		if (isEnded() || playing() || animTick >= 0) return;
		tier = t;
		Casino.Start s = ScratchGame.buy(player.getUUID(), player.getGameProfile().name(), t, Casino.RNG);
		if (!s.ok()) {
			Msg.error(player, s.error());
			Gui.failSound(player);
			render();
			return;
		}
		round = s.round();
		Casino.bind(round.id, this);
		shownTier = t;
		grid = round.reels.clone();
		payout = round.payout;
		finished = false;
		Arrays.fill(revealed, false);
		sound(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.0f);
		Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 1.4f);
		render();
	}

	private void scratch(int cell) {
		if (!playing() || animTick >= 0 || revealed[cell] || !stillOpen()) return;
		Casino.extTouch(round);
		reveal(cell);
		if (allRevealed()) finish();
		else render();
	}

	private void scratchAll() {
		if (!playing() || animTick >= 0 || !stillOpen()) return;
		Casino.extTouch(round);
		animTick = 0;
		render();
	}

	/** Shows one field with sounds (pair → chime, triple → bell). */
	private void reveal(int cell) {
		revealed[cell] = true;
		sound(SoundEvents.BRUSH_SAND, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.4f);
		int same = 0;
		for (int i = 0; i < grid.length; i++) if (revealed[i] && grid[i] == grid[cell]) same++;
		if (same == 2) Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 1.0f + grid[cell] * 0.1f);
		else if (same >= ScratchMath.MATCH) Gui.sound(player, SoundEvents.NOTE_BLOCK_BELL, 1.5f);
	}

	private boolean allRevealed() {
		for (boolean r : revealed) if (!r) return false;
		return true;
	}

	/** Is the open card still in the casino (not settled from outside, e.g. by an admin)? */
	private boolean stillOpen() {
		if (round == null) return false;
		if (Casino.round(round.id) == round) return true;
		// settled elsewhere: show the card as it was decided, nothing left to pay here
		round = null;
		finished = true;
		animTick = -1;
		Arrays.fill(revealed, true);
		render();
		return false;
	}

	/** Last field scratched (or "Scratch all" done): settle, then show the result. */
	private void finish() {
		if (round == null) return;
		String id = round.id;
		round = null;
		animTick = -1;
		Casino.Settled s = Casino.settle(id, null);
		finished = true;
		Arrays.fill(revealed, true);
		if (s != null) payout = s.payout();
		long price = shownTier.price();
		if (payout >= price * 10) {
			sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
			sound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f);
		} else if (payout > price) {
			sound(SoundEvents.PLAYER_LEVELUP, 1.2f);
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
		} else if (payout == price) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.0f);
		} else {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
		}
		render();
	}

	private void back() {
		if (animTick >= 0) return;
		if (playing()) finish(); // the card is paid out as it is
		Casino.openHub(player);
	}

	@Override
	public void onTick() {
		// sgui also calls onTick after click packets: at most one step per server tick
		MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		if (round != null && !stillOpen()) return;
		if (animTick < 0 || !playing()) return;
		animTick++;
		if (animTick % REVEAL_STEP != 0) return;
		for (int i = 0; i < revealed.length; i++) {
			if (!revealed[i]) {
				reveal(i);
				if (allRevealed()) finish();
				else render();
				return;
			}
		}
		finish();
	}

	/** Any way of closing (ESC, other menu, death, disconnect, server stop): settle the open card at its fixed result. */
	@Override
	protected void onEnded() {
		animTick = -1;
		if (round != null) {
			String id = round.id;
			round = null;
			Casino.settle(id, Casino.CLOSED_REASON);
		}
	}

	@Override
	public void expire(String roundId, String why) {
		if (round != null && round.id.equals(roundId)) round = null;
		animTick = -1;
		Casino.settle(roundId, why);
		close();
	}

	/** Settled from outside (admin): show the card as it was decided, nothing left to pay here. */
	@Override
	public void detach(String roundId) {
		if (round == null || !round.id.equals(roundId)) return;
		round = null;
		finished = true;
		animTick = -1;
		Arrays.fill(revealed, true);
		render();
	}

	void render() {
		ScratchTier t = shownTier != null ? shownTier : tier;
		for (int i = 0; i < ROWS * 9; i++) setSlot(i, Gui.filler());
		// card frame in the tier's colour (rows 0–4, columns 2–6)
		for (int row = 0; row <= 4; row++) {
			for (int col = 2; col <= 6; col++) setSlot(row * 9 + col, pane(t.frame()));
		}
		setSlot(SLOT_PAYTABLE, paytable(t));
		for (int c = 0; c < ScratchMath.CELLS; c++) setSlot(CELL_SLOTS[c], cell(c));
		setSlot(SLOT_HELP, help());
		setSlot(SLOT_WALLET, wallet());
		setSlot(SLOT_STATUS, status());
		setSlot(SLOT_ACTION, action());
		setSlot(SLOT_BACK, Gui.button(Items.ARROW, "§c« Back", this::back, playing() ? "§7Your card is paid out as it is" : "§7Casino menu"));
		ScratchTier[] tiers = ScratchTier.values();
		for (int i = 0; i < tiers.length; i++) setSlot(TIER_SLOTS[i], tierButton(tiers[i]));
		setSlot(SLOT_CLOSE, Gui.item(Items.BARRIER, "§cClose", playing() ? "§7Your card is paid out as it is" : "").setCallback((i, type, a, g) -> {
			if (Gui.isPlainClick(type)) close();
		}));
	}

	private static GuiElementBuilder pane(Item item) {
		return new GuiElementBuilder(item).setName(Msg.legacy(" ")).hideTooltip();
	}

	private GuiElementBuilder cell(int c) {
		if (grid == null) return Gui.item(Items.LIGHT_GRAY_CONCRETE, "§7Scratch field", "§8Buy a card below");
		if (!revealed[c]) {
			if (!playing()) return Gui.item(Items.LIGHT_GRAY_CONCRETE, "§7Scratch field");
			return Gui.item(Items.LIGHT_GRAY_CONCRETE, "§f§lScratch!", "§7Click to scratch this field").setCallback((index, type, action, g) -> {
				if (!Gui.acceptClick(player, index, type)) return;
				scratch(c);
			});
		}
		int s = grid[c];
		long price = shownTier.price();
		GuiElementBuilder b = Gui.item(SYMBOL_ITEMS[s], SYMBOL_COLORS[s] + ScratchMath.NAMES[s],
			"§73 of these: §a×" + ScratchMath.MULTIPLIERS[s] + " §8(" + Money.format(ScratchMath.payout(price, s)) + ")");
		int same = 0;
		for (int i = 0; i < grid.length; i++) if (revealed[i] && grid[i] == s) same++;
		if (finished ? same >= ScratchMath.MATCH : same >= 2) b.glow();
		return b;
	}

	private static GuiElementBuilder paytable(ScratchTier t) {
		List<String> lore = new ArrayList<>();
		lore.add("§7Match 3 symbols to win:");
		for (int s = ScratchMath.SYMBOLS - 1; s >= 0; s--) {
			lore.add(SYMBOL_COLORS[s] + "3× " + ScratchMath.NAMES[s] + " §7→ §a×" + ScratchMath.MULTIPLIERS[s]
				+ " §8(" + Money.format(ScratchMath.payout(t.price(), s)) + ")");
		}
		lore.add("");
		lore.add("§8Payout rate " + ScratchGame.rtpText() + " · any prize " + ScratchGame.oddsText());
		lore.add("§8The result is fixed when you buy the card.");
		return Gui.item(Items.BOOK, "§ePrize table §8– " + t.colored(), lore);
	}

	private static GuiElementBuilder help() {
		return Gui.item(Items.OAK_SIGN, "§eHow to play",
			"§71. Pick a card below: §6Bronze§7, §fSilver §7or §eGold§7.",
			"§72. Click the 9 grey fields to scratch them.",
			"§73. 3 matching symbols win that prize.",
			"§7Closing the window pays the card out as it is.",
			"",
			"§8/scratch [bronze|silver|gold] · /scratch help");
	}

	private GuiElementBuilder wallet() {
		var id = player.getUUID();
		long maxBet = Casino.maxBet();
		return Gui.item(Items.GOLD_NUGGET, "§6Your wallet",
			"§7Balance: " + Msg.moneyStr(EconomyService.balance(id)),
			"§7Daily loss limit left: " + Msg.moneyStr(Casino.lossRoom(id)),
			"§7Max bet right now: " + (maxBet >= Casino.cfg().minBet ? Msg.moneyStr(maxBet) : "§clocked"),
			"§8/casino limit <amount> sets your own limit");
	}

	private GuiElementBuilder status() {
		if (grid == null) {
			return Gui.item(Items.PAPER, "§eChoose a card below",
				"§6Bronze " + Money.format(ScratchTier.BRONZE.price()) + "§7, §fSilver " + Money.format(ScratchTier.SILVER.price())
					+ "§7, §eGold " + Money.format(ScratchTier.GOLD.price()),
				"§7Top prize §a×" + ScratchMath.maxMultiplier() + " §7of the price.");
		}
		if (!finished) {
			int k = 0;
			for (boolean r : revealed) if (r) k++;
			return Gui.item(Items.CLOCK, "§eScratched §f" + k + "§7/§f" + ScratchMath.CELLS,
				"§7Find 3 matching symbols!",
				"§8" + shownTier.colored() + " §8· " + Money.format(shownTier.price()));
		}
		long price = shownTier.price();
		int prize = ScratchMath.evaluate(grid);
		String line = prize >= 0 ? SYMBOL_COLORS[prize] + "3× " + ScratchMath.NAMES[prize] + " §7→ §a×" + ScratchMath.MULTIPLIERS[prize] : "§7No 3 matching symbols.";
		if (payout > price) {
			GuiElementBuilder b = Gui.item(Items.EMERALD_BLOCK, "§a§lYOU WIN " + Money.format(payout) + "!", line,
				"§8" + shownTier.colored() + " §8· " + Money.format(price));
			return b.glow();
		}
		if (payout == price && payout > 0) {
			return Gui.item(Items.GOLD_NUGGET, "§eMoney back: " + Money.format(payout), line, "§8" + shownTier.colored());
		}
		return Gui.item(Items.COAL, "§cNo prize this time", line, "§7Better luck with the next card!");
	}

	private GuiElementBuilder action() {
		if (playing()) {
			if (animTick >= 0) return Gui.item(Items.CLOCK, "§7Scratching …");
			return Gui.button(Items.BRUSH, "§e§lScratch all", this::scratchAll, "§7Reveal all remaining fields");
		}
		if (finished && shownTier != null) {
			ScratchTier t = shownTier;
			String reason = ScratchGame.blockedReason(player.getUUID(), t);
			return Gui.button(Items.LIME_CONCRETE, "§a§lBuy another " + t.colored(), () -> buy(t),
				"§7Price: " + Msg.moneyStr(t.price()), reason == null ? "§eClick: buy & play" : reason);
		}
		return Gui.filler();
	}

	private GuiElementBuilder tierButton(ScratchTier t) {
		long price = t.price();
		String reason = ScratchGame.blockedReason(player.getUUID(), t);
		String[] lore = {
			"§7Price: " + Msg.moneyStr(price),
			"§7Top prize: §a" + Money.format(ScratchMath.maxPayout(price)) + " §8(×" + ScratchMath.maxMultiplier() + ")",
			"",
			playing() ? "§8Finish your card first" : reason == null ? "§eClick: buy & play" : reason};
		String name = t.colored() + " §8– §a" + Money.format(price);
		GuiElementBuilder b = playing() ? Gui.item(t.icon(), name, lore) : Gui.button(t.icon(), name, () -> buy(t), lore);
		if (t == (shownTier != null ? shownTier : tier)) b.glow();
		return b;
	}

	private void sound(SoundEvent event, float pitch) {
		Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event);
		Gui.sound(player, holder, pitch);
	}
}
