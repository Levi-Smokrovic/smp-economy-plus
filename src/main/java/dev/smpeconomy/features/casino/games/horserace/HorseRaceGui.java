package dev.smpeconomy.features.casino.games.horserace;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Bet;
import dev.smpeconomy.features.casino.games.horserace.HorseData.History;
import dev.smpeconomy.features.casino.games.horserace.HorseData.PlayerStats;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Race;

/**
 * {@code /horserace}: one 6-row chest window with two views. Betting: the 6 horses with odds/chance/bets, your pick, the
 * stake bar and "Bet". Race (and a few seconds of results): one lane per horse, the horses run from left to the
 * checkered finish column. The window is display only, the race, the bets and the payouts run without it (closing it
 * keeps the bet). Updated every tick by {@link HorseRace}.
 */
final class HorseRaceGui extends SmpGui {
	static final String TITLE = "§8Horse Race";
	static final String[] NAMES = {"Leather Lightning", "Copper Comet", "Iron Duke", "Golden Gallop", "Diamond Dash", "Netherite Nightmare"};
	static final String[] COLORS = {"§6", "§c", "§f", "§e", "§b", "§5"};
	static final Item[] ICONS = {Items.LEATHER_HORSE_ARMOR, Items.COPPER_HORSE_ARMOR, Items.IRON_HORSE_ARMOR, Items.GOLDEN_HORSE_ARMOR,
		Items.DIAMOND_HORSE_ARMOR, Items.NETHERITE_HORSE_ARMOR};
	static final Item[] LANES = {Items.BROWN_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE, Items.LIGHT_GRAY_STAINED_GLASS_PANE,
		Items.YELLOW_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE};

	static final int SLOT_HELP = 0;
	static final int SLOT_STATUS = 4;
	static final int SLOT_HISTORY = 8;
	static final int[] HORSE_SLOTS = {10, 11, 12, 14, 15, 16};
	static final int[] TAG_SLOTS = {19, 20, 21, 23, 24, 25};
	static final int SLOT_CARD = 13;
	static final int SLOT_MY_BET = 31;
	/** Stake bar: back, ÷2, −, stake, action, +, ×2, max, close. */
	static final int BAR = 45;
	static final int SLOT_ACTION = BAR + 4;

	/** Open windows (updated by the tick). */
	static final Set<HorseRaceGui> OPEN = new LinkedHashSet<>();

	private final ServerPlayer player;
	private int selected = -1;
	private long selectedFor = -1;
	private long stake;
	/** "bet:<race>" or "track:<race>": what is drawn right now. */
	private String view = "";
	private int lastFrame = Integer.MIN_VALUE;
	private int lastCountdown = -1;
	private long lastSecond = Long.MIN_VALUE;
	private int[][] frames;
	private int[] finishFrame;
	private long framesFor = -1;

	private HorseRaceGui(ServerPlayer player) {
		super(MenuType.GENERIC_9x6, player, false);
		this.player = player;
		setTitle(Msg.legacy(TITLE));
		PlayerStats s = HorseRace.data().players.get(player.getUUID());
		long def = s != null && s.lastStake > 0 ? s.lastStake : 10_000; // $100
		this.stake = Math.max(Casino.cfg().minBet, def);
		long max = HorseRace.LIVE.maxStake(player.getUUID(), -1);
		if (max > 0 && stake > max) stake = max;
	}

