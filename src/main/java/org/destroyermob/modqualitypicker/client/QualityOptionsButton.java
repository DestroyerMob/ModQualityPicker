package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;
import org.destroyermob.modqualitypicker.runtime.QualityRuntime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public final class QualityOptionsButton {
    private QualityOptionsButton() {
    }

    public static LayoutElement wrapOptionsContents(LayoutElement vanillaContents, OptionsScreen screen) {
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        layout.addChild(vanillaContents);
        layout.addChild(createQualityCycleButton(screen));
        return layout;
    }

    private static AbstractWidget createQualityCycleButton(OptionsScreen screen) {
        List<QualityProfile> profiles = QualityRuntime.profiles().listPresets();
        if (profiles.isEmpty()) {
            QualityProfile empty = QualityProfile.empty("none", "No Profiles");
            CycleButton<QualityProfile> button = CycleButton.builder(QualityOptionsButton::profileName)
                    .withValues(List.of(empty))
                    .withInitialValue(empty)
                    .create(0, 0, 200, 20, Component.translatable("modqualitypicker.settings.button"), (cycleButton, profile) -> {
                    });
            button.active = false;
            button.setTooltip(Tooltip.create(Component.translatable("modqualitypicker.settings.no_profiles")));
            return button;
        }

        QualityProfile initial = initialProfile(profiles);
        CycleButton<QualityProfile> button = CycleButton.builder(QualityOptionsButton::profileName)
                .withValues(profiles)
                .withInitialValue(initial)
                .create(0, 0, 200, 20, Component.translatable("modqualitypicker.settings.button"), (cycleButton, profile) -> queueProfile(screen, cycleButton, profile));
        button.setTooltip(initialTooltip(initial));
        return button;
    }

    private static Component profileName(QualityProfile profile) {
        return Component.literal(profile.displayName());
    }

    private static QualityProfile initialProfile(List<QualityProfile> profiles) {
        Optional<QualityProfile> queued = queuedProfile().flatMap(profile -> findProfile(profiles, profile.id()));
        if (queued.isPresent()) {
            return queued.get();
        }

        String activeId = QualityRuntime.currentSelection().activeProfileId();
        return findProfile(profiles, activeId).orElse(profiles.getFirst());
    }

    private static Optional<QualityProfile> queuedProfile() {
        try {
            return QualityRuntime.store()
                    .readPendingProfile(ProfilePaths.pendingProfile())
                    .map(change -> change.profile());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static Optional<QualityProfile> findProfile(List<QualityProfile> profiles, String id) {
        return profiles.stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst();
    }

    private static Tooltip initialTooltip(QualityProfile profile) {
        Optional<QualityProfile> queued = queuedProfile();
        if (queued.map(QualityProfile::id).filter(profile.id()::equals).isPresent()) {
            return Tooltip.create(Component.translatable("modqualitypicker.settings.queued", profile.displayName()));
        }
        return Tooltip.create(Component.translatable("modqualitypicker.settings.restart_note"));
    }

    private static void queueProfile(OptionsScreen screen, CycleButton<QualityProfile> button, QualityProfile profile) {
        try {
            QualityRuntime.queueProfileChange(profile, "client-options", "");
            button.setTooltip(Tooltip.create(Component.translatable("modqualitypicker.settings.queued", profile.displayName())));
        } catch (IOException exception) {
            screen.getMinecraft().setScreen(new AlertScreen(
                    () -> screen.getMinecraft().setScreen(screen),
                    Component.translatable("modqualitypicker.world.error"),
                    Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())
            ));
        }
    }
}
