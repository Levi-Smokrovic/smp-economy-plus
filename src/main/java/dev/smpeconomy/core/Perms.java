package dev.smpeconomy.core;

import java.util.function.Predicate;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * Wraps fabric-permissions-api. Player nodes are allowed by default; admin nodes need OP level 2 by default. Console
 * and RCON may do anything.
 *
 * <p>LuckPerms can grant child nodes implicitly ({@code apply-sponge-implicit-wildcards = true}): holding
 * {@code smpeconomy.admin} includes every {@code smpeconomy.admin.<feature>}. So the node for {@code /smpeco} is a leaf of its
 * own ({@link #ADMIN} = {@code smpeconomy.admin.core}), not a parent of feature admin nodes, and each {@code /smpeco
 * <feature>} branch also requires its feature's node.
 */
public final class Perms {
	public static final String ADMIN = "smpeconomy.admin.core";
	/** Separate from {@link #ADMIN} so helpers with {@code smpeconomy.admin.core} cannot change prices. */
	public static final String ADMIN_PRICES = "smpeconomy.admin.prices";
	public static final String ADMIN_SETTINGS = "smpeconomy.admin";
	public static final String ADMIN_NOTIFY = "smpeconomy.admin.notify";
	public static final PermissionLevel ADMIN_LEVEL = PermissionLevel.GAMEMASTERS;

	private Perms() {}

	public static boolean check(CommandSourceStack src, String node) {
		return Permissions.check(src, node, true);
	}

	public static boolean checkAdmin(CommandSourceStack src, String node) {
		return Permissions.check(src, node, ADMIN_LEVEL);
	}

	public static boolean check(ServerPlayer player, String node) {
		return Permissions.check(player, node, true);
	}

	public static boolean checkAdmin(ServerPlayer player, String node) {
		return Permissions.check(player, node, ADMIN_LEVEL);
	}

	public static Predicate<CommandSourceStack> require(String node) {
		return src -> check(src, node);
	}

	/** Returns an {@link AdminRequirement}, which is how {@link AdminLog} recognizes admin commands. */
	public static Predicate<CommandSourceStack> requireAdmin(String node) {
		return new AdminRequirement(node);
	}

	public record AdminRequirement(String node) implements Predicate<CommandSourceStack> {
		@Override
		public boolean test(CommandSourceStack src) {
			return checkAdmin(src, node);
		}
	}
}
