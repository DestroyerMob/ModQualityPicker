package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelSummary;
import org.destroyermob.modqualitypicker.profile.ProfileDiff;
import org.destroyermob.modqualitypicker.profile.QualityProfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WorldProfileMismatchScreen extends Screen {
    private static final int PANEL_FILL = 0xC0101010;
    private static final int PANEL_BORDER = 0x80505050;
    private static final int PANEL_PADDING = 8;
    private static final int LINE_HEIGHT = 12;
    private static final int SCROLL_STEP = 24;

    private record ScrollbarGeometry(int barX, int trackTop, int trackHeight, int thumbHeight, int thumbY) {
    }

    private final Screen parent;
    private final WorldSelectionList.WorldListEntry entry;
    private final LevelSummary summary;
    private final Path worldDirectory;
    private final QualityProfile worldProfile;
    private final ProfileDiff diff;
    private final List<Component> lines = new ArrayList<>();
    private final List<FormattedCharSequence> wrappedLines = new ArrayList<>();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int scrollbarGrabOffset;

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
        int y = Math.max(112, this.height - 54);
        configurePanel(y);
        this.addRenderableWidget(Button.builder(ClientText.text("modqualitypicker.world.continue_current", "Continue Current"), button -> QualityWorldOpenFlow.acceptCurrentProfile(this.parent, this.entry, this.worldDirectory))
                .bounds(center - 154, y, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(ClientText.text("modqualitypicker.world.use_world", "Use World Profile"), button -> QualityWorldOpenFlow.queueWorldProfile(this.parent, this.worldProfile, this.summary.getLevelId()))
                .bounds(center + 4, y, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(ClientText.text("modqualitypicker.world.choose_profile", "Choose Profile"), button -> this.minecraft.setScreen(QualityProfileScreen.forWorldProfile(this.parent, this.worldProfile, this.summary.getLevelId())))
                .bounds(center - 154, y + 26, 150, 20)
                .build());
        this.addRenderableWidget(Button.builder(ClientText.text("modqualitypicker.world.back", "Back"), button -> this.minecraft.setScreen(this.parent))
                .bounds(center + 4, y + 26, 150, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal(this.summary.getLevelName()), this.width / 2, 44, 0xA0A0A0);

        drawDetailPanel(guiGraphics);
        for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
        if (insidePanel(mouseX, mouseY) && maxScroll() > 0) {
            this.scrollOffset = Mth.clamp(this.scrollOffset - (int) Math.signum(scrollDeltaY) * SCROLL_STEP, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDeltaX, scrollDeltaY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && startScrollbarDrag(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && this.draggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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

    private void configurePanel(int buttonY) {
        this.panelWidth = Math.min(this.width - 32, 520);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = 64;
        this.panelHeight = Math.max(42, buttonY - this.panelY - 10);
        wrapLines();
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0, maxScroll());
    }

    private void wrapLines() {
        this.wrappedLines.clear();
        int textWidth = Math.max(1, this.panelWidth - PANEL_PADDING * 2 - 8);
        for (Component line : this.lines) {
            this.wrappedLines.addAll(this.font.split(line, textWidth));
            this.wrappedLines.add(FormattedCharSequence.EMPTY);
        }
        if (!this.wrappedLines.isEmpty()) {
            this.wrappedLines.removeLast();
        }
    }

    private void drawDetailPanel(GuiGraphics guiGraphics) {
        guiGraphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, PANEL_FILL);
        guiGraphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + 1, PANEL_BORDER);
        guiGraphics.fill(this.panelX, this.panelY + this.panelHeight - 1, this.panelX + this.panelWidth, this.panelY + this.panelHeight, PANEL_BORDER);
        guiGraphics.fill(this.panelX, this.panelY, this.panelX + 1, this.panelY + this.panelHeight, PANEL_BORDER);
        guiGraphics.fill(this.panelX + this.panelWidth - 1, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, PANEL_BORDER);

        int textX = this.panelX + PANEL_PADDING;
        int textY = this.panelY + PANEL_PADDING - this.scrollOffset;
        guiGraphics.enableScissor(this.panelX + 1, this.panelY + 1, this.panelX + this.panelWidth - 1, this.panelY + this.panelHeight - 1);
        try {
            for (FormattedCharSequence line : this.wrappedLines) {
                if (textY > this.panelY - LINE_HEIGHT && textY < this.panelY + this.panelHeight) {
                    guiGraphics.drawString(this.font, line, textX, textY, 0xD0D0D0, true);
                }
                textY += LINE_HEIGHT;
            }
        } finally {
            guiGraphics.disableScissor();
        }

        drawScrollbar(guiGraphics);
    }

    private void drawScrollbar(GuiGraphics guiGraphics) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) {
            return;
        }

        ScrollbarGeometry scrollbar = scrollbarGeometry(maxScroll);
        guiGraphics.fill(scrollbar.barX(), scrollbar.trackTop(), scrollbar.barX() + 3, scrollbar.trackTop() + scrollbar.trackHeight(), 0x70303030);
        guiGraphics.fill(scrollbar.barX(), scrollbar.thumbY(), scrollbar.barX() + 3, scrollbar.thumbY() + scrollbar.thumbHeight(), 0xC0A0A0A0);
    }

    private ScrollbarGeometry scrollbarGeometry(int maxScroll) {
        int trackTop = this.panelY + 3;
        int trackHeight = this.panelHeight - 6;
        int thumbHeight = Math.max(18, trackHeight * visibleTextHeight() / contentHeight());
        int thumbY = trackTop + (trackHeight - thumbHeight) * this.scrollOffset / maxScroll;
        return new ScrollbarGeometry(this.panelX + this.panelWidth - 6, trackTop, trackHeight, thumbHeight, thumbY);
    }

    private boolean startScrollbarDrag(double mouseX, double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) {
            return false;
        }

        ScrollbarGeometry scrollbar = scrollbarGeometry(maxScroll);
        if (!insideScrollbar(mouseX, mouseY, scrollbar)) {
            return false;
        }

        this.draggingScrollbar = true;
        this.scrollbarGrabOffset = scrollbarThumbContains(mouseX, mouseY, scrollbar)
                ? (int) mouseY - scrollbar.thumbY()
                : scrollbar.thumbHeight() / 2;
        updateScrollFromMouse(mouseY);
        return true;
    }

    private void updateScrollFromMouse(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) {
            return;
        }

        ScrollbarGeometry scrollbar = scrollbarGeometry(maxScroll);
        int travel = Math.max(1, scrollbar.trackHeight() - scrollbar.thumbHeight());
        int thumbTop = Mth.clamp((int) mouseY - this.scrollbarGrabOffset, scrollbar.trackTop(), scrollbar.trackTop() + travel);
        this.scrollOffset = Mth.clamp(Math.round((float) (thumbTop - scrollbar.trackTop()) * maxScroll / travel), 0, maxScroll);
    }

    private boolean insideScrollbar(double mouseX, double mouseY, ScrollbarGeometry scrollbar) {
        return mouseX >= scrollbar.barX() - 4
                && mouseX < scrollbar.barX() + 8
                && mouseY >= scrollbar.trackTop()
                && mouseY < scrollbar.trackTop() + scrollbar.trackHeight();
    }

    private boolean scrollbarThumbContains(double mouseX, double mouseY, ScrollbarGeometry scrollbar) {
        return mouseX >= scrollbar.barX() - 4
                && mouseX < scrollbar.barX() + 8
                && mouseY >= scrollbar.thumbY()
                && mouseY < scrollbar.thumbY() + scrollbar.thumbHeight();
    }

    private boolean insidePanel(double mouseX, double mouseY) {
        return mouseX >= this.panelX && mouseX < this.panelX + this.panelWidth && mouseY >= this.panelY && mouseY < this.panelY + this.panelHeight;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - visibleTextHeight());
    }

    private int contentHeight() {
        return this.wrappedLines.size() * LINE_HEIGHT;
    }

    private int visibleTextHeight() {
        return Math.max(1, this.panelHeight - PANEL_PADDING * 2);
    }
}
