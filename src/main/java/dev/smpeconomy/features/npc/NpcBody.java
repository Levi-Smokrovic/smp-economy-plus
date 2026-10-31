package dev.smpeconomy.features.npc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Items;

/**
 * The version-dependent part of NPCs: the body. From MC 1.21.9 it is a vanilla {@code mannequin}, which shows player
 * skins without a client mod. A port to an older version only has to replace this class; the rest of features/npc
 * doesn't know the entity type.
 */
final class NpcBody {
	static final String[] SLOT_NAMES = {"head", "chest", "legs", "feet", "mainhand", "offhand"};

	private NpcBody() {}

	/** Null if the body can't be created. Unknown equipment is skipped with a warning through {@code warn}. */
	static LivingEntity create(ServerLevel level, double x, double y, double z, float yaw, NpcConfig.Npc n, java.util.function.Consumer<String> warn) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.MANNEQUIN).toString());
		tag.putBoolean("immovable", true);
		tag.putBoolean("hide_description", true);
		Tag profile = NpcLayout.profileTag(n.skin);
		if (profile != null) tag.put("profile", profile);
		Entity loaded = Mc.loadEntityRecursive(tag, level, EntitySpawnReason.COMMAND, ent -> {
			ent.snapTo(x, y, z, yaw, 0f);
			return ent;
		});
		if (!(loaded instanceof Mannequin m)) {
			if (loaded != null) loaded.discard();
			return null;
		}
		Mc.setInvulnerable(m);
		m.setNoGravity(true);
		m.setSilent(true);
		m.setYBodyRot(yaw);
		m.setYHeadRot(yaw);
		for (Map.Entry<String, String> en : n.equipment.entrySet()) {
			ItemStack stack = equipmentStack(en.getValue());
			if (stack.isEmpty()) {
				warn.accept(en.getValue());
				continue;
			}
			m.setItemSlot(slot(en.getKey()), stack);
		}
		return m;
	}

	static boolean isBody(Entity e) {
		return e instanceof Mannequin;
	}

	static ItemStack equipmentStack(String spec) {
		NpcLayout.ItemSpec s = NpcLayout.parseItemSpec(spec);
		ItemStack stack = resolveStack(s.id());
		if (!stack.isEmpty() && s.color() != NpcLayout.ItemSpec.NONE) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(s.color()));
		return stack;
	}

	static ItemStack resolveStack(String id) {
		String full = id.contains(":") ? id : "minecraft:" + id;
		Optional<Item> item = Items.byId(full);
		return item.map(ItemStack::new).orElse(ItemStack.EMPTY);
	}

	/**
	 * Player equipment as npc.json entries. Item id and leather color are kept, other components are not, since NPC
	 * gear is only for looks. Empty slots are left out.
	 */
	static Map<String, String> equipmentOf(LivingEntity e) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : SLOT_NAMES) {
			ItemStack s = e.getItemBySlot(slot(name));
			if (s.isEmpty()) continue;
			String id = Items.id(s.getItem());
			if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
			DyedItemColor dye = s.get(DataComponents.DYED_COLOR);
			out.put(name, dye == null ? id : id + "#" + String.format("%06X", dye.rgb() & 0xFFFFFF));
		}
		return out;
	}

	static EquipmentSlot slot(String name) {
		return switch (name) {
			case "head" -> EquipmentSlot.HEAD;
			case "chest" -> EquipmentSlot.CHEST;
			case "legs" -> EquipmentSlot.LEGS;
			case "feet" -> EquipmentSlot.FEET;
			case "offhand" -> EquipmentSlot.OFFHAND;
			default -> EquipmentSlot.MAINHAND;
		};
	}

	static void logUnknownItem(String npc, String item) {
		SmpEconomy.LOG.warn("[SMPEco] npc: {}: unknown item {}", npc, item);
	}
}
