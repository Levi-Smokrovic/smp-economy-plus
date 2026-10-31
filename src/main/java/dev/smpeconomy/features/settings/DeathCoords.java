package dev.smpeconomy.features.settings;

import java.util.Optional;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Settings;

/**
 * "Death coordinates" switch: on death the player alone gets a chat line with position and dimension, with
 * click-to-copy coordinates. Runs from Fabric's {@code AFTER_DEATH} (end of {@code ServerPlayer#die}, server thread).
 */
public final class DeathCoords {
	private DeathCoords() {}

	static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer player) || Bots.isBot(player)) return;
			try {
				send(player);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] settings: death coordinates failed for {}", player.getPlainTextName(), e);
			}
		});
	}

	static void send(ServerPlayer player) {
		message(player).ifPresent(player::sendSystemMessage);
	}

	/** Empty if the player has the switch off. */
	static Optional<MutableComponent> message(ServerPlayer player) {
		if (!Settings.get(player.getUUID(), Settings.DEATH_COORDS)) return Optional.empty();
		BlockPos pos = player.blockPosition();
		String dim = dimensionLabel(player.level().dimension());
		MutableComponent copy = Msg.legacy("§8[Copy]").withStyle(s -> s
			.withClickEvent(new ClickEvent.CopyToClipboard(copyText(pos)))
			.withHoverEvent(new HoverEvent.ShowText(Msg.legacy("§7Copy coordinates to clipboard"))));
		return Optional.of(Msg.prefixed(text(pos, dim) + " ").append(copy));
	}

	/** {@code "§7You died at §fX 12, Y 64, Z -46 §7(§fOverworld§7)."} */
	static String text(BlockPos pos, String dimensionLabel) {
		return "§7You died at §fX " + pos.getX() + ", Y " + pos.getY() + ", Z " + pos.getZ() + " §7(§f" + dimensionLabel + "§7).";
	}

	/** {@code "12 64 -46"}, which fits directly into /tp. */
	static String copyText(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	static String dimensionLabel(ResourceKey<Level> dimension) {
		if (Level.OVERWORLD.equals(dimension)) return "Overworld";
		if (Level.NETHER.equals(dimension)) return "Nether";
		if (Level.END.equals(dimension)) return "End";
		return dimension.identifier().toString();
	}
}
