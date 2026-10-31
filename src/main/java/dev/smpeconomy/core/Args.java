package dev.smpeconomy.core;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.LinkedHashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registering command arguments: money as {@code StringArgumentType.greedyString()} when it is the last argument
 * (allows "$5", "1,000"), otherwise {@code word()}. Players, including offline ones, as {@code word()} with
 * {@link #knownPlayers()} as suggestions.
 */
public final class Args {
	public static final SimpleCommandExceptionType INVALID_AMOUNT =
		new SimpleCommandExceptionType(Msg.prefixed("§cInvalid amount. Examples: §e100§c, §e1000§c, §e2.5k§c, §e1m§c, §e12.34 §c(no dots as thousands separators)"));
	public static final SimpleCommandExceptionType UNKNOWN_PLAYER =
		new SimpleCommandExceptionType(Msg.prefixed("§cPlayer not found."));

	private Args() {}

	public static long money(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		OptionalLong v = Money.parse(StringArgumentType.getString(ctx, name));
		if (v.isEmpty()) throw INVALID_AMOUNT.create();
		return v.getAsLong();
	}

	public static long moneyAllowZero(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		OptionalLong v = Money.parseAllowZero(StringArgumentType.getString(ctx, name));
		if (v.isEmpty()) throw INVALID_AMOUNT.create();
		return v.getAsLong();
	}

	public static SuggestionProvider<CommandSourceStack> knownPlayers() {
		return (ctx, builder) -> {
			Set<String> names = new LinkedHashSet<>();
			for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) names.add(p.getGameProfile().name());
			names.addAll(EconomyService.knownNames());
			return SharedSuggestionProvider.suggest(names, builder);
		};
	}

	/**
	 * Online first, then the offline name cache. System accounts do not count as players, otherwise bounties or
	 * payments could land on an account nobody ever collects from.
	 */
	public static UUID knownPlayer(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		UUID id = knownAccount(ctx, name);
		if (EconomyService.isSystemAccount(id)) throw UNKNOWN_PLAYER.create();
		return id;
	}

	/** Like {@link #knownPlayer} but also allows system accounts. Admin commands only. */
	public static UUID knownAccount(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		String input = StringArgumentType.getString(ctx, name);
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(input);
		if (online != null) return online.getUUID();
		return EconomyService.findByName(input).orElseThrow(UNKNOWN_PLAYER::create);
	}
}
