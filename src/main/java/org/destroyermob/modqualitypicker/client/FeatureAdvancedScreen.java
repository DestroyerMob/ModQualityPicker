package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.destroyermob.modqualitypicker.profile.ApplyRequirement;
import org.destroyermob.modqualitypicker.profile.FeatureChoice;
import org.destroyermob.modqualitypicker.profile.FeatureGroup;
import org.destroyermob.modqualitypicker.profile.FeatureScope;

import java.util.List;
import java.util.function.BiConsumer;

final class FeatureAdvancedScreen extends Screen {
    private final Screen parent;
    private final BiConsumer<FeatureGroup, FeatureChoice> onDone;
    private FeatureGroup group;
    private FeatureChoice choice;

    FeatureAdvancedScreen(Screen parent, FeatureGroup group, FeatureChoice choice, BiConsumer<FeatureGroup, FeatureChoice> onDone) {
        super(Component.translatable("modqualitypicker.editor.feature_advanced"));
        this.parent = parent;
        this.group = group;
        this.choice = choice;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        int width = Math.min(360, this.width - 24);
        int x = (this.width - width) / 2;
        int y = Math.max(34, this.height / 2 - 72);

        addRenderableWidget(CycleButton.builder((FeatureScope value) -> Component.literal(value.name()))
                .withValues(FeatureScope.values())
                .withInitialValue(this.group.scope())
                .create(x, y, width, 20, Component.translatable("modqualitypicker.editor.feature_scope_label"),
                        (button, value) -> this.group = this.group.withScope(value)));
        addRenderableWidget(CycleButton.builder((ApplyRequirement value) -> Component.literal(value.name()))
                .withValues(ApplyRequirement.values())
                .withInitialValue(this.choice.applyRequirement())
                .create(x, y + 24, width, 20, Component.translatable("modqualitypicker.editor.feature_requirement_label"),
                        (button, value) -> this.choice = this.choice.withApplyRequirement(value)));
        addRenderableWidget(CycleButton.onOffBuilder(this.group.playerAdjustable())
                .create(x, y + 48, width, 20, Component.translatable("modqualitypicker.editor.feature_adjustable_label"),
                        (button, value) -> this.group = this.group.withPlayerAdjustable(value)));
        addRenderableWidget(CycleButton.onOffBuilder(this.choice.experimental())
                .create(x, y + 72, width, 20, Component.translatable("modqualitypicker.editor.feature_experimental_label"),
                        (button, value) -> this.choice = this.choice.withExperimental(value)));

        List<String> choiceIds = List.copyOf(this.group.choices().keySet());
        addRenderableWidget(CycleButton.builder((String id) -> Component.literal(this.group.findChoice(id).map(FeatureChoice::displayName).orElse(id)))
                .withValues(choiceIds)
                .withInitialValue(this.group.defaultChoice())
                .create(x, y + 96, width, 20, Component.translatable("modqualitypicker.editor.feature_pack_default_label"),
                        (button, value) -> this.group = this.group.withDefaultChoice(value)));

        int buttonWidth = (width - 4) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose())
                .bounds(x, y + 126, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.minecraft.setScreen(this.parent))
                .bounds(x + buttonWidth + 4, y + 126, width - buttonWidth - 4, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal(this.group.displayName() + " / " + this.choice.displayName()), this.width / 2, 24, 0xA8C8FF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private void saveAndClose() {
        this.onDone.accept(this.group, this.choice);
        this.minecraft.setScreen(this.parent);
    }
}
