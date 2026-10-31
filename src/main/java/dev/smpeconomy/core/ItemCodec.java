package dev.smpeconomy.core;

import com.mojang.serialization.Dynamic;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;

/**
 * ItemStack to and from Base64, with all components. Internally compressed NBT {@code {DataVersion, item}} through
 * {@code ItemStack.CODEC}; the DataVersion lets DataFixer upgrade it after a Minecraft update.
 *
 * <p>If decoding fails, never delete the stored string since that loses the item. Keep the entry and log.
 */
public final class ItemCodec {
	private ItemCodec() {}

	public static String encode(ItemStack stack) {
		return encode(stack, registries());
	}

	public static String encode(ItemStack stack, HolderLookup.Provider registries) {
		if (stack.isEmpty()) throw new IllegalArgumentException("Cannot encode an empty ItemStack");
		Tag item = ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
			.getOrThrow(msg -> new IllegalArgumentException("Item not encodable: " + msg));
		CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", currentDataVersion());
		root.put("item", item);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			NbtIo.writeCompressed(root, out);
			return Base64.getEncoder().encodeToString(out.toByteArray());
		} catch (IOException e) {
			throw new IllegalStateException("Writing NBT failed", e);
		}
	}

	/** @throws IllegalArgumentException on invalid data */
	public static ItemStack decode(String data) {
		return decode(data, registries());
	}

	/** @throws IllegalArgumentException on invalid data */
	public static ItemStack decode(String data, HolderLookup.Provider registries) {
		CompoundTag root;
		try {
			byte[] bytes = Base64.getDecoder().decode(data);
			root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.uncompressedQuota());
		} catch (IOException | RuntimeException e) {
			throw new IllegalArgumentException("Invalid item data: " + e.getMessage(), e);
		}
		Tag item = root.get("item");
		if (item == null) throw new IllegalArgumentException("Item data without 'item'");
		int version = root.getIntOr("DataVersion", currentDataVersion());
		if (version < currentDataVersion()) {
			item = DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, item), version, currentDataVersion()).getValue();
		}
		return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), item)
			.getOrThrow(msg -> new IllegalArgumentException("Item not decodable: " + msg));
	}

	/** Like {@link #decode(String)} but logs and returns empty rather than throwing. */
	public static Optional<ItemStack> tryDecode(String data) {
		try {
			return Optional.of(decode(data));
		} catch (IllegalArgumentException e) {
			SmpEconomy.LOG.error("[SMPEco] Item data not readable: {}", e.getMessage());
			return Optional.empty();
		}
	}

	private static int currentDataVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}

	private static HolderLookup.Provider registries() {
		if (SmpEconomy.server() == null) throw new IllegalStateException("Server is not running");
		return SmpEconomy.server().registryAccess();
	}
}
