package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

final class FeatureManagementScreen extends Screen {
    record Action(Component label, Runnable action) {
    }

    private final Screen parent;
    private final List<Action> actions;

    FeatureManagementScreen(Screen parent, List<Action> actions) {
        super(Component.translatable("modqualitypicker.editor.manage_features"));
        this.parent = parent;
        this.actions = List.copyOf(actions);
    }

    @Override
    protected void init() {
        int width = Math.min(320, this.width - 24);
        int x = (this.width - width) / 2;
        int columns = width >= 240 ? 2 : 1;
        int gap = 4;
        int buttonWidth = columns == 1 ? width : (width - gap) / 2;
        int rows = (this.actions.size() + columns - 1) / columns;
        int top = Math.max(38, this.height / 2 - (rows * 24 + 24) / 2);
        for (int index = 0; index < this.actions.size(); index++) {
            Action action = this.actions.get(index);
            int column = index % columns;
            int row = index / columns;
            int buttonX = x + column * (buttonWidth + gap);
            addRenderableWidget(Button.builder(action.label(), button -> runAction(action.action()))
                    .bounds(buttonX, top + row * 24, buttonWidth, 20).build());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.minecraft.setScreen(this.parent))
                .bounds(x, top + rows * 24 + 4, width, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private void runAction(Runnable action) {
        this.minecraft.setScreen(this.parent);
        action.run();
    }
}
