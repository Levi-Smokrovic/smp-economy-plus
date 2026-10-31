plugins {
    id("dev.kikugie.stonecutter")
}

// Rewritten by "Set active project to <v>" and the IntelliJ plugin. Only commit with 1.21.11 active.
stonecutter active "1.21.11"

// From 26.2 on, color constants (Items.LIME_DYE) are collections (Items.DYE.lime()). The accessor is the color in camelCase.
val dyeColors = listOf(
    "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
    "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK",
)
// Families whose collection is named like the constant without the color (Items.RED_BED to Items.BED.red())
val sameNameFamilies = "DYE|WOOL|CARPET|STAINED_GLASS_PANE|STAINED_GLASS|CONCRETE_POWDER|CONCRETE|BED|BANNER|WALL_BANNER|GLAZED_TERRACOTTA|HARNESS"
// and those with a DYED_ prefix (Items.RED_TERRACOTTA to Items.DYED_TERRACOTTA.red()). RED_SAND, RED_MUSHROOM etc. are not affected.
val dyedFamilies = "TERRACOTTA|SHULKER_BOX|CANDLE_CAKE|CANDLE|BUNDLE"

fun camel(constant: String): String =
    constant.lowercase().split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")

stonecutter parameters {
    // String constants in Java: String V = /*$ mod_version*/ "1.0.0";
    swaps["mod_version"] = "\"${property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"

    // 1:1 renames in the vanilla, Fabric and sgui APIs. The source is committed in its 1.21.11 form. Each replacement runs
    // forward on newer versions and backward on older ones, so both directions must be safe on the whole source tree, and
    // substring hits are a real risk. Logic differences go into core/compat/Mc, VanillaTrades, core/gui/SmpGui or //? if blocks.
    replacements {
        // 26.1: Entity#getTags() to entityTags(). The code has no other getTags() or entityTags().
        string(current.parsed >= "26.1") {
            replace("getTags()", "entityTags()")
        }
        // sgui 2.x. The code only calls getSlot on sgui GUIs.
        string(current.parsed >= "26.1") {
            replace(".getSlot(", ".getGuiElement(")
        }
        // Fabric API 0.155 renamed World to Level
        string(current.parsed >= "26.1") {
            replace("ServerEntityWorldChangeEvents", "ServerEntityLevelChangeEvents")
        }
        string(current.parsed >= "26.1") {
            replace("AFTER_PLAYER_CHANGE_WORLD", "AFTER_PLAYER_CHANGE_LEVEL")
        }
        // Whole word only, so EndDragonFightOriginMixin stays as it is
        regex(current.parsed >= "26.1") {
            replace("\\bEndDragonFight\\b", "EnderDragonFight", "\\bEnderDragonFight\\b", "EndDragonFight")
        }
        // From 26.1 on, #dirt in its old meaning (dirt, grass, podzol, mycelium, moss, mud) is #substrate_overworld. The new #dirt
        // only holds dirt, coarse dirt and rooted dirt. Tags from config files go through Mc.configTag.
        regex(current.parsed >= "26.1") {
            replace("\\bBlockTags\\.DIRT\\b", "BlockTags.SUBSTRATE_OVERWORLD", "\\bBlockTags\\.SUBSTRATE_OVERWORLD\\b", "BlockTags.DIRT")
        }

        // 26.2: entity type constants moved to EntityTypes. EntityType keeps the class, CODEC, create and spawn.
        regex(current.parsed >= "26.2") {
            replace(
                "\\bEntityType\\.(?!CODEC\\b|STREAM_CODEC\\b)([A-Z][A-Z0-9_]*)\\b", "EntityTypes.$1",
                "\\bEntityTypes\\.([A-Z][A-Z0-9_]*)\\b", "EntityType.$1",
            )
        }
        // Every file with constants imports EntityType, and unused imports are harmless, so add EntityTypes next to it.
        regex(current.parsed >= "26.2") {
            replace(
                "import net\\.minecraft\\.world\\.entity\\.EntityType;(?! import net\\.minecraft\\.world\\.entity\\.EntityTypes;)",
                "import net.minecraft.world.entity.EntityType; import net.minecraft.world.entity.EntityTypes;",
                "import net\\.minecraft\\.world\\.entity\\.EntityType; import net\\.minecraft\\.world\\.entity\\.EntityTypes;",
                "import net.minecraft.world.entity.EntityType;",
            )
        }
        for (color in dyeColors) {
            val accessor = camel(color)
            regex(current.parsed >= "26.2") {
                replace(
                    "\\b(Items|Blocks)\\.${color}_($sameNameFamilies)\\b", "$1.$2.$accessor()",
                    "\\b(Items|Blocks)\\.($sameNameFamilies)\\.$accessor\\(\\)", "$1.${color}_$2",
                )
            }
            regex(current.parsed >= "26.2") {
                replace(
                    "\\b(Items|Blocks)\\.${color}_($dyedFamilies)\\b", "$1.DYED_$2.$accessor()",
                    "\\b(Items|Blocks)\\.DYED_($dyedFamilies)\\.$accessor\\(\\)", "$1.${color}_$2",
                )
            }
        }
        // The copper block is a weathering collection
        regex(current.parsed >= "26.2") {
            replace(
                "\\bItems\\.COPPER_BLOCK\\b(?!\\.weathering\\(\\))", "Items.COPPER_BLOCK.weathering().unaffected()",
                "\\bItems\\.COPPER_BLOCK\\.weathering\\(\\)\\.unaffected\\(\\)", "Items.COPPER_BLOCK",
            )
        }
        regex(current.parsed >= "26.2") {
            replace("\\bBlockTags\\.CONCRETE_POWDER\\b", "BlockTags.CONCRETE_POWDERS", "\\bBlockTags\\.CONCRETE_POWDERS\\b", "BlockTags.CONCRETE_POWDER")
        }
        // advancements.criterion was split into triggers and predicates
        string(current.parsed >= "26.2") {
            replace("net.minecraft.advancements.criterion.FishingRodHookedTrigger", "net.minecraft.advancements.triggers.FishingRodHookedTrigger")
        }
    }
}
