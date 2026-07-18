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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.destroyermob.modqualitypicker.configfile.ConfigFileManager;
import org.destroyermob.modqualitypicker.export.ProfileExporter;
import org.destroyermob.modqualitypicker.profile.ConfigFileOverride;
import org.destroyermob.modqualitypicker.profile.ModState;
import org.destroyermob.modqualitypicker.profile.ProfileOption;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.runtime.ModJarCatalog;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;
import org.destroyermob.modqualitypicker.runtime.ProfileValidation;
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
    private static final int FILTER_BUTTON_GAP = 4;
    private static final int FILTER_BUTTON_WIDTH = 82;
    private static final int PANEL_GAP = 10;
    private static final int STACKED_WIDTH = 360;
    private static final int DETAIL_INSET = 12;
    private static final int DETAIL_LINE_STEP = 13;
    private static final int DETAIL_ACTION_GAP = 8;
    private static final int DETAIL_SCROLL_STEP = 24;
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

    private enum ModVisibility {
        ALL,
        ENABLED,
        DISABLED
    }

    private record ActionButton(Component label, Runnable action) {
    }

    private record ScrollbarGeometry(int barX, int trackTop, int trackHeight, int thumbHeight, int thumbY) {
    }

    private record DetailScrollArea(int textTop, int textBottom, int maxScroll) {
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
    private final Map<String, String> modResolutionWarnings = new LinkedHashMap<>();
    private final Map<String, Boolean> resolvedModStates = new LinkedHashMap<>();
    private final Map<String, ModJarCatalog.ModInspection> modInspections = new LinkedHashMap<>();
    private final List<Button> modActionButtons = new ArrayList<>();
    private final List<ControllerFocusButton> listFocusButtons = new ArrayList<>();

    private int selectedProfileIndex;
    private int selectedModIndex;
    private int selectedConfigIndex;
    private int profileScroll;
    private int modScroll;
    private int configScroll;
    private int detailScroll;
    private Tab tab = Tab.MODS;
    private EditBox profileName;
    private EditBox modSearch;
    private Button queueButton;
    private Button modEnabledButton;
    private Button modLockedButton;
    private Button modVisibilityButton;
    private boolean queueButtonShiftMode;
    private boolean draggingListScrollbar;
    private boolean draggingDetailScrollbar;
    private int listScrollbarGrabOffset;
    private int detailScrollbarGrabOffset;
    private String modSearchText = "";
    private ModVisibility modVisibility = ModVisibility.ALL;
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
        this.modVisibilityButton = null;
        this.modActionButtons.clear();
        this.draggingListScrollbar = false;
        this.draggingDetailScrollbar = false;
        reloadData();
        initTopControls();

        if (this.tab == Tab.MODS) {
            int filterWidth = Math.min(FILTER_BUTTON_WIDTH, Math.max(54, listWidth() / 3));
            int searchWidth = Math.max(1, listWidth() - filterWidth - FILTER_BUTTON_GAP);
            this.modSearch = new EditBox(this.font, listX(), modSearchY(), searchWidth, SEARCH_HEIGHT, Component.translatable("modqualitypicker.editor.search_mods"));
            this.modSearch.setHint(Component.translatable("modqualitypicker.editor.search_mods"));
            this.modSearch.setMaxLength(80);
            this.modSearch.setValue(this.modSearchText);
            this.modSearch.setResponder(this::setModSearchText);
            this.addRenderableWidget(this.modSearch);
            this.modVisibilityButton = addButton(
                    modVisibilityLabel(),
                    listX() + searchWidth + FILTER_BUTTON_GAP,
                    modSearchY(),
                    filterWidth,
                    this::cycleModVisibility
            );
        } else {
            this.modSearch = null;
        }

        initActionButtons();
        updateModActionButtons();
        refreshControllerListButtons();
    }

    private void refreshControllerListButtons() {
        boolean refocusList = this.listFocusButtons.contains(this.getFocused());
        for (ControllerFocusButton button : this.listFocusButtons) {
            this.removeWidget(button);
        }
        this.listFocusButtons.clear();
        int count = itemCountForTab();
        int scroll = scrollForTab();
        for (int visible = 0; visible < visibleRows(); visible++) {
            int absoluteIndex = scroll + visible;
            if (absoluteIndex >= count) {
                break;
            }
            Component label = this.tab == Tab.PROFILES
                    ? Component.literal(this.profiles.get(absoluteIndex).displayName())
                    : Component.literal(this.filteredMods.get(absoluteIndex));
            ControllerFocusButton button = this.addRenderableWidget(new ControllerFocusButton(
                    listX() + 2, listTop() + visible * ROW_HEIGHT + 1,
                    listWidth() - 4, ROW_HEIGHT - 2, label,
                    () -> selectRow(absoluteIndex)
            ));
            this.listFocusButtons.add(button);
        }
        listScrollbarGeometry().ifPresent(scrollbar -> {
            int halfHeight = Math.max(10, scrollbar.trackHeight() / 2);
            ControllerFocusButton previous = this.addRenderableWidget(new ControllerFocusButton(
                    scrollbar.barX() - 8, scrollbar.trackTop(), 11, halfHeight,
                    Component.translatable("modqualitypicker.editor.previous_page"),
                    () -> setScrollForTab(scrollForTab() - visibleRows())
            ));
            ControllerFocusButton next = this.addRenderableWidget(new ControllerFocusButton(
                    scrollbar.barX() - 8, scrollbar.trackTop() + halfHeight, 11,
                    scrollbar.trackHeight() - halfHeight,
                    Component.translatable("modqualitypicker.editor.next_page"),
                    () -> setScrollForTab(scrollForTab() + visibleRows())
            ));
            this.listFocusButtons.add(previous);
            this.listFocusButtons.add(next);
        });
        if (refocusList && !this.listFocusButtons.isEmpty()) {
            this.setInitialFocus(this.listFocusButtons.getFirst());
        }
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
        if (button == 0) {
            if (startListScrollbarDrag(mouseX, mouseY) || startDetailScrollbarDrag(mouseX, mouseY)) {
                return true;
            }
            if (insideList(mouseX, mouseY)) {
                int visibleIndex = ((int) mouseY - listTop()) / ROW_HEIGHT;
                int absoluteIndex = scrollForTab() + visibleIndex;
                if (selectRow(absoluteIndex)) {
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && this.draggingListScrollbar) {
            updateListScrollFromMouse(mouseY);
            return true;
        }
        if (button == 0 && this.draggingDetailScrollbar) {
            updateDetailScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (this.draggingListScrollbar || this.draggingDetailScrollbar)) {
            this.draggingListScrollbar = false;
            this.draggingDetailScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
        if (insideList(mouseX, mouseY)) {
            int step = hasShiftDown() ? visibleRows() : (this.tab == Tab.MODS ? 3 : 1);
            int next = scrollForTab() - (int) Math.signum(scrollDeltaY) * step;
            setScrollForTab(next);
            return true;
        }
        if (insideDetails(mouseX, mouseY)) {
            int maxScroll = maxDetailScroll();
            if (maxScroll > 0) {
                this.detailScroll = Mth.clamp(this.detailScroll - (int) Math.signum(scrollDeltaY) * DETAIL_SCROLL_STEP, 0, maxScroll);
                return true;
            }
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
                    new ActionButton(modLockedButtonLabel(), this::toggleSelectedModLock),
                    new ActionButton(Component.translatable("modqualitypicker.editor.disable_dependents"), () -> disableSelectedMod(ModJarCatalog.DisableStrategy.DEPENDENTS)),
                    new ActionButton(Component.translatable("modqualitypicker.editor.disable_jar"), () -> disableSelectedMod(ModJarCatalog.DisableStrategy.JAR)),
                    new ActionButton(Component.translatable("modqualitypicker.editor.disable_unused_deps"), () -> disableSelectedMod(ModJarCatalog.DisableStrategy.UNUSED_DEPENDENCIES))
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
                this.modActionButtons.add(button);
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
            listScrollbarGeometry().ifPresent(scrollbar -> {
                guiGraphics.fill(scrollbar.barX(), scrollbar.trackTop(), scrollbar.barX() + 3, scrollbar.trackTop() + scrollbar.trackHeight(), 0x70303030);
                guiGraphics.fill(scrollbar.barX(), scrollbar.thumbY(), scrollbar.barX() + 3, scrollbar.thumbY() + scrollbar.thumbHeight(), 0xC0A0A0A0);
            });
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
            String warning = this.modResolutionWarnings.get(modId);
            boolean resolvedEnabled = resolvedModEnabled(modId);
            String marker = resolvedEnabled ? state.enabled() ? "[ON] " : "[ON*] " : state.enabled() ? "[OFF*] " : "[OFF] ";
            String lock = state.locked() ? " locked" : "";
            drawString(guiGraphics, fit(marker + modId, width), x, y, warning == null ? primary : 0xFFE0A0);
            String resolvedDescription = Component.translatable(
                    resolvedEnabled ? "modqualitypicker.editor.resolved_enabled" : "modqualitypicker.editor.resolved_disabled"
            ).getString();
            drawString(guiGraphics, fit(warning == null ? resolvedDescription + lock : Component.translatable("modqualitypicker.editor.mod_stays_loaded").getString(), width), x, y + 12, warning == null ? secondary : 0xE8C878);
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

        int textTop = y;
        if (textTop + this.font.lineHeight > textBottom) {
            return;
        }
        int textWidth = Math.max(1, width - 8);
        List<FormattedCharSequence> lines = wrappedDetailLines(textWidth);
        int maxScroll = maxDetailScroll(lines, textTop, textBottom);
        this.detailScroll = Mth.clamp(this.detailScroll, 0, maxScroll);

        guiGraphics.enableScissor(detailX() + 1, textTop, detailX() + detailWidth() - 1, textBottom);
        try {
            int lineY = textTop - this.detailScroll;
            for (FormattedCharSequence line : lines) {
                if (lineY + this.font.lineHeight > textTop && lineY < textBottom) {
                    guiGraphics.drawString(this.font, line, x, lineY, 0xD0D0D0, true);
                }
                lineY += DETAIL_LINE_STEP;
            }
        } finally {
            guiGraphics.disableScissor();
        }

        drawDetailScrollbar(guiGraphics, maxScroll, textTop, textBottom);
    }

    private List<FormattedCharSequence> wrappedDetailLines(int width) {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : detailLines()) {
            List<FormattedCharSequence> split = this.font.split(line, width);
            if (split.isEmpty()) {
                wrapped.add(FormattedCharSequence.EMPTY);
            } else {
                wrapped.addAll(split);
            }
        }
        return wrapped;
    }

    private int maxDetailScroll() {
        return detailScrollArea().map(DetailScrollArea::maxScroll).orElse(0);
    }

    private Optional<DetailScrollArea> detailScrollArea() {
        int width = detailWidth() - DETAIL_INSET * 2;
        int rows = actionButtonRows(actionButtons().size(), actionButtonColumns(Math.max(1, width)));
        int textTop = detailTop() + DETAIL_INSET + 18;
        int textBottom = actionButtonY(rows) - DETAIL_ACTION_GAP;
        if (textTop + this.font.lineHeight > textBottom) {
            return Optional.empty();
        }
        int maxScroll = maxDetailScroll(wrappedDetailLines(Math.max(1, width - 8)), textTop, textBottom);
        return Optional.of(new DetailScrollArea(textTop, textBottom, maxScroll));
    }

    private int maxDetailScroll(List<FormattedCharSequence> lines, int textTop, int textBottom) {
        int visibleHeight = Math.max(1, textBottom - textTop);
        int contentHeight = lines.size() * DETAIL_LINE_STEP;
        return Math.max(0, contentHeight - visibleHeight);
    }

    private void drawDetailScrollbar(GuiGraphics guiGraphics, int maxScroll, int textTop, int textBottom) {
        if (maxScroll <= 0) {
            return;
        }

        ScrollbarGeometry scrollbar = detailScrollbarGeometry(maxScroll, textTop, textBottom);
        guiGraphics.fill(scrollbar.barX(), scrollbar.trackTop(), scrollbar.barX() + 3, scrollbar.trackTop() + scrollbar.trackHeight(), 0x70303030);
        guiGraphics.fill(scrollbar.barX(), scrollbar.thumbY(), scrollbar.barX() + 3, scrollbar.thumbY() + scrollbar.thumbHeight(), 0xC0A0A0A0);
    }

    private Optional<ScrollbarGeometry> listScrollbarGeometry() {
        int count = itemCountForTab();
        int visibleRows = visibleRows();
        if (count <= visibleRows) {
            return Optional.empty();
        }

        int rowWidth = listWidth();
        int trackTop = listTop() + 2;
        int trackHeight = visibleRows * ROW_HEIGHT - 4;
        int thumbHeight = Math.max(18, trackHeight * visibleRows / count);
        int maxScroll = maxScrollForTab();
        int thumbY = trackTop + (maxScroll <= 0 ? 0 : (trackHeight - thumbHeight) * scrollForTab() / maxScroll);
        return Optional.of(new ScrollbarGeometry(listX() + rowWidth - 6, trackTop, trackHeight, thumbHeight, thumbY));
    }

    private ScrollbarGeometry detailScrollbarGeometry(int maxScroll, int textTop, int textBottom) {
        int trackTop = textTop + 1;
        int trackHeight = Math.max(1, textBottom - textTop - 2);
        int contentHeight = trackHeight + maxScroll;
        int thumbHeight = Math.max(18, trackHeight * trackHeight / Math.max(1, contentHeight));
        int thumbY = trackTop + (trackHeight - thumbHeight) * this.detailScroll / maxScroll;
        return new ScrollbarGeometry(detailX() + detailWidth() - 6, trackTop, trackHeight, thumbHeight, thumbY);
    }

    private boolean startListScrollbarDrag(double mouseX, double mouseY) {
        Optional<ScrollbarGeometry> geometry = listScrollbarGeometry();
        if (geometry.isEmpty() || !insideScrollbar(mouseX, mouseY, geometry.get())) {
            return false;
        }

        ScrollbarGeometry scrollbar = geometry.get();
        this.draggingListScrollbar = true;
        this.listScrollbarGrabOffset = scrollbarThumbContains(mouseX, mouseY, scrollbar)
                ? (int) mouseY - scrollbar.thumbY()
                : scrollbar.thumbHeight() / 2;
        updateListScrollFromMouse(mouseY);
        return true;
    }

    private boolean startDetailScrollbarDrag(double mouseX, double mouseY) {
        Optional<DetailScrollArea> area = detailScrollArea();
        if (area.isEmpty() || area.get().maxScroll() <= 0) {
            return false;
        }

        ScrollbarGeometry scrollbar = detailScrollbarGeometry(area.get().maxScroll(), area.get().textTop(), area.get().textBottom());
        if (!insideScrollbar(mouseX, mouseY, scrollbar)) {
            return false;
        }

        this.draggingDetailScrollbar = true;
        this.detailScrollbarGrabOffset = scrollbarThumbContains(mouseX, mouseY, scrollbar)
                ? (int) mouseY - scrollbar.thumbY()
                : scrollbar.thumbHeight() / 2;
        updateDetailScrollFromMouse(mouseY);
        return true;
    }

    private void updateListScrollFromMouse(double mouseY) {
        Optional<ScrollbarGeometry> geometry = listScrollbarGeometry();
        if (geometry.isEmpty()) {
            return;
        }

        ScrollbarGeometry scrollbar = geometry.get();
        int maxScroll = maxScrollForTab();
        int travel = Math.max(1, scrollbar.trackHeight() - scrollbar.thumbHeight());
        int thumbTop = Mth.clamp((int) mouseY - this.listScrollbarGrabOffset, scrollbar.trackTop(), scrollbar.trackTop() + travel);
        setScrollForTab(Math.round((float) (thumbTop - scrollbar.trackTop()) * maxScroll / travel));
    }

    private void updateDetailScrollFromMouse(double mouseY) {
        Optional<DetailScrollArea> area = detailScrollArea();
        if (area.isEmpty() || area.get().maxScroll() <= 0) {
            return;
        }

        ScrollbarGeometry scrollbar = detailScrollbarGeometry(area.get().maxScroll(), area.get().textTop(), area.get().textBottom());
        int travel = Math.max(1, scrollbar.trackHeight() - scrollbar.thumbHeight());
        int thumbTop = Mth.clamp((int) mouseY - this.detailScrollbarGrabOffset, scrollbar.trackTop(), scrollbar.trackTop() + travel);
        this.detailScroll = Mth.clamp(Math.round((float) (thumbTop - scrollbar.trackTop()) * area.get().maxScroll() / travel), 0, area.get().maxScroll());
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
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("modqualitypicker.editor.mod_state", state.enabled(), state.locked()));
            lines.add(Component.translatable(
                    "modqualitypicker.editor.mod_resolved_state",
                    Component.translatable(resolvedModEnabled(modId.get()) ? "modqualitypicker.editor.enabled" : "modqualitypicker.editor.disabled")
            ));
            ModJarCatalog.ModInspection inspection = this.modInspections.get(modId.get());
            if (inspection != null) {
                lines.add(Component.empty());
                lines.add(Component.translatable("modqualitypicker.editor.jar_file", inspection.fileName()));
                lines.add(Component.translatable(
                        "modqualitypicker.editor.jar_disk_state",
                        Component.translatable(inspection.jarCurrentlyEnabled() ? "modqualitypicker.editor.enabled" : "modqualitypicker.editor.disabled")
                ));
                lines.add(Component.translatable("modqualitypicker.editor.jar_provides", joinedOrNone(inspection.providedModIds())));
                lines.add(Component.translatable("modqualitypicker.editor.jar_requires", joinedOrNone(inspection.requiredModIds())));
                lines.add(Component.empty());
                lines.add(Component.translatable("modqualitypicker.editor.jar_dependents", inspection.dependentJars().size()));
                if (inspection.dependentJars().isEmpty()) {
                    lines.add(Component.translatable("modqualitypicker.editor.jar_dependents_none"));
                } else {
                    for (ModJarCatalog.DependentJar dependent : inspection.dependentJars()) {
                        lines.add(Component.translatable(
                                "modqualitypicker.editor.jar_dependent_entry",
                                dependent.fileName(),
                                String.join(", ", dependent.modIds()),
                                String.join(", ", dependent.requiredProvidedIds())
                        ));
                    }
                }
            } else {
                lines.add(Component.translatable("modqualitypicker.editor.jar_unknown"));
            }
            selectedModResolutionWarning(modId.get()).ifPresent(warning -> lines.add(Component.translatable("modqualitypicker.editor.mod_resolution_warning", warning)));
            lines.add(Component.translatable("modqualitypicker.editor.mod_position", this.selectedModIndex + 1, this.filteredMods.size()));
            lines.add(Component.translatable("modqualitypicker.editor.mod_hint"));
            lines.add(Component.translatable("modqualitypicker.editor.queue_shift_hint"));
            return lines;
        }

        return List.of();
    }

    private Optional<Component> selectedModResolutionWarning(String modId) {
        return Optional.ofNullable(this.modResolutionWarnings.get(modId)).map(Component::literal);
    }

    private void refreshModResolutionWarnings() {
        this.modResolutionWarnings.clear();
        this.resolvedModStates.clear();
        try {
            this.resolvedModStates.putAll(ModJarCatalog.resolveEnabledMods(ProfilePaths.gameDirectory(), this.draft));
            ProfileValidation validation = QualityRuntime.validateProfile(this.draft);
            for (String warning : validation.warnings()) {
                disabledModIdFromWarning(warning).ifPresent(modId -> this.modResolutionWarnings.putIfAbsent(modId, warning));
            }
        } catch (IOException ignored) {
            this.modResolutionWarnings.clear();
            this.draft.mods().forEach((modId, state) -> this.resolvedModStates.put(modId, state.enabled()));
        }
    }

    private Optional<String> disabledModIdFromWarning(String warning) {
        String prefix = "profile disables ";
        String separator = ", but ";
        if (!warning.startsWith(prefix)) {
            return Optional.empty();
        }
        int separatorIndex = warning.indexOf(separator, prefix.length());
        if (separatorIndex <= prefix.length()) {
            return Optional.empty();
        }
        return Optional.of(warning.substring(prefix.length(), separatorIndex));
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
        refreshModResolutionWarnings();

        if (!this.catalogLoaded) {
            this.catalogMods.clear();
            this.catalogMods.addAll(QualityRuntime.availableModIds(this.draft));
            try {
                this.modInspections.clear();
                this.modInspections.putAll(ModJarCatalog.inspectMods(ProfilePaths.gameDirectory()));
            } catch (IOException exception) {
                this.status = Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            }
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
        this.detailScroll = 0;
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
            this.detailScroll = 0;
            rebuildWidgets();
        } else if (this.tab == Tab.MODS) {
            this.selectedModIndex = absoluteIndex;
            this.detailScroll = 0;
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
                Map.of(),
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
                source.options(),
                source.featureChoices()
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

    private void disableSelectedMod(ModJarCatalog.DisableStrategy strategy) {
        selectedMod().ifPresent(modId -> {
            try {
                ModJarCatalog.DisablePlan plan = QualityRuntime.planDisableMod(this.draft, modId, strategy);
                if (plan.isEmpty()) {
                    this.status = Component.translatable("modqualitypicker.message.no_disable_changes", modId);
                    return;
                }
                if (plan.entries().size() == 1) {
                    applyDisablePlan(plan);
                } else {
                    confirmDisablePlan(plan);
                }
            } catch (IOException exception) {
                showError(exception);
            }
        });
    }

    private void confirmDisablePlan(ModJarCatalog.DisablePlan plan) {
        List<String> modIds = plan.modIds();
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    this.minecraft.setScreen(this);
                    if (confirmed) {
                        applyDisablePlan(plan);
                    }
                },
                Component.translatable("modqualitypicker.editor.disable_plan_title"),
                Component.translatable("modqualitypicker.editor.disable_plan_confirm", modIds.size(), disablePreview(modIds)),
                Component.translatable("modqualitypicker.editor.disable_plan_apply"),
                CommonComponents.GUI_CANCEL
        ));
    }

    private void applyDisablePlan(ModJarCatalog.DisablePlan plan) {
        Map<String, ModState> mods = new LinkedHashMap<>(this.draft.mods());
        for (ModJarCatalog.DisableEntry entry : plan.entries()) {
            ModState current = mods.getOrDefault(entry.modId(), ModState.implicitChoice());
            mods.put(entry.modId(), new ModState(false, current.locked(), entry.reason()));
        }
        this.draft = copyProfile(this.draft.id(), this.draft.displayName(), mods, this.draft.configFiles(), this.draft.options());
        this.status = Component.translatable("modqualitypicker.message.mods_disabled", plan.entries().size(), plan.rootModId());
        refreshModResolutionWarnings();
        this.detailScroll = 0;
        updateModActionButtons();
    }

    private String disablePreview(List<String> modIds) {
        int limit = 12;
        if (modIds.size() <= limit) {
            return String.join(", ", modIds);
        }
        return String.join(", ", modIds.subList(0, limit)) + " +" + (modIds.size() - limit) + " more";
    }

    private void removeSelectedModOverride() {
        selectedMod().ifPresent(modId -> {
            Map<String, ModState> mods = new LinkedHashMap<>(this.draft.mods());
            mods.remove(modId);
            this.draft = copyProfile(this.draft.id(), this.draft.displayName(), mods, this.draft.configFiles(), this.draft.options());
            this.status = Component.translatable("modqualitypicker.message.mod_removed", modId);
            refreshModResolutionWarnings();
            this.detailScroll = 0;
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
        refreshModResolutionWarnings();
        applyModFilter(modId);
        this.detailScroll = 0;
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
        return new QualityProfile(QualityProfile.SCHEMA_VERSION, normalizedId, displayName, this.draft.sortOrder(), this.draft.description(), mods, configs, options, this.draft.featureChoices());
    }

    private void setModSearchText(String value) {
        String selected = selectedMod().orElse("");
        this.modSearchText = value == null ? "" : value;
        applyModFilter(selected);
        if (this.minecraft != null) {
            refreshControllerListButtons();
        }
    }

    private void applyModFilter(String preserveModId) {
        this.filteredMods.clear();
        String query = this.modSearchText.trim().toLowerCase(Locale.ROOT);
        for (String modId : this.availableMods) {
            boolean visibilityMatch = switch (this.modVisibility) {
                case ALL -> true;
                case ENABLED -> resolvedModEnabled(modId);
                case DISABLED -> !resolvedModEnabled(modId);
            };
            if (visibilityMatch && matchesModSearch(modId, query)) {
                this.filteredMods.add(modId);
            }
        }

        int previousSelectedModIndex = this.selectedModIndex;
        int preservedIndex = preserveModId.isBlank() ? -1 : this.filteredMods.indexOf(preserveModId);
        this.selectedModIndex = preservedIndex >= 0 ? preservedIndex : clampIndex(this.selectedModIndex, this.filteredMods.size());
        if (this.selectedModIndex != previousSelectedModIndex) {
            this.detailScroll = 0;
        }
        this.modScroll = Math.max(0, Math.min(this.modScroll, Math.max(0, this.filteredMods.size() - visibleRows())));
        updateModActionButtons();
    }

    private boolean matchesModSearch(String modId, String query) {
        if (query.isEmpty() || modId.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        ModJarCatalog.ModInspection inspection = this.modInspections.get(modId);
        if (inspection == null) {
            return false;
        }
        if (inspection.fileName().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return inspection.providedModIds().stream().anyMatch(id -> id.toLowerCase(Locale.ROOT).contains(query));
    }

    private void cycleModVisibility() {
        String selected = selectedMod().orElse("");
        this.modVisibility = switch (this.modVisibility) {
            case ALL -> ModVisibility.ENABLED;
            case ENABLED -> ModVisibility.DISABLED;
            case DISABLED -> ModVisibility.ALL;
        };
        if (this.modVisibilityButton != null) {
            this.modVisibilityButton.setMessage(modVisibilityLabel());
        }
        this.modScroll = 0;
        applyModFilter(selected);
    }

    private Component modVisibilityLabel() {
        return Component.translatable(switch (this.modVisibility) {
            case ALL -> "modqualitypicker.editor.filter_all";
            case ENABLED -> "modqualitypicker.editor.filter_enabled";
            case DISABLED -> "modqualitypicker.editor.filter_disabled";
        });
    }

    private boolean resolvedModEnabled(String modId) {
        return this.resolvedModStates.getOrDefault(modId, modState(modId).enabled());
    }

    private String joinedOrNone(List<String> values) {
        return values.isEmpty() ? Component.translatable("modqualitypicker.editor.none").getString() : String.join(", ", values);
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

    private boolean insideDetails(double mouseX, double mouseY) {
        return mouseX >= detailX() && mouseX < detailX() + detailWidth() && mouseY >= detailTop() && mouseY < detailTop() + detailHeight();
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
        if (this.minecraft != null) {
            refreshControllerListButtons();
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
            case MODS -> this.modSearchText.isBlank() && this.modVisibility == ModVisibility.ALL
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
        for (Button button : this.modActionButtons) {
            button.active = hasMod;
        }
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
