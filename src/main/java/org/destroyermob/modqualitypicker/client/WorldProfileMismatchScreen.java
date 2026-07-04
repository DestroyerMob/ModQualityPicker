package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.destroyermob.modqualitypicker.profile.ProfileDiff;
import org.destroyermob.modqualitypicker.profile.QualityProfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WorldProfileMismatchScreen extends Screen {
    private final Screen parent;
    private final WorldSelectionList.WorldListEntry entry;
    private final LevelSummary summary;
    private final Path worldDirectory;
    private final QualityProfile worldProfile;
    private final ProfileDiff diff;
    private final List<Component> lines = new ArrayList<>();

    public WorldProfileMismatchScreen(
            Screen parent,
            WorldSelectionList.WorldListEntry entry,
            LevelSummary summary,
            Path worldDirectory,
            QualityProfile worldProfile,
            ProfileDiff diff
    ) {
        super(ClientText.text("modqualitypicker.world.title", "World Quality Profile"));
        this.parent = parent;
        this.entry = entry;
        this.summary = summary;
        this.worldDirectory = worldDirectory;
        this.worldProfile = worldProfile;
        this.diff = diff;
        buildLines();
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int y = Math.max(118, this.height / 2 + 20);
        this.addRenderableWidget(Button.builder(ClientText.text("modqualitypicker.world.continue_current", "Continue Current"), button -> QualityWorldOpenFlow.acceptCurrentProfile(this.parent, this.entry, this.worldDirectory))
                .bounds(center - 154, y, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(ClientText.text("modqualitypicker.world.use_world", "Use World Profile"), button -> QualityWorldOpenFlow.queueWorldProfile(this.parent, this.worldProfile, this.summary.getLevelId()))
                .bounds(center + 4, y, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(ClientText.text("modqualitypicker.world.back", "Back"), button -> this.minecraft.setScreen(this.parent))
                .bounds(center - 75, y + 26, 150, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal(this.summary.getLevelName()), this.width / 2, 44, 0xA0A0A0);

        int y = 70;
        for (Component line : this.lines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, 0xD0D0D0);
            y += 12;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private void buildLines() {
        this.lines.add(ClientText.text("modqualitypicker.world.diff.profile", "World profile: %s", this.worldProfile.displayName()));
        if (!this.diff.missingEnabledMods().isEmpty()) {
            this.lines.add(ClientText.text("modqualitypicker.world.diff.missing", "Missing required by world: %s", String.join(", ", this.diff.missingEnabledMods())));
        }
        if (!this.diff.loadedDisabledMods().isEmpty()) {
            this.lines.add(ClientText.text("modqualitypicker.world.diff.disabled_loaded", "Loaded but disabled by world: %s", String.join(", ", this.diff.loadedDisabledMods())));
        }
        if (!this.diff.newlyLoadedMods().isEmpty()) {
            this.lines.add(ClientText.text("modqualitypicker.world.diff.newly_loaded", "Newly loaded mods/blocks for this world: %s", String.join(", ", this.diff.newlyLoadedMods())));
        }
        if (!this.diff.configHashMismatches().isEmpty()) {
            this.lines.add(ClientText.text("modqualitypicker.world.diff.configs", "Config differences: %s", String.join(", ", this.diff.configHashMismatches())));
        }
        if (this.lines.size() == 1) {
            this.lines.add(Component.literal(this.worldDirectory.getFileName().toString()));
        }
    }
}
