package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Focus target for custom-drawn list rows used by keyboard and controller navigation. */
final class ControllerFocusButton extends AbstractButton {
    private final Runnable action;

    ControllerFocusButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    @Override
    public void onPress() {
        this.action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.isFocused()) {
            graphics.renderOutline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0xFFFFFFFF);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
