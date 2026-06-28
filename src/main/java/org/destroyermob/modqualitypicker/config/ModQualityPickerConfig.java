package org.destroyermob.modqualitypicker.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModQualityPickerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> ACTIVE_PROFILE_ID = BUILDER
            .comment("The quality profile selected for the current launch. Profile application happens before Minecraft finishes loading mods.")
            .define("activeProfileId", "balanced");

    public static final ModConfigSpec.BooleanValue ALLOW_PLAYER_MOD_OVERRIDES = BUILDER
            .comment("Allows players to make local enabled/disabled mod choices in addition to pack developer presets.")
            .define("allowPlayerModOverrides", true);

    public static final ModConfigSpec.BooleanValue WRITE_LAUNCH_SNAPSHOT = BUILDER
            .comment("Writes the currently loaded mod snapshot to config/modqualitypicker/active-selection.json after startup.")
            .define("writeLaunchSnapshot", true);

    public static final ModConfigSpec.BooleanValue ENABLE_WORLD_LOAD_PROMPT = BUILDER
            .comment("Checks a world's saved quality profile before the world is opened.")
            .define("enableWorldLoadPrompt", true);

    public static final ModConfigSpec.BooleanValue EXIT_AFTER_QUEUING_WORLD_PROFILE = BUILDER
            .comment("Exits Minecraft after queuing a world's profile so the launcher can restart with the requested mod set.")
            .define("exitAfterQueuingWorldProfile", false);

    public static final ModConfigSpec.ConfigValue<String> PACK_EXPORT_ROOT = BUILDER
            .comment("Preset export destination relative to the game directory. For this Prism workspace, ../pack/config/modqualitypicker points at the pack root.")
            .define("packExportRoot", "../pack/config/modqualitypicker");

    public static final ModConfigSpec.EnumValue<WorldMismatchPolicy> WORLD_MISMATCH_POLICY = BUILDER
            .comment("How the world-open flow should react when the world profile does not match the current launch.")
            .defineEnum("worldMismatchPolicy", WorldMismatchPolicy.PROMPT);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ModQualityPickerConfig() {
    }

    public static String activeProfileId() {
        return ACTIVE_PROFILE_ID.get();
    }

    public enum WorldMismatchPolicy {
        PROMPT,
        ALLOW_CURRENT,
        REQUIRE_WORLD_PROFILE
    }
}
