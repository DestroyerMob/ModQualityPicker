package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.destroyermob.modqualitypicker.configfile.ConfigFileManager;
import org.destroyermob.modqualitypicker.export.ProfileExporter;
import org.destroyermob.modqualitypicker.profile.ConfigFileOverride;
import org.destroyermob.modqualitypicker.profile.ModState;
import org.destroyermob.modqualitypicker.profile.ProfileOption;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;
import org.destroyermob.modqualitypicker.runtime.QualityRuntime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class QualityProfileScreen extends Screen {
    private static final int ROW_HEIGHT = 34;
    private static final int PADDING = 18;
    private static final int TOP_BAR_HEIGHT = 78;
    private static final int FOOTER_HEIGHT = 34;

    private enum Tab {
        PROFILES,
        MODS
    }

    private final Screen parent;
    private final List<QualityProfile> profiles = new ArrayList<>();
    private final List<String> availableMods = new ArrayList<>();
    private final List<String> availableConfigs = new ArrayList<>();

    private int selectedProfileIndex;
    private int selectedModIndex;
    private int selectedConfigIndex;
    private int profileScroll;
    private int modScroll;
    private int configScroll;
    private Tab tab = Tab.MODS;
    private EditBox profileId;
    private QualityProfile draft = QualityProfile.empty("balanced", "Balanced");
    private Component status = CommonComponents.EMPTY;

    public QualityProfileScreen(Screen parent) {
        super(Component.translatable("modqualitypicker.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        reloadData();
        int left = PADDING;
        int right = this.width - PADDING;
        int center = this.width / 2;

        this.profileId = new EditBox(this.font, left, 30, Math.min(220, right - left - 280), 20, Component.translatable("modqualitypicker.editor.profile_id"));
        this.profileId.setHint(Component.translatable("modqualitypicker.editor.profile_id"));
        this.profileId.setMaxLength(64);
        this.profileId.setValue(this.draft.id());
        this.addRenderableWidget(this.profileId);

        addButton(Component.translatable("modqualitypicker.editor.save"), left + this.profileId.getWidth() + 8, 30, 70, this::saveDraft);
        addButton(Component.translatable("modqualitypicker.editor.queue"), left + this.profileId.getWidth() + 84, 30, 70, this::queueDraft);
        addButton(Component.translatable("modqualitypicker.editor.done"), right - 74, 30, 74, this::onClose);

        addButton(tabLabel(Tab.PROFILES), center - 103, 56, 100, () -> switchTab(Tab.PROFILES));
        addButton(tabLabel(Tab.MODS), center + 3, 56, 100, () -> switchTab(Tab.MODS));

        initActionButtons();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        drawPanel(guiGraphics, listX(), listTop(), listWidth(), listHeight());
        drawPanel(guiGraphics, detailX(), listTop(), detailWidth(), listHeight());
        drawList(guiGraphics, mouseX, mouseY);
        drawDetails(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, this.height - 22, 0xA0D8FF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && insideList(mouseX, mouseY)) {
            int visibleIndex = ((int) mouseY - listTop()) / ROW_HEIGHT;
            int absoluteIndex = scrollForTab() + visibleIndex;
            if (selectRow(absoluteIndex)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
        if (insideList(mouseX, mouseY)) {
            int next = scrollForTab() - (int) Math.signum(scrollDeltaY);
            setScrollForTab(next);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDeltaX, scrollDeltaY);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private void initActionButtons() {
        int x = detailX() + 12;
        int y = listTop() + 112;
        int w = Math.max(70, (detailWidth() - 36) / 2);
        int gap = 8;

        if (this.tab == Tab.PROFILES) {
            addButton(Component.translatable("modqualitypicker.editor.capture"), x, y, w, this::captureCurrent);
            addButton(Component.translatable("modqualitypicker.editor.export"), x + w + gap, y, w, this::exportPresets);
            addButton(Component.translatable("modqualitypicker.editor.move_up"), x, y + 26, w, () -> moveSelectedProfile(-1));
            addButton(Component.translatable("modqualitypicker.editor.move_down"), x + w + gap, y + 26, w, () -> moveSelectedProfile(1));
        } else if (this.tab == Tab.MODS) {
            addButton(Component.translatable("modqualitypicker.editor.toggle_enabled"), x, y, w, this::toggleSelectedMod);
            addButton(Component.translatable("modqualitypicker.editor.toggle_locked"), x + w + gap, y, w, this::toggleSelectedModLock);
            addButton(Component.translatable("modqualitypicker.editor.remove_override"), x, y + 26, w, this::removeSelectedModOverride);
        }
    }

    private void drawList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = listX();
        int y = listTop();
        int rowWidth = listWidth();
        int visibleRows = visibleRows();
        int scroll = scrollForTab();
        int count = itemCountForTab();

        drawString(guiGraphics, listTitle(), x + 8, y - 14, 0xFFFFFF);

        for (int i = 0; i < visibleRows; i++) {
            int index = scroll + i;
            if (index >= count) {
                break;
            }

            int rowTop = y + i * ROW_HEIGHT;
            boolean selected = index == selectedIndexForTab();
            boolean hovered = mouseX >= x && mouseX < x + rowWidth && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;
            int fill = selected ? 0x804C7A96 : hovered ? 0x503A3A3A : 0x20101010;
            guiGraphics.fill(x + 2, rowTop + 1, x + rowWidth - 2, rowTop + ROW_HEIGHT - 1, fill);
            drawRow(guiGraphics, index, x + 8, rowTop + 5, rowWidth - 16, selected);
        }

        if (count > visibleRows) {
            int barX = x + rowWidth - 6;
            int trackTop = y + 2;
            int trackHeight = visibleRows * ROW_HEIGHT - 4;
            int thumbHeight = Math.max(18, trackHeight * visibleRows / count);
            int maxScroll = maxScrollForTab();
            int thumbY = trackTop + (maxScroll <= 0 ? 0 : (trackHeight - thumbHeight) * scroll / maxScroll);
            guiGraphics.fill(barX, trackTop, barX + 3, trackTop + trackHeight, 0x70303030);
            guiGraphics.fill(barX, thumbY, barX + 3, thumbY + thumbHeight, 0xC0A0A0A0);
        }
    }

    private void drawRow(GuiGraphics guiGraphics, int index, int x, int y, int width, boolean selected) {
        int primary = selected ? 0xFFFFFF : 0xE0E0E0;
        int secondary = 0x9A9A9A;
        if (this.tab == Tab.PROFILES) {
            QualityProfile profile = this.profiles.get(index);
            drawString(guiGraphics, fit(profile.displayName(), width), x, y, primary);
            drawString(guiGraphics, fit(profile.id() + " | order " + profile.sortOrder(), width), x, y + 12, secondary);
        } else if (this.tab == Tab.MODS) {
            String modId = this.availableMods.get(index);
            ModState state = this.draft.mods().getOrDefault(modId, ModState.enabledChoice());
            String marker = state.enabled() ? "[ON] " : "[OFF] ";
            String lock = state.locked() ? " locked" : "";
            drawString(guiGraphics, fit(marker + modId, width), x, y, primary);
            drawString(guiGraphics, fit(stateDescription(state) + lock, width), x, y + 12, secondary);
        }
    }

    private void drawDetails(GuiGraphics guiGraphics) {
        int x = detailX() + 12;
        int y = listTop() + 12;
        int width = detailWidth() - 24;
        drawString(guiGraphics, detailTitle(), x, y, 0xFFFFFF);
        y += 18;

        for (Component line : detailLines()) {
            drawString(guiGraphics, fit(line.getString(), width), x, y, 0xD0D0D0);
            y += 13;
        }
    }

    private List<Component> detailLines() {
        if (this.tab == Tab.PROFILES) {
            return List.of(
                    Component.translatable("modqualitypicker.editor.profile_counts", this.draft.mods().size()),
                    Component.translatable("modqualitypicker.editor.profile_order", this.draft.sortOrder()),
                    Component.translatable("modqualitypicker.editor.active", QualityRuntime.currentSelection().activeProfileId()),
                    Component.translatable("modqualitypicker.editor.profile_hint")
            );
        }

        if (this.tab == Tab.MODS) {
            Optional<String> modId = selectedMod();
            if (modId.isEmpty()) {
                return List.of(Component.translatable("modqualitypicker.editor.no_mods"));
            }
            ModState state = this.draft.mods().getOrDefault(modId.get(), ModState.enabledChoice());
            return List.of(
                    Component.translatable("modqualitypicker.editor.mod_state", state.enabled(), state.locked()),
                    Component.translatable("modqualitypicker.editor.mod_position", this.selectedModIndex + 1, this.availableMods.size()),
                    Component.translatable("modqualitypicker.editor.mod_hint")
            );
        }

        return List.of();
    }

    private void reloadData() {
        String currentId = this.draft.id();
        this.profiles.clear();
        this.profiles.addAll(QualityRuntime.profiles().listPresets());
        if (this.profiles.isEmpty()) {
            this.profiles.add(QualityRuntime.captureCurrentProfile("balanced", "Balanced"));
        }

        this.selectedProfileIndex = clampIndex(this.selectedProfileIndex, this.profiles.size());
        for (int index = 0; index < this.profiles.size(); index++) {
            if (this.profiles.get(index).id().equals(currentId)) {
                this.selectedProfileIndex = index;
                break;
            }
        }
        this.draft = this.profiles.get(this.selectedProfileIndex);

        this.availableMods.clear();
        this.availableMods.addAll(QualityRuntime.currentSelection().enabledMods().keySet());
        this.availableMods.sort(String.CASE_INSENSITIVE_ORDER);
        this.selectedModIndex = clampIndex(this.selectedModIndex, this.availableMods.size());

        clampScrolls();
    }

    private void switchTab(Tab tab) {
        this.tab = tab;
        rebuildWidgets();
    }

    private boolean selectRow(int absoluteIndex) {
        if (absoluteIndex < 0 || absoluteIndex >= itemCountForTab()) {
            return false;
        }
        if (this.tab == Tab.PROFILES) {
            this.selectedProfileIndex = absoluteIndex;
            this.draft = this.profiles.get(absoluteIndex);
            rebuildWidgets();
        } else if (this.tab == Tab.MODS) {
            this.selectedModIndex = absoluteIndex;
        }
        return true;
    }

    private void saveDraft() {
        this.draft = copyProfile(this.profileId.getValue().isBlank() ? this.draft.id() : this.profileId.getValue(), this.draft.displayName(), this.draft.mods(), this.draft.configFiles(), this.draft.options());
        try {
            QualityRuntime.profiles().writePreset(this.draft);
            this.status = Component.translatable("modqualitypicker.message.profile_saved", this.draft.displayName());
            reloadData();
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void queueDraft() {
        try {
            QualityRuntime.queueProfileChange(this.draft, "client-menu", "");
            this.status = Component.translatable("modqualitypicker.message.profile_queued", this.draft.displayName());
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void captureCurrent() {
        String id = this.profileId.getValue().isBlank() ? "custom" : this.profileId.getValue();
        this.draft = QualityRuntime.captureCurrentProfile(id, id);
        saveDraft();
    }

    private void applyDraftConfigs() {
        try {
            ConfigFileManager.applyProfileConfigFiles(ProfilePaths.gameDirectory(), this.draft);
            this.status = Component.translatable("modqualitypicker.message.configs_applied", this.draft.displayName());
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void exportPresets() {
        try {
            Path destination = ProfileExporter.exportPresets(ProfilePaths.packExportRoot());
            this.status = Component.translatable("modqualitypicker.message.exported", destination.toString());
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void moveSelectedProfile(int direction) {
        int targetIndex = this.selectedProfileIndex + direction;
        if (targetIndex < 0 || targetIndex >= this.profiles.size()) {
            return;
        }

        List<QualityProfile> ordered = new ArrayList<>(this.profiles);
        Collections.swap(ordered, this.selectedProfileIndex, targetIndex);

        try {
            for (int index = 0; index < ordered.size(); index++) {
                QualityRuntime.profiles().writePreset(ordered.get(index).withSortOrder((index + 1) * 10));
            }
            this.draft = ordered.get(targetIndex).withSortOrder((targetIndex + 1) * 10);
            this.status = Component.translatable("modqualitypicker.message.profile_reordered", this.draft.displayName());
            reloadData();
            rebuildWidgets();
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void toggleSelectedMod() {
        selectedMod().ifPresent(modId -> {
            ModState current = this.draft.mods().getOrDefault(modId, ModState.enabledChoice());
            putModState(modId, new ModState(!current.enabled(), current.locked(), current.reason()));
        });
    }

    private void toggleSelectedModLock() {
        selectedMod().ifPresent(modId -> {
            ModState current = this.draft.mods().getOrDefault(modId, ModState.enabledChoice());
            putModState(modId, new ModState(current.enabled(), !current.locked(), current.reason()));
        });
    }

    private void removeSelectedModOverride() {
        selectedMod().ifPresent(modId -> {
            Map<String, ModState> mods = new LinkedHashMap<>(this.draft.mods());
            mods.remove(modId);
            this.draft = copyProfile(this.draft.id(), this.draft.displayName(), mods, this.draft.configFiles(), this.draft.options());
            this.status = Component.translatable("modqualitypicker.message.mod_removed", modId);
        });
    }

    private void addSelectedConfig() {
        selectedConfig().ifPresent(config -> {
            ConfigFileOverride existing = configOverride(config).orElse(new ConfigFileOverride(config, defaultMode(config), "", ""));
            putConfig(existing);
            this.status = Component.translatable("modqualitypicker.message.config_added", config);
        });
    }

    private void cycleSelectedConfigMode() {
        selectedConfig().ifPresent(config -> {
            ConfigFileOverride existing = configOverride(config).orElse(new ConfigFileOverride(config, defaultMode(config), "", ""));
            ConfigFileOverride.ConfigApplyMode next = switch (existing.mode()) {
                case APPLY_DIFF -> ConfigFileOverride.ConfigApplyMode.KEEP_PLAYER;
                case KEEP_PLAYER -> ConfigFileOverride.ConfigApplyMode.REPLACE_FILE;
                case REPLACE_FILE -> ConfigFileOverride.ConfigApplyMode.MERGE_TOML;
                case MERGE_TOML -> ConfigFileOverride.ConfigApplyMode.APPLY_DIFF;
            };
            putConfig(new ConfigFileOverride(config, next, existing.presetFile(), existing.sha256()));
        });
    }

    private void captureSelectedConfig() {
        selectedConfig().ifPresent(config -> {
            try {
                ConfigFileOverride existing = configOverride(config).orElse(new ConfigFileOverride(config, defaultMode(config), "", ""));
                ConfigFileOverride captured = ConfigFileManager.captureConfigFile(ProfilePaths.gameDirectory(), this.draft, config, existing.mode());
                putConfig(captured);
                this.status = Component.translatable("modqualitypicker.message.config_captured", config);
            } catch (IOException exception) {
                showError(exception);
            }
        });
    }

    private void removeSelectedConfig() {
        selectedConfig().ifPresent(config -> {
            List<ConfigFileOverride> configs = new ArrayList<>(this.draft.configFiles());
            configs.removeIf(item -> item.path().equals(config));
            this.draft = copyProfile(this.draft.id(), this.draft.displayName(), this.draft.mods(), configs, this.draft.options());
            this.status = Component.translatable("modqualitypicker.message.config_removed", config);
        });
    }

    private Optional<String> selectedMod() {
        if (this.availableMods.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(this.availableMods.get(this.selectedModIndex));
    }

    private Optional<String> selectedConfig() {
        if (this.availableConfigs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(this.availableConfigs.get(this.selectedConfigIndex));
    }

    private Optional<ConfigFileOverride> configOverride(String configPath) {
        return this.draft.configFiles().stream().filter(item -> item.path().equals(configPath)).findFirst();
    }

    private void putModState(String modId, ModState state) {
        Map<String, ModState> mods = new LinkedHashMap<>(this.draft.mods());
        mods.put(modId, state);
        this.draft = copyProfile(this.draft.id(), this.draft.displayName(), mods, this.draft.configFiles(), this.draft.options());
        this.status = Component.translatable("modqualitypicker.message.mod_updated", modId);
    }

    private void putConfig(ConfigFileOverride config) {
        List<ConfigFileOverride> configs = new ArrayList<>(this.draft.configFiles());
        configs.removeIf(item -> item.path().equals(config.path()));
        configs.add(config);
        this.draft = copyProfile(this.draft.id(), this.draft.displayName(), this.draft.mods(), configs, this.draft.options());
    }

    private ConfigFileOverride.ConfigApplyMode defaultMode(String config) {
        return ConfigFileOverride.ConfigApplyMode.APPLY_DIFF;
    }

    private QualityProfile copyProfile(String id, String displayName, Map<String, ModState> mods, List<ConfigFileOverride> configs, Map<String, ProfileOption> options) {
        String normalizedId = id == null || id.isBlank() ? this.draft.id() : id;
        return new QualityProfile(QualityProfile.SCHEMA_VERSION, normalizedId, displayName, this.draft.sortOrder(), this.draft.description(), mods, configs, options);
    }

    private int listX() {
        return PADDING;
    }

    private int listTop() {
        return TOP_BAR_HEIGHT + 10;
    }

    private int listWidth() {
        return Math.max(210, Math.min(330, this.width / 2 - 28));
    }

    private int listHeight() {
        return Math.max(ROW_HEIGHT, visibleRows() * ROW_HEIGHT);
    }

    private int visibleRows() {
        return Math.max(1, (this.height - TOP_BAR_HEIGHT - FOOTER_HEIGHT - 20) / ROW_HEIGHT);
    }

    private int detailX() {
        return listX() + listWidth() + 14;
    }

    private int detailWidth() {
        return Math.max(180, this.width - detailX() - PADDING);
    }

    private boolean insideList(double mouseX, double mouseY) {
        return mouseX >= listX() && mouseX < listX() + listWidth() && mouseY >= listTop() && mouseY < listTop() + listHeight();
    }

    private int itemCountForTab() {
        return switch (this.tab) {
            case PROFILES -> this.profiles.size();
            case MODS -> this.availableMods.size();
        };
    }

    private int selectedIndexForTab() {
        return switch (this.tab) {
            case PROFILES -> this.selectedProfileIndex;
            case MODS -> this.selectedModIndex;
        };
    }

    private int scrollForTab() {
        return switch (this.tab) {
            case PROFILES -> this.profileScroll;
            case MODS -> this.modScroll;
        };
    }

    private void setScrollForTab(int value) {
        int clamped = Math.max(0, Math.min(value, maxScrollForTab()));
        if (this.tab == Tab.PROFILES) {
            this.profileScroll = clamped;
        } else {
            this.modScroll = clamped;
        }
    }

    private int maxScrollForTab() {
        return Math.max(0, itemCountForTab() - visibleRows());
    }

    private void clampScrolls() {
        this.profileScroll = Math.max(0, Math.min(this.profileScroll, Math.max(0, this.profiles.size() - visibleRows())));
        this.modScroll = Math.max(0, Math.min(this.modScroll, Math.max(0, this.availableMods.size() - visibleRows())));
    }

    private int clampIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(index, size - 1));
    }

    private Component tabLabel(Tab target) {
        String key = switch (target) {
            case PROFILES -> "modqualitypicker.editor.tab_profiles";
            case MODS -> "modqualitypicker.editor.tab_mods";
        };
        return this.tab == target ? Component.literal("> ").append(Component.translatable(key)) : Component.translatable(key);
    }

    private Component listTitle() {
        return switch (this.tab) {
            case PROFILES -> Component.translatable("modqualitypicker.editor.tab_profiles");
            case MODS -> Component.translatable("modqualitypicker.editor.tab_mods");
        };
    }

    private Component detailTitle() {
        if (this.tab == Tab.PROFILES) {
            return Component.translatable("modqualitypicker.editor.selected", this.draft.displayName());
        }
        if (this.tab == Tab.MODS) {
            return selectedMod().map(Component::literal).orElse(Component.translatable("modqualitypicker.editor.no_mods"));
        }
        return CommonComponents.EMPTY;
    }

    private String stateDescription(ModState state) {
        return state.enabled() ? "enabled in profile" : "disabled in profile";
    }

    private String fit(String text, int width) {
        if (this.font.width(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        return this.font.plainSubstrByWidth(text, Math.max(1, width - this.font.width(ellipsis))) + ellipsis;
    }

    private void drawPanel(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, 0x90000000);
        guiGraphics.fill(x, y, x + width, y + 1, 0x80505050);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0x80505050);
    }

    private void drawString(GuiGraphics guiGraphics, String text, int x, int y, int color) {
        guiGraphics.drawString(this.font, text, x, y, color, false);
    }

    private void drawString(GuiGraphics guiGraphics, Component text, int x, int y, int color) {
        guiGraphics.drawString(this.font, text, x, y, color, false);
    }

    private void addButton(Component label, int x, int y, int width, Runnable action) {
        this.addRenderableWidget(Button.builder(label, button -> action.run()).bounds(x, y, width, 20).build());
    }

    private void showError(Exception exception) {
        this.minecraft.setScreen(new AlertScreen(
                () -> this.minecraft.setScreen(this),
                Component.translatable("modqualitypicker.world.error"),
                Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())
        ));
    }
}
