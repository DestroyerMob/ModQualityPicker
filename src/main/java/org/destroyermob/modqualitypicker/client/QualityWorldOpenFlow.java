package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.destroyermob.modqualitypicker.config.ModQualityPickerConfig;
import org.destroyermob.modqualitypicker.configfile.ConfigFileManager;
import org.destroyermob.modqualitypicker.profile.ProfileDiff;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;
import org.destroyermob.modqualitypicker.runtime.QualityRuntime;
import org.destroyermob.modqualitypicker.runtime.RuntimeSelection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public final class QualityWorldOpenFlow {
    private static final ThreadLocal<Boolean> BYPASS_CHECK = ThreadLocal.withInitial(() -> false);

    private QualityWorldOpenFlow() {
    }

    public static boolean shouldBypass() {
        return BYPASS_CHECK.get();
    }

    public static boolean openWithCheck(SelectWorldScreen screen, WorldSelectionList.WorldListEntry entry, LevelSummary summary) {
        if (!ModQualityPickerConfig.ENABLE_WORLD_LOAD_PROMPT.get() || shouldBypass()) {
            return false;
        }
        if (!entry.canJoin()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Path worldDirectory = minecraft.getLevelSource().getLevelPath(summary.getLevelId());

        try {
            Optional<QualityProfile> worldProfile = QualityRuntime.store().readProfile(ProfilePaths.worldProfile(worldDirectory));
            RuntimeSelection currentSelection = QualityRuntime.currentSelection();

            if (worldProfile.isEmpty()) {
                QualityRuntime.writeWorldProfile(worldDirectory, QualityRuntime.captureCurrentProfile(currentSelection.activeProfileId(), currentSelection.activeProfileId()));
                return false;
            }

            ProfileDiff diff = ProfileDiff.compare(worldProfile.get(), currentSelection);
            if (!diff.hasDifferences()) {
                return false;
            }

            return handleMismatch(screen, entry, summary, worldDirectory, worldProfile.get(), diff);
        } catch (IOException | RuntimeException exception) {
            minecraft.setScreen(new AlertScreen(
                    () -> minecraft.setScreen(screen),
                    Component.translatable("modqualitypicker.world.error"),
                    Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())
            ));
            return true;
        }
    }

    public static void openVanilla(WorldSelectionList.WorldListEntry entry) {
        BYPASS_CHECK.set(true);
        try {
            entry.joinWorld();
        } finally {
            BYPASS_CHECK.remove();
        }
    }

    static void acceptCurrentProfile(Screen returnScreen, WorldSelectionList.WorldListEntry entry, Path worldDirectory) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            RuntimeSelection currentSelection = QualityRuntime.currentSelection();
            QualityProfile currentProfile = QualityRuntime.captureCurrentProfile(currentSelection.activeProfileId(), currentSelection.activeProfileId());
            QualityRuntime.writeWorldProfile(worldDirectory, currentProfile);
        } catch (IOException | RuntimeException exception) {
            minecraft.setScreen(new AlertScreen(
                    () -> minecraft.setScreen(returnScreen),
                    Component.translatable("modqualitypicker.world.error"),
                    Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())
            ));
            return;
        }

        openVanilla(entry);
    }

    static void queueWorldProfile(Screen returnScreen, QualityProfile profile, String worldId) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            QualityRuntime.queueProfileChange(profile, "world-profile", worldId);
            ConfigFileManager.applyProfileConfigFiles(ProfilePaths.gameDirectory(), profile);
        } catch (IOException exception) {
            minecraft.setScreen(new AlertScreen(
                    () -> minecraft.setScreen(returnScreen),
                    Component.translatable("modqualitypicker.world.error"),
                    Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())
            ));
            return;
        }

        if (ModQualityPickerConfig.EXIT_AFTER_QUEUING_WORLD_PROFILE.get()) {
            minecraft.stop();
        } else {
            minecraft.setScreen(new AlertScreen(
                    () -> minecraft.setScreen(returnScreen),
                    Component.translatable("modqualitypicker.world.queued.title"),
                    Component.translatable("modqualitypicker.world.queued.body")
            ));
        }
    }

    private static boolean handleMismatch(
            SelectWorldScreen screen,
            WorldSelectionList.WorldListEntry entry,
            LevelSummary summary,
            Path worldDirectory,
            QualityProfile worldProfile,
            ProfileDiff diff
    ) {
        if (ModQualityPickerConfig.WORLD_MISMATCH_POLICY.get() == ModQualityPickerConfig.WorldMismatchPolicy.ALLOW_CURRENT) {
            acceptCurrentProfile(screen, entry, worldDirectory);
            return true;
        }

        if (ModQualityPickerConfig.WORLD_MISMATCH_POLICY.get() == ModQualityPickerConfig.WorldMismatchPolicy.REQUIRE_WORLD_PROFILE) {
            queueWorldProfile(screen, worldProfile, summary.getLevelId());
            return true;
        }

        Minecraft.getInstance().setScreen(new WorldProfileMismatchScreen(screen, entry, summary, worldDirectory, worldProfile, diff));
        return true;
    }
}
