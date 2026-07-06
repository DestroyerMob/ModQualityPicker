package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class QualityProfileScreen extends Screen {
    private static final int ROW_HEIGHT = 34;
    private static final int PADDING = 18;
    private static final int COMPACT_PADDING = 8;
    private static final int FOOTER_HEIGHT = 34;
    private static final int SEARCH_HEIGHT = 20;
    private static final int SEARCH_GAP = 8;
    private static final int PANEL_GAP = 10;
    private static final int STACKED_WIDTH = 360;
    private static final int DETAIL_INSET = 12;
    private static final int DETAIL_LINE_STEP = 13;
    private static final int DETAIL_ACTION_GAP = 8;
    private static final int BUTTON_ROW_STEP = 24;
    private static final int PANEL_FILL = 0xD0000000;
    private static final int PANEL_BORDER = 0xFF505050;
    private static final int ROW_FILL = 0x70101010;
    private static final int ROW_HOVER_FILL = 0xA03A3A3A;
    private static final int ROW_SELECTED_FILL = 0xC04C7A96;

    private enum Tab {
        PROFILES,
        MODS
    }

    private record ActionButton(Component label, Runnable action) {
    }

    private final Screen parent;
    private final QualityProfile initialProfile;
    private final String queueReason;
    private final String sourceWorldId;
    private final Set<String> temporaryProfileIds = new HashSet<>();
    private final List<QualityProfile> profiles = new ArrayList<>();
    private final List<String> catalogMods = new ArrayList<>();
    private final List<String> availableMods = new ArrayList<>();
    private final List<String> filteredMods = new ArrayList<>();
    private final List<String> availableConfigs = new ArrayList<>();

    private int selectedProfileIndex;
    private int selectedModIndex;
    private int selectedConfigIndex;
    private int profileScroll;
    private int modScroll;
    private int configScroll;
    private Tab tab = Tab.MODS;
    private EditBox profileName;
    private EditBox modSearch;
    private Button queueButton;
    private Button modEnabledButton;
    private Button modLockedButton;
    private boolean queueButtonShiftMode;
    private String modSearchText = "";
    private boolean catalogLoaded;
    private String activeProfileId = "balanced";
    private String activeProfileLabel = "Balanced";
    private QualityProfile draft = QualityProfile.empty("balanced", "Balanced");
    private boolean initialSelectionPending = true;
    private boolean draftTemporary;
    private Component status = CommonComponents.EMPTY;

    public QualityProfileScreen(Screen parent) {
        this(parent, null, "client-menu", "");
    }

    static QualityProfileScreen forWorldProfile(Screen parent, QualityProfile worldProfile, String worldId) {
        return new QualityProfileScreen(parent, worldProfile, "world-profile", worldId);
    }

    private QualityProfileScreen(Screen parent, QualityProfile initialProfile, String queueReason, String sourceWorldId) {
        super(Component.translatable("modqualitypicker.title"));
        this.parent = parent;
        this.initialProfile = initialProfile;
        this.queueReason = queueReason == null || queueReason.isBlank() ? "client-menu" : queueReason;
        this.sourceWorldId = sourceWorldId == null ? "" : sourceWorldId;
    }

    @Override
    protected void init() {
        this.queueButton = null;
        this.modEnabledButton = null;
        this.modLockedButton = null;
        reloadData();
        initTopControls();

        if (this.tab == Tab.MODS) {
            this.modSearch = new EditBox(this.font, listX(), modSearchY(), listWidth(), SEARCH_HEIGHT, Component.translatable("modqualitypicker.editor.search_mods"));
            this.modSearch.setHint(Component.translatable("modqualitypicker.editor.search_mods"));
            this.modSearch.setMaxLength(80);
            this.modSearch.setValue(this.modSearchText);
            this.modSearch.setResponder(this::setModSearchText);
            this.addRenderableWidget(this.modSearch);
        } else {
            this.modSearch = null;
        }

        initActionButtons();
        updateModActionButtons();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        drawPanel(guiGraphics, listX(), listTop(), listWidth(), listHeight());
        drawPanel(guiGraphics, detailX(), detailTop(), detailWidth(), detailHeight());
        drawList(guiGraphics, mouseX, mouseY);
        drawDetails(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, this.height - 22, 0xA0D8FF);
        updateQueueButtonLabel();
        for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
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
            int step = hasShiftDown() ? visibleRows() : (this.tab == Tab.MODS ? 3 : 1);
            int next = scrollForTab() - (int) Math.signum(scrollDeltaY) * step;
            setScrollForTab(next);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDeltaX, scrollDeltaY);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private void initTopControls() {
        int x = padding();
        int contentWidth = contentWidth();
        int gap = 4;
        int firstRowY = 30;

        if (compactTopBar()) {
            int buttonWidth = Math.max(1, (contentWidth - gap) / 2);
            addButton(Component.translatable("modqualitypicker.editor.save"), x, firstRowY, buttonWidth, this::saveDraft);
            this.queueButton = addButton(queueButtonLabel(), x + buttonWidth + gap, firstRowY, Math.max(1, contentWidth - buttonWidth - gap), this::queueOrRestartDraft);

            int secondRowY = firstRowY + 24;
            addButton(Component.translatable("modqualitypicker.editor.set_default"), x, secondRowY, buttonWidth, this::setSelectedProfileDefault);
            addButton(Component.translatable("modqualitypicker.editor.done"), x + buttonWidth + gap, secondRowY, Math.max(1, contentWidth - buttonWidth - gap), this::onClose);

            int tabY = secondRowY + 24;
            int tabWidth = Math.max(1, (contentWidth - gap) / 2);
            addButton(tabLabel(Tab.PROFILES), x, tabY, tabWidth, () -> switchTab(Tab.PROFILES));
            addButton(tabLabel(Tab.MODS), x + tabWidth + gap, tabY, Math.max(1, contentWidth - tabWidth - gap), () -> switchTab(Tab.MODS));
            return;
        }

        int saveWidth = 58;
        int queueWidth = 84;
        int defaultWidth = 92;
        int doneWidth = 64;
        int nextX = x;
        addButton(Component.translatable("modqualitypicker.editor.save"), nextX, firstRowY, saveWidth, this::saveDraft);
        nextX += saveWidth + gap;
        this.queueButton = addButton(queueButtonLabel(), nextX, firstRowY, queueWidth, this::queueOrRestartDraft);
        nextX += queueWidth + gap;
        addButton(Component.translatable("modqualitypicker.editor.set_default"), nextX, firstRowY, defaultWidth, this::setSelectedProfileDefault);
        addButton(Component.translatable("modqualitypicker.editor.done"), x + contentWidth - doneWidth, firstRowY, doneWidth, this::onClose);

        int tabY = 56;
        int tabWidth = Math.min(100, Math.max(1, (contentWidth - gap) / 2));
        int tabX = x + Math.max(0, (contentWidth - tabWidth * 2 - gap) / 2);
        addButton(tabLabel(Tab.PROFILES), tabX, tabY, tabWidth, () -> switchTab(Tab.PROFILES));
        addButton(tabLabel(Tab.MODS), tabX + tabWidth + gap, tabY, tabWidth, () -> switchTab(Tab.MODS));
    }
    private EditBox newProfileNameBox(int x, int y, int width) {
        EditBox box = new EditBox(this.font, x, y, Math.max(1, width), 20, Component.translatable("modqualitypicker.editor.profile_name"));
        box.setHint(Component.translatable("modqualitypicker.editor.profile_name"));
        box.setMaxLength(64);
        box.setValue(this.draft.displayName());
        this.addRenderableWidget(box);
        return box;
    }

    private void initActionButtons() {
        addActionButtons(actionButtons());
    }

    private List<ActionButton> actionButtons() {
        if (this.tab == Tab.PROFILES) {
            return List.of(
                    new ActionButton(Component.translatable("modqualitypicker.editor.edit_mods"), () -> switchTab(Tab.MODS)),
                    new ActionButton(Component.translatable("modqualitypicker.editor.new_profile"), this::createNewProfile),
                    new ActionButton(Component.translatable("modqualitypicker.editor.duplicate_profile"), this::duplicateSelectedProfile),
                    new ActionButton(Component.translatable("modqualitypicker.editor.rename"), this::renameSelectedProfile),
                    new ActionButton(Component.translatable("modqualitypicker.editor.set_default"), this::setSelectedProfileDefault),
                    new ActionButton(Component.translatable("modqualitypicker.editor.delete_profile"), this::confirmDeleteSelectedProfile),
                    new ActionButton(Component.translatable("modqualitypicker.editor.capture"), this::captureCurrent),
                    new ActionButton(Component.translatable("modqualitypicker.editor.export"), this::exportPresets),
                    new ActionButton(Component.translatable("modqualitypicker.editor.move_up"), () -> moveSelectedProfile(-1)),
                    new ActionButton(Component.translatable("modqualitypicker.editor.move_down"), () -> moveSelectedProfile(1))
            );
        }
        if (this.tab == Tab.MODS) {
            return List.of(
                    new ActionButton(modEnabledButtonLabel(), this::toggleSelectedMod),
                    new ActionButton(modLockedButtonLabel(), this::toggleSelectedModLock)
            );
        }
        return List.of();
    }

    private void addActionButtons(List<ActionButton> buttons) {
        if (buttons.isEmpty()) {
            return;
        }

        int contentWidth = Math.max(1, detailWidth() - 24);
        int gap = 6;
        int columns = actionButtonColumns(contentWidth);
        int rows = actionButtonRows(buttons.size(), columns);
        int buttonWidth = Math.max(1, (contentWidth - gap * (columns - 1)) / columns);
        int x = detailX() + DETAIL_INSET;
        int y = actionButtonY(rows);

        for (int index = 0; index < buttons.size(); index++) {
            ActionButton actionButton = buttons.get(index);
            int column = index % columns;
            int row = index / columns;
            int buttonX = x + column * (buttonWidth + gap);
            int rowWidth = column == columns - 1 ? Math.max(1, contentWidth - column * (buttonWidth + gap)) : buttonWidth;
            Button button = addButton(actionButton.label(), buttonX, y + row * BUTTON_ROW_STEP, rowWidth, actionButton.action());
            if (this.tab == Tab.MODS) {
                if (index == 0) {
                    this.modEnabledButton = button;
                } else if (index == 1) {
                    this.modLockedButton = button;
                }
            }
        }
    }

    private int actionButtonY(int rows) {
        int buttonAreaHeight = actionButtonAreaHeight(rows);
        int y = detailTop() + detailHeight() - buttonAreaHeight - DETAIL_INSET;
        int preferred = Math.max(detailTop() + DETAIL_INSET + 24, y);
        int maxY = Math.max(0, bodyBottom() - buttonAreaHeight - 4);
        return Math.max(detailTop() + DETAIL_INSET, Math.min(preferred, maxY));
    }

    private int actionButtonColumns(int contentWidth) {
        return contentWidth >= 300 ? 3 : contentWidth >= 180 ? 2 : 1;
    }

    private int actionButtonRows(int count, int columns) {
        if (count <= 0) {
            return 0;
        }
        return (count + columns - 1) / columns;
    }

    private int actionButtonAreaHeight(int rows) {
        if (rows <= 0) {
            return 0;
        }
        return (rows - 1) * BUTTON_ROW_STEP + 20;
    }

    private void drawList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = listX();
        int y = listTop();
        int rowWidth = listWidth();
        int visibleRows = visibleRows();
        int scroll = scrollForTab();
        int count = itemCountForTab();

        int titleY = this.tab == Tab.MODS ? modSearchY() - 14 : y - 14;
        drawString(guiGraphics, listTitle(), x + 8, titleY, 0xFFFFFF);

        guiGraphics.enableScissor(x, y, x + rowWidth, y + visibleRows * ROW_HEIGHT);
        try {
            for (int i = 0; i < visibleRows; i++) {
                int index = scroll + i;
                if (index >= count) {
                    break;
                }

                int rowTop = y + i * ROW_HEIGHT;
                boolean selected = index == selectedIndexForTab();
                boolean hovered = mouseX >= x && mouseX < x + rowWidth && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;
                int fill = selected ? ROW_SELECTED_FILL : hovered ? ROW_HOVER_FILL : ROW_FILL;
                guiGraphics.fill(x + 2, rowTop + 1, x + rowWidth - 2, rowTop + ROW_HEIGHT - 1, fill);
                drawRow(guiGraphics, index, x + 8, rowTop + 5, rowWidth - 16, selected);
            }
        } finally {
            guiGraphics.disableScissor();
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
            drawString(guiGraphics, fit(Component.translatable("modqualitypicker.editor.profile_summary", profile.mods().size(), profile.sortOrder()).getString(), width), x, y + 12, secondary);
        } else if (this.tab == Tab.MODS) {
            String modId = this.filteredMods.get(index);
            ModState state = modState(modId);
            String marker = state.enabled() ? "[ON] " : "[OFF] ";
            String lock = state.locked() ? " locked" : "";
            drawString(guiGraphics, fit(marker + modId, width), x, y, primary);
            drawString(guiGraphics, fit(stateDescription(state) + lock, width), x, y + 12, secondary);
        }
    }

    private void drawDetails(GuiGraphics guiGraphics) {
        int x = detailX() + DETAIL_INSET;
        int y = detailTop() + DETAIL_INSET;
        int width = detailWidth() - DETAIL_INSET * 2;
        int rows = actionButtonRows(actionButtons().size(), actionButtonColumns(Math.max(1, width)));
        int textBottom = actionButtonY(rows) - DETAIL_ACTION_GAP;
        if (y + this.font.lineHeight > textBottom) {
            return;
        }
        drawString(guiGraphics, detailTitle(), x, y, 0xFFFFFF);
        y += 18;

        for (Component line : detailLines()) {
            if (y + this.font.lineHeight > textBottom) {
                break;
            }
            drawString(guiGraphics, fit(line.getString(), width), x, y, 0xD0D0D0);
            y += DETAIL_LINE_STEP;
        }
    }

    private List<Component> detailLines() {
        if (this.tab == Tab.PROFILES) {
            return List.of(
                    Component.translatable("modqualitypicker.editor.profile_counts", this.draft.mods().size()),
                    Component.translatable("modqualitypicker.editor.profile_internal_id", this.draft.id()),
                    Component.translatable("modqualitypicker.editor.profile_order", this.draft.sortOrder()),
                    Component.translatable("modqualitypicker.editor.active", this.activeProfileLabel),
                    Component.translatable("modqualitypicker.editor.profile_hint"),
                    Component.translatable("modqualitypicker.editor.queue_shift_hint")
            );
        }

        if (this.tab == Tab.MODS) {
            Optional<String> modId = selectedMod();
            if (modId.isEmpty()) {
                return List.of(this.availableMods.isEmpty()
                    ? Component.translatable("modqualitypicker.editor.no_mods")
                    : Component.translatable("modqualitypicker.editor.no_matching_mods"));
            }
            ModState state = modState(modId.get());
            return List.of(
                    Component.translatable("modqualitypicker.editor.mod_state", state.enabled(), state.locked()),
                    Component.translatable("modqualitypicker.editor.mod_position", this.selectedModIndex + 1, this.filteredMods.size()),
                    Component.translatable("modqualitypicker.editor.mod_hint"),
                    Component.translatable("modqualitypicker.editor.queue_shift_hint")
            );
        }

        return List.of();
    }

    private void reloadData() {
        String currentId = this.draft.id();
        String currentMod = selectedMod().orElse("");
        this.activeProfileId = QualityRuntime.activeProfileId();
        this.profiles.clear();
        this.temporaryProfileIds.clear();
        this.profiles.addAll(QualityRuntime.profiles().listPresets());

        String preferredId = this.initialSelectionPending ? this.activeProfileId : currentId;
        addAppliedTemporaryProfile();
        if (this.initialProfile != null) {
            preferredId = addInitialProfileOption(preferredId);
        }

        if (this.profiles.isEmpty()) {
            this.profiles.add(QualityRuntime.captureCurrentProfile("balanced", "Balanced"));
        }
        this.activeProfileLabel = this.profiles.stream()
                .filter(profile -> profile.id().equals(this.activeProfileId))
                .findFirst()
                .map(QualityProfile::displayName)
                .orElse(this.activeProfileId);

        this.selectedProfileIndex = clampIndex(this.selectedProfileIndex, this.profiles.size());
        for (int index = 0; index < this.profiles.size(); index++) {
            if (this.profiles.get(index).id().equals(preferredId)) {
                this.selectedProfileIndex = index;
                break;
            }
        }
        this.draft = this.profiles.get(this.selectedProfileIndex);
        this.draftTemporary = this.temporaryProfileIds.contains(this.draft.id());
        this.initialSelectionPending = false;

        if (!this.catalogLoaded) {
            this.catalogMods.clear();
            this.catalogMods.addAll(QualityRuntime.availableModIds(this.draft));
            this.catalogLoaded = true;
        }

        Set<String> modIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        modIds.addAll(this.catalogMods);
        for (QualityProfile profile : this.profiles) {
            modIds.addAll(profile.mods().keySet());
        }

        this.availableMods.clear();
        this.availableMods.addAll(modIds);
        applyModFilter(currentMod);

        clampScrolls();
    }

    private void addAppliedTemporaryProfile() {
        QualityRuntime.appliedProfile()
                .filter(profile -> profile.id().equals(this.activeProfileId))
                .filter(profile -> !profileIdExists(profile.id()))
                .ifPresent(profile -> addProfileOption(profile, true));
    }

    private String addInitialProfileOption(String fallbackPreferredId) {
        try {
            Optional<QualityProfile> matchedPreset = QualityRuntime.findMatchingPreset(this.initialProfile);
            if (matchedPreset.isPresent()) {
                return this.initialSelectionPending ? matchedPreset.get().id() : fallbackPreferredId;
            }
        } catch (IOException exception) {
            this.status = Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }

        QualityProfile custom = QualityRuntime.customProfile(this.initialProfile);
        addProfileOption(custom, true);
        return this.initialSelectionPending ? custom.id() : fallbackPreferredId;
    }

    private void addProfileOption(QualityProfile profile, boolean temporary) {
        if (profileIdExists(profile.id())) {
            return;
        }
        this.profiles.add(profile);
        if (temporary) {
            this.temporaryProfileIds.add(profile.id());
        }
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
            this.draftTemporary = this.temporaryProfileIds.contains(this.draft.id());
            rebuildWidgets();
        } else if (this.tab == Tab.MODS) {
            this.selectedModIndex = absoluteIndex;
            updateModActionButtons();
        }
        return true;
    }

    private void saveDraft() {
        String displayName = this.draftTemporary ? uniqueDisplayName(this.draft.displayName()) : this.draft.displayName();
        try {
            String profileId = this.draftTemporary ? uniqueProfileId(displayName) : this.draft.id();
            this.draft = QualityRuntime.withRequiredDependencies(copyProfile(profileId, displayName, this.draft.mods(), this.draft.configFiles(), this.draft.options()));
            if (this.draftTemporary) {
                this.draft = this.draft.withSortOrder(QualityRuntime.profiles().nextSortOrder());
                this.draftTemporary = false;
            }
            QualityRuntime.profiles().writePreset(this.draft);
            this.status = Component.translatable("modqualitypicker.message.profile_saved", this.draft.displayName());
            reloadData();
            rebuildWidgets();
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void queueOrRestartDraft() {
        if (hasShiftDown()) {
            confirmQueueAndQuit();
        } else {
            queueDraft();
        }
    }

    private void queueDraft() {
        if (queueDraftChange()) {
            reloadData();
            rebuildWidgets();
        }
    }

    private void confirmQueueAndQuit() {
        QualityProfile profile = this.draft;
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    this.minecraft.setScreen(this);
                    if (confirmed) {
                        queueDraftAndQuit();
                    }
                },
                Component.translatable("modqualitypicker.editor.queue_restart_confirm_title"),
                Component.translatable("modqualitypicker.editor.queue_restart_confirm", profile.displayName()),
                Component.translatable("modqualitypicker.editor.queue_restart"),
                CommonComponents.GUI_CANCEL
        ));
    }

    private void queueDraftAndQuit() {
        if (queueDraftChange()) {
            this.minecraft.stop();
        }
    }

    private boolean queueDraftChange() {
        try {
            boolean temporary = this.draftTemporary;
            this.draft = QualityRuntime.withRequiredDependencies(this.draft);
            if (!temporary) {
                QualityRuntime.profiles().writePreset(this.draft);
            }
            QualityRuntime.queueProfileChange(this.draft, this.queueReason, this.sourceWorldId);
            this.draftTemporary = temporary;
            this.status = Component.translatable("modqualitypicker.message.profile_queued", this.draft.displayName());
            return true;
        } catch (IOException exception) {
            showError(exception);
            return false;
        }
    }

    private void captureCurrent() {
        String displayName = uniqueDisplayName("Captured Profile");
        String id = uniqueProfileId(displayName);
        this.draft = QualityRuntime.captureCurrentProfile(id, displayName);
        writeCreatedProfile(Component.translatable("modqualitypicker.message.profile_captured", this.draft.displayName()));
    }

    private void createNewProfile() {
        String displayName = uniqueDisplayName("New Profile");
        String id = uniqueProfileId(displayName);
        this.draft = new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                id,
                displayName,
                QualityRuntime.profiles().nextSortOrder(),
                "Created from scratch.",
                Map.of(),
                List.of(),
                Map.of()
        );

        writeCreatedProfile(Component.translatable("modqualitypicker.message.profile_created", this.draft.displayName()));
    }

    private void duplicateSelectedProfile() {
        QualityProfile source = this.draft;
        String displayName = uniqueDisplayName(source.displayName() + " Copy");
        String id = uniqueProfileId(displayName);
        this.draft = new QualityProfile(
                QualityProfile.SCHEMA_VERSION,
                id,
                displayName,
                QualityRuntime.profiles().nextSortOrder(),
                "Copied from " + source.displayName() + ".",
                source.mods(),
                source.configFiles(),
                source.options()
        );

        writeCreatedProfile(Component.translatable("modqualitypicker.message.profile_duplicated", source.displayName(), this.draft.displayName()));
    }

    private void writeCreatedProfile(Component success) {
        try {
            QualityRuntime.profiles().writePreset(this.draft);
            this.status = success;
            reloadData();
            this.tab = Tab.PROFILES;
            rebuildWidgets();
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void renameSelectedProfile() {
        this.minecraft.setScreen(new RenameProfileScreen(this, this.draft.displayName(), this::renameSelectedProfileTo));
    }

    private void renameSelectedProfileTo(String name) {
        String displayName = profileNameOrDefault(name);
        if (profileDisplayNameExists(displayName, this.draft.id())) {
            this.status = Component.translatable("modqualitypicker.message.profile_name_exists", displayName);
            return;
        }

        this.draft = this.draft.withDisplayName(displayName);
        if (this.draftTemporary) {
            if (this.selectedProfileIndex >= 0 && this.selectedProfileIndex < this.profiles.size()) {
                this.profiles.set(this.selectedProfileIndex, this.draft);
            }
            this.status = Component.translatable("modqualitypicker.message.profile_renamed", this.draft.displayName());
            return;
        }

        try {
            QualityRuntime.profiles().writePreset(this.draft);
            this.status = Component.translatable("modqualitypicker.message.profile_renamed", this.draft.displayName());
            reloadData();
            rebuildWidgets();
        } catch (IOException exception) {
            showError(exception);
        }
    }

    private void setSelectedProfileDefault() {
        try {
            QualityProfile profile = this.draft;
            if (this.draftTemporary) {
                String displayName = uniqueDisplayName(profile.displayName());
                String profileId = uniqueProfileId(displayName);
                profile = QualityRuntime.withRequiredDependencies(copyProfile(profileId, displayName, profile.mods(), profile.configFiles(), profile.options()))
                        .withSortOrder(QualityRuntime.profiles().nextSortOrder());
                QualityRuntime.profiles().writePreset(profile);
                this.draft = profile;
                this.draftTemporary = false;
            }

            QualityRuntime.setActiveProfileId(profile.id());
            this.activeProfileId = profile.id();
            this.activeProfileLabel = profile.displayName();
            this.status = Component.translatable("modqualitypicker.message.profile_default", profile.displayName());
            reloadData();
            rebuildWidgets();
        } catch (IOException exception) {
            showError(exception);
        }
    }
    private void confirmDeleteSelectedProfile() {
        if (this.profiles.size() <= 1) {
            this.status = Component.translatable("modqualitypicker.message.profile_delete_last");
            return;
        }

        if (isDefaultProfile(this.draft)) {
            this.status = Component.translatable("modqualitypicker.message.profile_delete_default", this.draft.displayName());
            return;
        }

        QualityProfile profile = this.draft;
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    this.minecraft.setScreen(this);
                    if (confirmed) {
                        deleteProfile(profile);
                    }
                },
                Component.translatable("modqualitypicker.editor.delete_profile"),
                Component.translatable("modqualitypicker.editor.delete_profile_confirm", profile.displayName()),
                Component.translatable("modqualitypicker.editor.delete_profile"),
                CommonComponents.GUI_CANCEL
        ));
    }

    private void deleteProfile(QualityProfile profile) {
        try {
            if (QualityRuntime.profiles().deletePreset(profile)) {
                this.status = Component.translatable("modqualitypicker.message.profile_deleted", profile.displayName());
            }
            this.selectedProfileIndex = Math.max(0, this.selectedProfileIndex - 1);
            reloadData();
            rebuildWidgets();
        } catch (IOException exception) {
            showError(exception);
        }
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
            ModState current = modState(modId);
            putModState(modId, new ModState(!current.enabled(), current.locked(), current.reason()));
        });
    }

    private void toggleSelectedModLock() {
        selectedMod().ifPresent(modId -> {
            ModState current = modState(modId);
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
        if (this.filteredMods.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(this.filteredMods.get(this.selectedModIndex));
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

    private Optional<ModState> selectedModState() {
        return selectedMod().map(this::modState);
    }

    private ModState modState(String modId) {
        return this.draft.mods().getOrDefault(modId, ModState.implicitChoice());
    }

    private void putModState(String modId, ModState state) {
        Map<String, ModState> mods = new LinkedHashMap<>(this.draft.mods());
        mods.put(modId, state);
        this.draft = copyProfile(this.draft.id(), this.draft.displayName(), mods, this.draft.configFiles(), this.draft.options());
        this.status = Component.translatable("modqualitypicker.message.mod_updated", modId);
        updateModActionButtons();
    }

    private boolean isDefaultProfile(QualityProfile profile) {
        return profile.id().equals(this.activeProfileId);
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

    private void setModSearchText(String value) {
        String selected = selectedMod().orElse("");
        this.modSearchText = value == null ? "" : value;
        applyModFilter(selected);
    }

    private void applyModFilter(String preserveModId) {
        this.filteredMods.clear();
        String query = this.modSearchText.trim().toLowerCase(Locale.ROOT);
        for (String modId : this.availableMods) {
            if (query.isEmpty() || modId.toLowerCase(Locale.ROOT).contains(query)) {
                this.filteredMods.add(modId);
            }
        }

        int preservedIndex = preserveModId.isBlank() ? -1 : this.filteredMods.indexOf(preserveModId);
        this.selectedModIndex = preservedIndex >= 0 ? preservedIndex : clampIndex(this.selectedModIndex, this.filteredMods.size());
        this.modScroll = Math.max(0, Math.min(this.modScroll, Math.max(0, this.filteredMods.size() - visibleRows())));
        updateModActionButtons();
    }

    private String profileNameFromInput(String fallback) {
        String value = this.profileName == null ? "" : this.profileName.getValue();
        if (value == null || value.isBlank()) {
            return fallback == null || fallback.isBlank() ? "New Profile" : fallback.trim();
        }
        return value.trim();
    }

    private String uniqueDisplayName(String baseName) {
        String base = profileNameOrDefault(baseName);
        String displayName = base;
        int suffix = 2;
        while (profileDisplayNameExists(displayName, "")) {
            displayName = base + " " + suffix;
            suffix++;
        }
        return displayName;
    }

    private boolean profileDisplayNameExists(String displayName, String exceptId) {
        String normalized = profileNameOrDefault(displayName);
        return this.profiles.stream().anyMatch(profile ->
                !profile.id().equals(exceptId) && profile.displayName().equalsIgnoreCase(normalized));
    }

    private String profileNameOrDefault(String value) {
        return value == null || value.isBlank() ? "New Profile" : value.trim();
    }

    private void focusProfileName() {
        if (this.profileName != null) {
            this.setFocused(this.profileName);
            this.profileName.setFocused(true);
        }
    }

    private String uniqueProfileId(String baseId) {
        String base = QualityProfile.empty(baseId, baseId).id();
        String id = base;
        int suffix = 2;
        while (profileIdExists(id)) {
            id = base + "_" + suffix;
            suffix++;
        }
        return id;
    }

    private boolean profileIdExists(String id) {
        return this.profiles.stream().anyMatch(profile -> profile.id().equals(id));
    }

    private int listX() {
        return padding();
    }

    private int listTop() {
        return contentTop() + (this.tab == Tab.MODS ? SEARCH_HEIGHT + SEARCH_GAP : 0);
    }

    private int listWidth() {
        if (stackedLayout()) {
            return contentWidth();
        }
        int available = Math.max(1, contentWidth() - PANEL_GAP);
        int preferred = Math.max(150, Math.min(330, available * 38 / 100));
        int detailMinimum = Math.min(220, Math.max(120, available / 2));
        return Math.max(1, Math.min(preferred, available - detailMinimum));
    }

    private int listHeight() {
        return Math.max(ROW_HEIGHT, visibleRows() * ROW_HEIGHT);
    }

    private int visibleRows() {
        int available = Math.max(ROW_HEIGHT, bodyBottom() - listTop());
        if (stackedLayout()) {
            available = Math.max(ROW_HEIGHT, available - stackedDetailReserve(available) - PANEL_GAP);
        }
        return Math.max(1, available / ROW_HEIGHT);
    }

    private int modSearchY() {
        return contentTop();
    }

    private int detailX() {
        return stackedLayout() ? padding() : listX() + listWidth() + PANEL_GAP;
    }

    private int detailTop() {
        return stackedLayout() ? listTop() + listHeight() + PANEL_GAP : listTop();
    }

    private int detailWidth() {
        return stackedLayout() ? contentWidth() : Math.max(1, this.width - detailX() - padding());
    }

    private int detailHeight() {
        return stackedLayout() ? Math.max(1, bodyBottom() - detailTop()) : listHeight();
    }

    private int bodyBottom() {
        return Math.max(listTop() + ROW_HEIGHT, this.height - FOOTER_HEIGHT - 4);
    }

    private int stackedDetailReserve(int available) {
        int rows = actionButtonRows(actionButtons().size(), actionButtonColumns(Math.max(1, contentWidth() - DETAIL_INSET * 2)));
        int minimum = DETAIL_INSET * 2 + actionButtonAreaHeight(rows) + DETAIL_ACTION_GAP + 42;
        int desired = Math.min(190, Math.max(minimum, available / 3));
        return Math.max(0, Math.min(desired, available - ROW_HEIGHT - PANEL_GAP));
    }

    private int padding() {
        return this.width < 380 ? COMPACT_PADDING : PADDING;
    }

    private int contentWidth() {
        return Math.max(1, this.width - padding() * 2);
    }

    private boolean compactTopBar() {
        return contentWidth() < 360;
    }

    private boolean stackedLayout() {
        return contentWidth() < STACKED_WIDTH;
    }

    private int contentTop() {
        return compactTopBar() ? 116 : 96;
    }

    private boolean insideList(double mouseX, double mouseY) {
        return mouseX >= listX() && mouseX < listX() + listWidth() && mouseY >= listTop() && mouseY < listTop() + listHeight();
    }

    private int itemCountForTab() {
        return switch (this.tab) {
            case PROFILES -> this.profiles.size();
            case MODS -> this.filteredMods.size();
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
        this.modScroll = Math.max(0, Math.min(this.modScroll, Math.max(0, this.filteredMods.size() - visibleRows())));
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
            case MODS -> this.modSearchText.isBlank()
                    ? Component.translatable("modqualitypicker.editor.tab_mods")
                    : Component.translatable("modqualitypicker.editor.tab_mods_filtered", this.filteredMods.size(), this.availableMods.size());
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

    private void updateModActionButtons() {
        if (this.modEnabledButton == null || this.modLockedButton == null) {
            return;
        }

        boolean hasMod = selectedMod().isPresent();
        this.modEnabledButton.active = hasMod;
        this.modLockedButton.active = hasMod;
        this.modEnabledButton.setMessage(modEnabledButtonLabel());
        this.modLockedButton.setMessage(modLockedButtonLabel());
    }

    private void updateQueueButtonLabel() {
        if (this.queueButton == null) {
            return;
        }
        boolean shiftMode = hasShiftDown();
        if (this.queueButtonShiftMode != shiftMode) {
            this.queueButtonShiftMode = shiftMode;
            this.queueButton.setMessage(queueButtonLabel());
        }
    }

    private Component queueButtonLabel() {
        return hasShiftDown()
                ? Component.translatable("modqualitypicker.editor.queue_restart")
                : Component.translatable("modqualitypicker.editor.queue");
    }

    private Component modEnabledButtonLabel() {
        return selectedModState().map(state -> Component.translatable(state.enabled() ? "modqualitypicker.editor.toggle_enabled" : "modqualitypicker.editor.toggle_disabled"))
                .orElse(Component.translatable("modqualitypicker.editor.toggle_enabled"));
    }

    private Component modLockedButtonLabel() {
        return selectedModState().map(state -> Component.translatable(state.locked() ? "modqualitypicker.editor.toggle_locked" : "modqualitypicker.editor.toggle_unlocked"))
                .orElse(Component.translatable("modqualitypicker.editor.toggle_unlocked"));
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
        guiGraphics.fill(x, y, x + width, y + height, PANEL_FILL);
        guiGraphics.fill(x, y, x + width, y + 1, PANEL_BORDER);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, PANEL_BORDER);
    }

    private void drawString(GuiGraphics guiGraphics, String text, int x, int y, int color) {
        guiGraphics.drawString(this.font, text, x, y, color, true);
    }

    private void drawString(GuiGraphics guiGraphics, Component text, int x, int y, int color) {
        guiGraphics.drawString(this.font, text, x, y, color, true);
    }

    private Button addButton(Component label, int x, int y, int width, Runnable action) {
        return this.addRenderableWidget(Button.builder(label, button -> action.run()).bounds(x, y, width, 20).build());
    }

    private void showError(Exception exception) {
        this.minecraft.setScreen(new AlertScreen(
                () -> this.minecraft.setScreen(this),
                Component.translatable("modqualitypicker.world.error"),
                Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())
        ));
    }
}
