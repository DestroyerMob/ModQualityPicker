package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.destroyermob.modqualitypicker.profile.EffectiveQualitySelection;
import org.destroyermob.modqualitypicker.profile.ApplyRequirement;
import org.destroyermob.modqualitypicker.profile.FeatureChoice;
import org.destroyermob.modqualitypicker.profile.FeatureGroup;
import org.destroyermob.modqualitypicker.profile.QualityPackDefinition;
import org.destroyermob.modqualitypicker.profile.QualityProfile;
import org.destroyermob.modqualitypicker.profile.QualitySelection;
import org.destroyermob.modqualitypicker.runtime.ModJarCatalog;
import org.destroyermob.modqualitypicker.runtime.ProfilePaths;
import org.destroyermob.modqualitypicker.runtime.QualityRuntime;
import org.destroyermob.modqualitypicker.runtime.RuntimeSelection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlayerQualityScreen extends Screen {
    private static final int CONTENT_TOP = 88;
    private static final int MAX_CONTENT_WIDTH = 440;
    private static final int WIDGET_GAP = 4;
    private final Screen parent;
    private List<QualityProfile> profiles = List.of();
    private QualityPackDefinition definition = QualityPackDefinition.empty();
    private QualitySelection activeSelection = QualitySelection.forBase("balanced");
    private QualitySelection draft = QualitySelection.forBase("balanced");
    private EffectiveQualitySelection effective;
    private RuntimeSelection loaded;
    private Map<String, Boolean> desiredMods = Map.of();
    private List<String> mods = List.of();
    private Tab tab = Tab.FEATURES;
    private int featureScroll;
    private int modScroll;
    private Component status = CommonComponents.EMPTY;
    private Button queueButton;
    private Button queueQuitButton;

    private enum Tab {
        FEATURES,
        MODS
    }

    public PlayerQualityScreen(Screen parent) {
        super(Component.translatable("modqualitypicker.player.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        reload();
        normalizeScrollOffsets();
        int left = contentLeft();
        int contentWidth = contentWidth();
        int topButtonY = 55;
        int tabWidth = (contentWidth - WIDGET_GAP * 2) / 3;
        addRenderableWidget(Button.builder(tabLabel(Tab.FEATURES), button -> switchTab(Tab.FEATURES))
                .bounds(left, topButtonY, tabWidth, 20).build());
        addRenderableWidget(Button.builder(tabLabel(Tab.MODS), button -> switchTab(Tab.MODS))
                .bounds(left + tabWidth + WIDGET_GAP, topButtonY, tabWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.player.developer"), button -> this.minecraft.setScreen(new QualityProfileScreen(this)))
                .bounds(left + (tabWidth + WIDGET_GAP) * 2, topButtonY, contentWidth - (tabWidth + WIDGET_GAP) * 2, 20).build());

        if (!profiles.isEmpty()) {
            QualityProfile initial = profileById(draft.baseProfileId());
            addRenderableWidget(CycleButton.builder((QualityProfile profile) -> Component.literal(profile.displayName()))
                    .withValues(profiles)
                    .withInitialValue(initial)
                    .create(left, 30, contentWidth, 20, Component.translatable("modqualitypicker.player.base_preset"), (button, profile) -> {
                        this.draft = this.draft.withBaseProfile(profile.id());
                        this.featureScroll = 0;
                        rebuild();
                    }));
        }

        if (tab == Tab.FEATURES) {
            initFeatureControls();
        }

        initFooterControls(left, contentWidth);
        updateQueueButtons();
    }

    private void initFooterControls(int left, int contentWidth) {
        Button.OnPress reset = button -> {
            this.draft = QualitySelection.forBase(this.draft.baseProfileId());
            rebuild();
        };
        if (compactFooter()) {
            int buttonWidth = (contentWidth - WIDGET_GAP) / 2;
            int firstRow = this.height - 50;
            int secondRow = this.height - 27;
            addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.player.reset_overrides"), reset)
                    .bounds(left, firstRow, buttonWidth, 20).build());
            this.queueButton = addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.player.queue"), button -> queue(false))
                    .bounds(left + buttonWidth + WIDGET_GAP, firstRow, contentWidth - buttonWidth - WIDGET_GAP, 20).build());
            this.queueQuitButton = addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.player.queue_quit"), button -> confirmQueueAndQuit())
                    .bounds(left, secondRow, buttonWidth, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                    .bounds(left + buttonWidth + WIDGET_GAP, secondRow, contentWidth - buttonWidth - WIDGET_GAP, 20).build());
            return;
        }

        int buttonWidth = (contentWidth - WIDGET_GAP * 3) / 4;
        int bottom = this.height - 27;
        addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.player.reset_overrides"), reset)
                .bounds(left, bottom, buttonWidth, 20).build());
        this.queueButton = addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.player.queue"), button -> queue(false))
                .bounds(left + buttonWidth + WIDGET_GAP, bottom, buttonWidth, 20).build());
        this.queueQuitButton = addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.player.queue_quit"), button -> confirmQueueAndQuit())
                .bounds(left + (buttonWidth + WIDGET_GAP) * 2, bottom, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + (buttonWidth + WIDGET_GAP) * 3, bottom, contentWidth - (buttonWidth + WIDGET_GAP) * 3, 20).build());
    }

    private void initFeatureControls() {
        List<FeatureGroup> groups = adjustableGroups();
        int visible = visibleFeatureRows();
        int left = contentLeft();
        int choiceWidth = featureChoiceWidth();
        int choiceX = left + contentWidth() - choiceWidth;
        for (int visibleIndex = 0; visibleIndex < visible; visibleIndex++) {
            int index = featureScroll + visibleIndex;
            if (index >= groups.size()) {
                break;
            }
            FeatureGroup group = groups.get(index);
            List<String> values = new ArrayList<>(group.choices().keySet());
            if (values.isEmpty()) {
                continue;
            }
            String selected = effectiveChoice(group);
            if (!values.contains(selected)) {
                selected = values.getFirst();
            }
            int y = CONTENT_TOP + visibleIndex * featureRowHeight();
            addRenderableWidget(CycleButton.builder((String choiceId) -> choiceLabel(group, choiceId))
                    .withValues(values)
                    .withInitialValue(selected)
                    .displayOnlyValue()
                    .withTooltip(choiceId -> featureTooltip(group, choiceId))
                    .create(choiceX, y, choiceWidth, 20, Component.literal(group.displayName()), (button, choiceId) -> {
                        String inherited = inheritedChoice(group, profileById(this.draft.baseProfileId()));
                        this.draft = Objects.equals(inherited, choiceId)
                                ? this.draft.withoutOverride(group.id())
                                : this.draft.withOverride(group.id(), choiceId);
                        rebuild();
                    }));
        }
    }

    private void reload() {
        this.profiles = QualityRuntime.profiles().listPresets();
        this.definition = QualityRuntime.packDefinition();
        this.activeSelection = QualityRuntime.activeQualitySelection();
        if (this.effective == null) {
            this.draft = QualityRuntime.pendingQualitySelection().orElse(this.activeSelection);
        }
        resolveDraft();
    }

    private void resolveDraft() {
        try {
            this.effective = QualityRuntime.resolveQualitySelection(this.draft);
            this.loaded = QualityRuntime.currentSelection();
            this.desiredMods = ModJarCatalog.resolveEnabledMods(ProfilePaths.gameDirectory(), this.effective.profile());
            this.mods = QualityRuntime.availableModIds(this.effective.profile());
            this.status = CommonComponents.EMPTY;
        } catch (IOException | RuntimeException exception) {
            this.effective = null;
            this.loaded = QualityRuntime.currentSelection();
            this.desiredMods = Map.of();
            this.mods = List.of();
            this.status = Component.literal(message(exception));
        }
    }

    private void rebuild() {
        resolveDraft();
        this.rebuildWidgets();
    }

    private void switchTab(Tab tab) {
        this.tab = tab;
        rebuild();
    }

    private void queue(boolean quit) {
        try {
            QualityRuntime.queueQualitySelection(this.draft, "player-quality", "");
            this.status = Component.translatable("modqualitypicker.player.queued");
            if (quit) {
                this.minecraft.stop();
                return;
            }
            rebuild();
        } catch (IOException exception) {
            this.minecraft.setScreen(new AlertScreen(
                    () -> this.minecraft.setScreen(this),
                    Component.translatable("modqualitypicker.world.error"),
                    Component.literal(message(exception))
            ));
        }
    }

    private void confirmQueueAndQuit() {
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    this.minecraft.setScreen(this);
                    if (confirmed) {
                        queue(true);
                    }
                },
                Component.translatable("modqualitypicker.player.queue_quit_confirm_title"),
                Component.translatable("modqualitypicker.player.queue_quit_confirm"),
                Component.translatable("modqualitypicker.player.queue_quit"),
                CommonComponents.GUI_CANCEL
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        String activeName = profileById(activeSelection.baseProfileId()).displayName();
        graphics.drawCenteredString(this.font, fit(Component.translatable("modqualitypicker.player.active", activeName).getString(), contentWidth()), this.width / 2, 20, 0xA8C8FF);
        if (restartRequired()) {
            graphics.drawCenteredString(this.font, fit(Component.translatable("modqualitypicker.player.restart_required").getString(), contentWidth()), this.width / 2, 78, 0xFFD36A);
        } else {
            graphics.drawCenteredString(this.font, fit(Component.translatable("modqualitypicker.player.no_restart").getString(), contentWidth()), this.width / 2, 78, 0x79D887);
        }

        if (tab == Tab.FEATURES) {
            renderFeatures(graphics);
        } else {
            renderMods(graphics);
        }
        graphics.drawCenteredString(this.font, fit(this.status.getString(), contentWidth()), this.width / 2, statusY(), 0xFF9A9A);
        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderFeatures(GuiGraphics graphics) {
        List<FeatureGroup> groups = adjustableGroups();
        int left = contentLeft();
        int contentWidth = contentWidth();
        int choiceX = left + contentWidth - featureChoiceWidth();
        int visible = visibleFeatureRows();
        for (int visibleIndex = 0; visibleIndex < visible; visibleIndex++) {
            int index = featureScroll + visibleIndex;
            if (index >= groups.size()) {
                break;
            }
            FeatureGroup group = groups.get(index);
            int y = CONTENT_TOP + visibleIndex * featureRowHeight();
            String selected = effectiveChoice(group);
            String active = activeChoice(group);
            FeatureChoice choice = group.findChoice(selected).orElse(null);
            int titleColor = choice != null && choice.experimental() ? 0xFFB86A : 0xFFFFFF;
            graphics.fill(left - 4, y - 3, left + contentWidth + 4, y + featureRowHeight() - 5, 0x55000000);
            graphics.drawString(this.font, fit(group.displayName(), Math.max(20, choiceX - left - 8)), left, y + 6, titleColor, false);
            String requirement = choice == null ? "" : requirementName(choice.applyRequirement()).getString();
            String state = Component.translatable("modqualitypicker.player.feature_state", choiceName(group, active), choiceName(group, selected), requirement).getString();
            graphics.drawString(this.font, fit(state, contentWidth), left, y + 24, active.equals(selected) ? 0x8FCB92 : 0xFFD36A, false);
            renderWrappedDescription(graphics, featureDescription(group, choice), left, y + 37, contentWidth);
        }
        if (groups.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("modqualitypicker.player.no_features"), this.width / 2, CONTENT_TOP + 24, 0xA0A0A0);
        }
        renderScrollbar(graphics, groups.size(), visible, featureScroll, CONTENT_TOP, contentBottom());
    }

    private void renderMods(GuiGraphics graphics) {
        int left = contentLeft();
        int contentWidth = contentWidth();
        int right = left + contentWidth;
        boolean compact = compactModRows();
        int modWidth = compact ? Math.max(80, contentWidth - 132) : contentWidth * 43 / 100;
        int stateWidth = compact ? contentWidth - modWidth - WIDGET_GAP : contentWidth * 29 / 100;
        int stateX = left + modWidth + WIDGET_GAP;
        int ownerX = stateX + stateWidth + WIDGET_GAP;
        if (compact) {
            graphics.drawString(this.font, Component.translatable("modqualitypicker.player.mod_header_compact"), left, CONTENT_TOP, 0xFFFFFF, false);
        } else {
            graphics.drawString(this.font, Component.translatable("modqualitypicker.player.mod_column"), left, CONTENT_TOP, 0xFFFFFF, false);
            graphics.drawString(this.font, Component.translatable("modqualitypicker.player.state_column"), stateX, CONTENT_TOP, 0xFFFFFF, false);
            graphics.drawString(this.font, Component.translatable("modqualitypicker.player.owner_column"), ownerX, CONTENT_TOP, 0xFFFFFF, false);
        }
        int visible = visibleModRows();
        for (int visibleIndex = 0; visibleIndex < visible; visibleIndex++) {
            int index = modScroll + visibleIndex;
            if (index >= mods.size()) {
                break;
            }
            String modId = mods.get(index);
            boolean current = loaded.enabledMods().getOrDefault(modId, false);
            boolean desired = desiredMods.getOrDefault(modId, false);
            String ownerId = definition.ownerOfMod(modId);
            String owner = ownerId.isBlank() ? Component.translatable("modqualitypicker.player.base_owned").getString() : ownerLabel(ownerId);
            String statusText = current == desired
                    ? (current ? Component.translatable("modqualitypicker.player.enabled").getString() : Component.translatable("modqualitypicker.player.disabled").getString())
                    : (current ? Component.translatable("modqualitypicker.player.disable_pending").getString() : Component.translatable("modqualitypicker.player.enable_pending").getString());
            int color = current == desired ? (current ? 0x79D887 : 0x888888) : 0xFFD36A;
            int y = CONTENT_TOP + 14 + visibleIndex * modRowHeight();
            if (visibleIndex % 2 == 0) {
                graphics.fill(left - 3, y - 2, right + 3, y + modRowHeight() - 2, 0x33000000);
            }
            graphics.drawString(this.font, fit(modId, Math.max(20, modWidth - WIDGET_GAP)), left, y, color, false);
            graphics.drawString(this.font, fit(statusText, Math.max(20, stateWidth - WIDGET_GAP)), stateX, y, color, false);
            if (compact) {
                String controlledBy = Component.translatable("modqualitypicker.player.controlled_by", owner).getString();
                graphics.drawString(this.font, fit(controlledBy, contentWidth), left, y + 10, 0xA8C8FF, false);
            } else {
                graphics.drawString(this.font, fit(owner, Math.max(20, right - ownerX)), ownerX, y, 0xA8C8FF, false);
            }
        }
        graphics.drawCenteredString(this.font, fit(Component.translatable("modqualitypicker.player.mod_count", mods.size()).getString(), contentWidth), this.width / 2, modSummaryY(), 0x808080);
        renderScrollbar(graphics, mods.size(), visible, modScroll, CONTENT_TOP + 12, contentBottom());
    }

    private void renderScrollbar(GuiGraphics graphics, int total, int visible, int scroll, int top, int bottom) {
        if (total <= visible || bottom <= top) {
            return;
        }
        int x = contentLeft() + contentWidth() + 5;
        int trackHeight = bottom - top;
        int thumbHeight = Math.max(12, trackHeight * visible / total);
        int travel = Math.max(1, trackHeight - thumbHeight);
        int maximumScroll = Math.max(1, total - visible);
        int thumbY = top + travel * scroll / maximumScroll;
        graphics.fill(x, top, x + 2, bottom, 0x55333333);
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int direction = -(int) Math.signum(deltaY);
        if (tab == Tab.FEATURES) {
            featureScroll = clamp(featureScroll + direction, 0, Math.max(0, adjustableGroups().size() - visibleFeatureRows()));
        } else {
            modScroll = clamp(modScroll + direction * 3, 0, Math.max(0, mods.size() - visibleModRows()));
        }
        rebuild();
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private List<FeatureGroup> adjustableGroups() {
        return definition.orderedGroups().stream().filter(FeatureGroup::playerAdjustable).toList();
    }

    private QualityProfile profileById(String id) {
        return profiles.stream().filter(profile -> profile.id().equals(id)).findFirst()
                .orElseGet(() -> profiles.isEmpty() ? QualityProfile.empty(id, id) : profiles.getFirst());
    }

    private String inheritedChoice(FeatureGroup group, QualityProfile base) {
        return base.featureChoices().getOrDefault(group.id(), group.defaultChoice());
    }

    private String effectiveChoice(FeatureGroup group) {
        if (effective == null) {
            return inheritedChoice(group, profileById(draft.baseProfileId()));
        }
        return effective.effectiveChoices().getOrDefault(group.id(), group.defaultChoice());
    }

    private String activeChoice(FeatureGroup group) {
        return QualityRuntime.appliedProfile()
                .map(profile -> profile.featureChoices().getOrDefault(group.id(), inheritedChoice(group, profileById(activeSelection.baseProfileId()))))
                .orElseGet(() -> inheritedChoice(group, profileById(activeSelection.baseProfileId())));
    }

    private Component choiceLabel(FeatureGroup group, String choiceId) {
        String label = choiceName(group, choiceId);
        return draft.featureOverrides().containsKey(group.id()) ? Component.literal(label + " *") : Component.literal(label);
    }

    private Tooltip featureTooltip(FeatureGroup group, String choiceId) {
        FeatureChoice choice = group.findChoice(choiceId).orElse(null);
        if (choice == null) {
            return Tooltip.create(Component.literal(group.description()));
        }
        String requirement = requirementName(choice.applyRequirement()).getString();
        String detail = group.description();
        if (!choice.description().isBlank() && !choice.description().equals(group.description())) {
            detail += "\n\n" + choice.description();
        }
        return Tooltip.create(Component.literal(detail + "\n\n" + requirement));
    }

    private String featureDescription(FeatureGroup group, FeatureChoice choice) {
        return choice != null && !choice.description().isBlank() ? choice.description() : group.description();
    }

    private void renderWrappedDescription(GuiGraphics graphics, String description, int x, int y, int width) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(description), width);
        int maximum = featureDescriptionLines();
        for (int index = 0; index < Math.min(maximum, lines.size()); index++) {
            graphics.drawString(this.font, lines.get(index), x, y + index * 10, 0xA0A0A0, false);
        }
    }

    private String choiceName(FeatureGroup group, String choiceId) {
        return group.findChoice(choiceId).map(FeatureChoice::displayName).orElse(choiceId);
    }

    private String ownerLabel(String groupId) {
        FeatureGroup group = definition.groups().get(groupId);
        if (group == null) {
            return groupId;
        }
        return group.displayName() + ": " + choiceName(group, effectiveChoice(group));
    }

    private Component requirementName(ApplyRequirement requirement) {
        return Component.translatable(switch (requirement) {
            case LIVE -> "modqualitypicker.player.requirement.live";
            case RESOURCE_RELOAD -> "modqualitypicker.player.requirement.resource_reload";
            case RESTART -> "modqualitypicker.player.requirement.restart";
            case NEW_WORLD -> "modqualitypicker.player.requirement.new_world";
        });
    }

    private boolean restartRequired() {
        if (effective == null || loaded == null) {
            return false;
        }
        if (!activeSelection.equals(draft)) {
            return true;
        }
        for (String modId : mods) {
            if (loaded.enabledMods().getOrDefault(modId, false) != desiredMods.getOrDefault(modId, false)) {
                return true;
            }
        }
        return false;
    }

    private void updateQueueButtons() {
        boolean available = effective != null && (!activeSelection.equals(draft) || restartRequired());
        if (queueButton != null) {
            queueButton.active = available;
        }
        if (queueQuitButton != null) {
            queueQuitButton.active = available;
        }
    }

    private Component tabLabel(Tab target) {
        Component label = Component.translatable(target == Tab.FEATURES ? "modqualitypicker.player.features" : "modqualitypicker.player.mods");
        return tab == target ? Component.literal("> ").append(label) : label;
    }

    private int visibleFeatureRows() {
        return Math.max(1, Math.max(0, contentBottom() - CONTENT_TOP) / featureRowHeight());
    }

    private int visibleModRows() {
        return Math.max(1, Math.max(0, contentBottom() - CONTENT_TOP - 14) / modRowHeight());
    }

    private void normalizeScrollOffsets() {
        this.featureScroll = clamp(this.featureScroll, 0, Math.max(0, adjustableGroups().size() - visibleFeatureRows()));
        this.modScroll = clamp(this.modScroll, 0, Math.max(0, this.mods.size() - visibleModRows()));
    }

    private int horizontalMargin() {
        return this.width < 360 ? 8 : 12;
    }

    private int contentWidth() {
        return Math.max(80, Math.min(MAX_CONTENT_WIDTH, this.width - horizontalMargin() * 2));
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private boolean compactFooter() {
        return contentWidth() < 420;
    }

    private int footerTop() {
        return this.height - (compactFooter() ? 50 : 27);
    }

    private int statusY() {
        return footerTop() - 12;
    }

    private int modSummaryY() {
        return statusY() - 12;
    }

    private int contentBottom() {
        return (this.tab == Tab.MODS ? modSummaryY() : statusY()) - 4;
    }

    private int featureChoiceWidth() {
        return Math.min(180, Math.max(100, contentWidth() * 2 / 5));
    }

    private int featureDescriptionLines() {
        return contentWidth() < 360 ? 3 : 2;
    }

    private int featureRowHeight() {
        return 42 + featureDescriptionLines() * 10;
    }

    private boolean compactModRows() {
        return contentWidth() < 380;
    }

    private int modRowHeight() {
        return compactModRows() ? 22 : 13;
    }

    private String fit(String value, int width) {
        if (this.font.width(value) <= width) {
            return value;
        }
        return this.font.plainSubstrByWidth(value, Math.max(1, width - this.font.width("..."))) + "...";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }
}