	static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		if (!HorseRace.STORE.isLoaded()) {
			Msg.error(player, "The horse race is not available right now.");
			return;
		}
		HorseRaceGui gui = new HorseRaceGui(player);
		gui.update(HorseRace.LIVE.clock.getAsLong(), true);
		if (gui.open()) OPEN.add(gui);
	}

	static boolean isWatching(ServerPlayer p) {
		for (HorseRaceGui g : OPEN) {
			if (g.player == p && g.isOpen()) return true;
		}
		return false;
	}

	@Override
	protected void onEnded() {
		OPEN.remove(this);
	}

	/** Server stop: no more updates. */
	void forget() {
		OPEN.remove(this);
	}

	void update(long now, boolean force) {
		if (!force && !isOpen()) {
			OPEN.remove(this);
			return;
		}
		HorseData d = HorseRace.data();
		Race live = d.race;
		Race track = null;
		if (live != null && HorseData.RUNNING.equals(live.state)) track = live;
		else if (d.last != null && d.last.startedAt > 0 && now < d.last.endAt && now >= d.last.startedAt) track = d.last;
		if (track != null) renderTrack(track, now, force);
		else renderBetting(HorseRace.LIVE.card(), now, force);
	}

	private void renderBetting(Race r, long now, boolean force) {
		String v = "bet:" + r.id;
		boolean changed = !v.equals(view);
		long second = now / 1000;
		if (!changed && !force && second == lastSecond) return;
		if (changed) {
			view = v;
			lastFrame = Integer.MIN_VALUE;
			lastCountdown = -1;
			if (selectedFor != r.id) {
				selected = -1;
				selectedFor = r.id;
			}
		}
		lastSecond = second;
		if (r.closesAt > 0 && !changed) {
			int s = (int) ((r.closesAt - now + 999) / 1000);
			if (s >= 1 && s <= 5 && s != lastCountdown) {
				lastCountdown = s;
				Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.9f + (5 - s) * 0.15f);
			}
		}
		drawBetting(r, now);
	}

	private void drawBetting(Race r, long now) {
		UUID me = player.getUUID();
		Bet mine = r.bets.get(me);
		for (int i = 0; i < BAR; i++) {
			int row = i / 9;
			int col = i % 9;
			setSlot(i, row == 0 || row == 4 || col == 0 || col == 8 ? Gui.border() : Gui.filler());
		}
		setSlot(SLOT_HELP, help());
		setSlot(SLOT_STATUS, status(r, now));
		setSlot(SLOT_HISTORY, history());
		long[] pool = new long[HorseMath.HORSES];
		int[] count = new int[HorseMath.HORSES];
		for (Bet b : r.bets.values()) {
			if (b.horse < 0 || b.horse >= HorseMath.HORSES) continue;
			pool[b.horse] = Math.addExact(pool[b.horse], b.stake);
			count[b.horse]++;
		}
		setSlot(SLOT_CARD, cardInfo(r));
		for (int h = 0; h < HorseMath.HORSES; h++) {
			setSlot(HORSE_SLOTS[h], horseButton(r, h, mine, pool[h], count[h]));
			setSlot(TAG_SLOTS[h], tag(r, h, mine));
		}
		setSlot(SLOT_MY_BET, myBet(r, mine, now));
		drawBar(r, mine, now);
	}

	private GuiElementBuilder horseButton(Race r, int h, Bet mine, long pool, int count) {
		List<String> lore = new ArrayList<>();
		lore.add("§7Odds: §a" + HorseMath.formatOdds(r.odds[h]));
		lore.add("§7Win chance: §f" + HorseMath.formatChance(r.chances[h]));
		lore.add("§7Bets on this horse: §f" + count + (pool > 0 ? " §8(" + Money.format(pool) + ")" : ""));
		lore.add("§7Bet " + Money.format(stake) + " → win §a" + Money.format(HorseMath.payoutSafe(stake, r.odds[h])));
		lore.add("");
		if (mine != null) lore.add(mine.horse == h ? "§aYour horse in this race" : "§8You already bet on this race");
		else lore.add(selected == h ? "§a✔ Picked §7– click §aBet §7below" : "§eClick to pick");
		GuiElementBuilder b = Gui.button(ICONS[h], HorseRace.horseName(h), () -> select(h), lore.toArray(String[]::new));
		if (selected == h || mine != null && mine.horse == h) b.glow();
		return b;
	}

	private GuiElementBuilder tag(Race r, int h, Bet mine) {
		if (mine != null && mine.horse == h) {
			return Gui.item(Items.EMERALD, "§a▲ Your bet", "§7" + Money.format(mine.stake) + " → win §a" + Money.format(HorseMath.payoutSafe(mine.stake, mine.odds)));
		}
		if (mine == null && selected == h) {
			return Gui.item(Items.LIME_STAINED_GLASS_PANE, "§a▲ Picked", "§7Win: §a" + Money.format(HorseMath.payoutSafe(stake, r.odds[h])));
		}
		return Gui.item(Items.PAPER, "§a" + HorseMath.formatOdds(r.odds[h]), "§7Win chance " + HorseMath.formatChance(r.chances[h]))
			.setCount(Math.max(1, Math.min(64, r.odds[h] / 100)));
	}

	private GuiElementBuilder status(Race r, long now) {
		HorseConfig c = HorseRace.cfg();
		if (r.closesAt > 0) {
			long left = Math.max(0, r.closesAt - now);
			return Gui.item(Items.CLOCK, "§eRace #" + r.id + " starts in §f" + HorseRace.seconds(left),
				"§7Bets: §f" + r.bets.size() + " §8(" + Money.format(r.pool()) + ")",
				"§7Betting closes when the race starts.");
		}
		return Gui.item(Items.CLOCK, "§eRace #" + r.id + ": waiting for the first bet",
			"§7The race starts §f" + c.bettingWindowSeconds + " s §7after the first bet",
			"§7(at most one race every §f" + HorseRace.seconds(c.raceIntervalSeconds * 1000L) + "§7).");
	}

	private GuiElementBuilder cardInfo(Race r) {
		int fav = 0;
		int out = 0;
		for (int h = 1; h < HorseMath.HORSES; h++) {
			if (r.chances[h] > r.chances[fav]) fav = h;
			if (r.chances[h] < r.chances[out]) out = h;
		}
		return Gui.item(Items.HAY_BLOCK, "§6Race #" + r.id,
			"§7Bets: §f" + r.bets.size() + " §8(" + Money.format(r.pool()) + ")",
			"§7Favourite: " + HorseRace.horseName(fav) + " §8(" + HorseMath.formatOdds(r.odds[fav]) + ")",
			"§7Outsider: " + HorseRace.horseName(out) + " §8(" + HorseMath.formatOdds(r.odds[out]) + ")",
			"",
			"§8Payout = bet × odds of the winner");
	}

	private GuiElementBuilder myBet(Race r, Bet mine, long now) {
		if (mine != null) {
			return Gui.item(Items.SADDLE, "§aYour bet: " + Money.format(mine.stake),
				"§7on " + HorseRace.horseName(mine.horse) + " §8(" + HorseMath.formatOdds(mine.odds) + ")",
				"§7Win: §a" + Money.format(HorseMath.payoutSafe(mine.stake, mine.odds)),
				r.closesAt > 0 ? "§7Starts in §f" + HorseRace.seconds(Math.max(0, r.closesAt - now)) : "",
				"",
				"§8Paid when the winner crosses the line –",
				"§8also if you close this window or leave.").glow();
		}
		PlayerStats s = HorseRace.data().players.get(player.getUUID());
		List<String> lore = new ArrayList<>(List.of("§71. Click a horse above", "§72. Set your bet below", "§73. Click §aBet"));
		if (s != null && s.races > 0) {
			long net = s.paidOut - s.wagered;
			lore.add("");
			lore.add("§7Your races: §f" + Msg.number(s.races) + " §8· §7won: §f" + Msg.number(s.wins));
			lore.add("§7Result: " + (net >= 0 ? "§a+" : "§c") + Money.format(net));
		}
		return Gui.item(Items.NAME_TAG, "§7No bet on this race yet", lore);
	}

	private GuiElementBuilder help() {
		HorseConfig c = HorseRace.cfg();
		double edge = c.baseHouseEdgePercent;
		return Gui.item(Items.BOOK, "§eHow it works",
			"§71. Click a horse to pick it.",
			"§72. Set your bet with the buttons below.",
			"§73. Click §aBet §7– the stake is taken right away.",
			"",
			"§7The race starts §f" + c.bettingWindowSeconds + " s §7after the first bet",
			"§7(at most one race every §f" + HorseRace.seconds(c.raceIntervalSeconds * 1000L) + "§7).",
			"§7Everyone bets on the same race.",
			"§7Your horse wins: §abet × odds§7. Odds are fixed",
			"§7when you bet. Closing the window keeps your bet.",
			"",
			"§8House edge " + fmt(edge) + "–" + fmt(edge + 0.4) + " % · RTP ≈ " + fmt(100 - edge - 0.2) + " %",
			"§8/horserace bet <1-6> <amount>");
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.1f", d);
	}

	private GuiElementBuilder history() {
		List<History> hist = HorseRace.data().history;
		List<String> lore = new ArrayList<>();
		if (hist.isEmpty()) lore.add("§7No races yet.");
		for (History h : hist) {
			if (h.winner < 0 || h.winner >= HorseMath.HORSES) continue;
			lore.add("§7#" + h.raceId + ": " + HorseRace.horseName(h.winner) + " §8(" + HorseMath.formatOdds(h.odds) + ")");
		}
		return Gui.item(Items.FILLED_MAP, "§eLast winners", lore);
	}

	private void drawBar(Race r, Bet mine, long now) {
		UUID me = player.getUUID();
		long min = Casino.cfg().minBet;
		long max = HorseRace.LIVE.maxStake(me, selected);
		setSlot(BAR, Gui.button(Items.ARROW, "§c« Casino", this::openHub, "§7Back to the casino menu", "§8Your bet stays in the race."));
		setSlot(BAR + 3, Gui.item(Items.GOLD_NUGGET, "§6Bet: §e" + Money.format(stake),
			"§7Min " + Msg.moneyStr(min) + " §8· §7Max " + (max > 0 ? Msg.moneyStr(max) : "§clocked"),
			selected >= 0 ? "§8(max for the picked horse)" : "§8(max for every horse)",
			"§7Balance: " + Msg.moneyStr(EconomyService.balance(me))));
		boolean adjustable = mine == null;
		if (adjustable) {
			setSlot(BAR + 1, Gui.button(Items.RED_DYE, "§c÷2", () -> setStake(stake / 2), "§7Halve the bet"));
			setSlot(BAR + 2, Gui.button(Items.RED_DYE, "§c−" + Money.formatShort(10_000), t -> setStake(stake - (t.isRight ? 100_000 : 10_000)), "§7Left: −" + Money.formatShort(10_000), "§7Right: −" + Money.formatShort(100_000)));
			setSlot(BAR + 5, Gui.button(Items.LIME_DYE, "§a+" + Money.formatShort(10_000), t -> setStake(stake + (t.isRight ? 100_000 : 10_000)), "§7Left: +" + Money.formatShort(10_000), "§7Right: +" + Money.formatShort(100_000)));
			setSlot(BAR + 6, Gui.button(Items.LIME_DYE, "§a×2", () -> setStake(stake * 2), "§7Double the bet"));
			setSlot(BAR + 7, Gui.button(Items.GOLD_BLOCK, "§6Max", this::setMaxStake, "§7Highest possible bet"));
		} else {
			for (int i : new int[] {1, 2, 5, 6, 7}) setSlot(BAR + i, Gui.filler());
		}
		setSlot(SLOT_ACTION, action(r, mine, now));
		setSlot(BAR + 8, Gui.item(Items.BARRIER, "§cClose", "§8Your bet stays in the race.").setCallback((i, t, a, g) -> {
			if (Gui.isPlainClick(t)) close();
		}));
	}

	private GuiElementBuilder action(Race r, Bet mine, long now) {
		if (mine != null) {
			return Gui.item(Items.EMERALD_BLOCK, "§a✔ Bet placed",
				"§7" + Money.format(mine.stake) + " on " + HorseRace.horseName(mine.horse),
				"§7Win: §a" + Money.format(HorseMath.payoutSafe(mine.stake, mine.odds)),
				r.closesAt > 0 ? "§7Starts in §f" + HorseRace.seconds(Math.max(0, r.closesAt - now)) : "");
		}
		if (!Casino.cfg().enabled || !HorseRace.cfg().enabled) return Gui.item(Items.BARRIER, "§cThe casino is closed");
		if (selected < 0) {
			return Gui.button(Items.GRAY_DYE, "§7Pick a horse first", () -> {
				Msg.error(player, "Click one of the horses first.");
				Gui.failSound(player);
			}, "§7Click one of the 6 horses above.");
		}
		long win = HorseMath.payoutSafe(stake, r.odds[selected]);
		return Gui.button(Items.LIME_CONCRETE, "§a§lBet " + Money.format(stake) + " on #" + (selected + 1), this::placeBet,
			"§7" + HorseRace.horseName(selected) + " §8(" + HorseMath.formatOdds(r.odds[selected]) + ")",
			"§7Win: §a" + Money.format(win),
			"",
			r.closesAt > 0 ? "§7Race starts in §f" + HorseRace.seconds(Math.max(0, r.closesAt - now))
				: "§7The race starts §f" + HorseRace.cfg().bettingWindowSeconds + " s §7after this bet.");
	}

	private void select(int h) {
		Race r = HorseRace.LIVE.card();
		if (!HorseData.BETTING.equals(r.state)) return;
		if (r.bets.containsKey(player.getUUID())) {
			Msg.info(player, "You already have a bet on this race.");
			return;
		}
		selected = h;
		selectedFor = r.id;
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.HORSE_STEP), 1.0f);
		redraw();
	}

	private void placeBet() {
		Race r = HorseRace.LIVE.card();
		if (selected < 0 || selectedFor != r.id) return;
		HorseRace.placeBet(player, selected, stake);
		redraw();
	}

	private void setStake(long value) {
		Race r = HorseRace.LIVE.card();
		if (r.bets.containsKey(player.getUUID())) return;
		long v = Math.max(Casino.cfg().minBet, Math.min(Money.MAX, value));
		long max = HorseRace.LIVE.maxStake(player.getUUID(), selected);
		if (max > 0 && v > max) v = max;
		stake = v;
		redraw();
	}

	private void setMaxStake() {
		long max = Math.min(HorseRace.LIVE.maxStake(player.getUUID(), selected), EconomyService.balance(player.getUUID()));
		if (max < Casino.cfg().minBet) {
			Msg.error(player, "No bet possible right now (treasury, limit or balance).");
			Gui.failSound(player);
			return;
		}
		setStake(max);
	}

	private void redraw() {
		if (isOpen()) update(HorseRace.LIVE.clock.getAsLong(), true);
	}

	private void openHub() {
		Casino.openHub(player);
	}

	private void renderTrack(Race r, long now, boolean force) {
		String v = "track:" + r.id;
		boolean changed = !v.equals(view);
		if (changed) {
			view = v;
			lastFrame = Integer.MIN_VALUE;
			lastCountdown = -1;
		}
		if (framesFor != r.id) {
			frames = HorseMath.frames(r.seed, r.winner);
			finishFrame = new int[HorseMath.HORSES];
			for (int h = 0; h < HorseMath.HORSES; h++) {
				finishFrame[h] = Integer.MAX_VALUE;
				for (int f = 0; f < frames.length; f++) {
					if (frames[f][h] >= HorseMath.FINISH) {
						finishFrame[h] = f;
						break;
					}
				}
			}
			framesFor = r.id;
		}
		long t = now - r.startedAt;
		int frame = t < RaceEngine.COUNTDOWN_MS ? -1 : (int) Math.min((t - RaceEngine.COUNTDOWN_MS) / RaceEngine.FRAME_MS, frames.length - 1);
		Integer pick = r.picks.get(player.getUUID());
		int mine = pick == null ? -1 : pick;
		if (frame < 0) {
			int s = (int) ((RaceEngine.COUNTDOWN_MS - t + 999) / 1000);
			if (s != lastCountdown) {
				lastCountdown = s;
				Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 0.8f);
				Msg.actionBar(player, "§eRace #" + r.id + " §7– §f" + s + " …");
			}
		}
		if (frame == lastFrame && !force && !changed) return;
		int prev = lastFrame;
		lastFrame = frame;
		int wf = finishFrame[r.winner];
		if (frame >= 0 && prev != Integer.MIN_VALUE) {
			if (prev < 0) {
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BELL_BLOCK), 1.2f);
				Msg.actionBar(player, "§6§lAnd they're off!");
			} else if (frame <= wf) {
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.HORSE_GALLOP), 0.9f + (frame % 3) * 0.1f);
			}
			if (prev < wf && frame >= wf) {
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_BLAST), 1.0f);
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_TWINKLE), 1.0f);
			}
		}
		if (frame >= 0 && frame < wf) {
			Msg.actionBar(player, "§7Leading: " + HorseRace.horseName(leader(frame)) + (mine >= 0 ? " §8| §7yours: " + HorseRace.horseName(mine)
				+ " §8(" + rank(frame, mine) + ".)" : ""));
		} else if (frame >= wf) {
			Msg.actionBar(player, HorseRace.horseName(r.winner) + " §6§lwins!" + (mine >= 0 ? (mine == r.winner ? " §a§lYou win!" : " §7Not your day.") : ""));
		}
		drawTrack(r, frame, mine);
	}

	/** Horse in front at this frame (equal position: the one that finishes first). */
	private int leader(int frame) {
		int best = 0;
		for (int h = 1; h < HorseMath.HORSES; h++) {
			if (ahead(frame, h, best)) best = h;
		}
		return best;
	}

	private int rank(int frame, int horse) {
		int r = 1;
		for (int h = 0; h < HorseMath.HORSES; h++) {
			if (h != horse && ahead(frame, h, horse)) r++;
		}
		return r;
	}

	private boolean ahead(int frame, int a, int b) {
		int pa = frames[frame][a];
		int pb = frames[frame][b];
		return pa > pb || pa == pb && finishFrame[a] < finishFrame[b];
	}

	private void drawTrack(Race r, int frame, int mine) {
		int[] pos = frame < 0 ? new int[HorseMath.HORSES] : frames[frame];
		int[] places = frame < 0 ? new int[HorseMath.HORSES] : HorseMath.places(frames, frame);
		for (int h = 0; h < HorseMath.HORSES; h++) {
			int row = h * 9;
			for (int col = 0; col < HorseMath.FINISH; col++) {
				setSlot(row + col, pos[h] == col ? horse(r, h, places[h], mine) : lane(h));
			}
			setSlot(row + HorseMath.FINISH, pos[h] >= HorseMath.FINISH ? horse(r, h, places[h], mine) : finish(h));
		}
	}

	private GuiElementBuilder horse(Race r, int h, int place, int mine) {
		String name = (place > 0 ? "§6§l" + ordinal(place) + " §r" : "") + HorseRace.horseName(h);
		String odds = "§7Odds: §a" + HorseMath.formatOdds(r.odds[h]);
		GuiElementBuilder b = h == mine ? Gui.item(ICONS[h], name, odds, "§aYour horse") : Gui.item(ICONS[h], name, odds);
		if (place > 0) b.setCount(place);
		if (h == mine) b.glow();
		return b;
	}

	private static GuiElementBuilder lane(int h) {
		return new GuiElementBuilder(LANES[h]).setName(Msg.legacy(" ")).hideTooltip();
	}

	private static GuiElementBuilder finish(int h) {
		return Gui.item(h % 2 == 0 ? Items.WHITE_CONCRETE : Items.BLACK_CONCRETE, "§f§lFINISH");
	}

	static String ordinal(int n) {
		return switch (n) {
			case 1 -> "1st";
			case 2 -> "2nd";
			case 3 -> "3rd";
			default -> n + "th";
		};
	}
}
