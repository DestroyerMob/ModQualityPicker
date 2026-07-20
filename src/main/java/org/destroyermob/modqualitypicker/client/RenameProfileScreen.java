package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

final class RenameProfileScreen extends Screen {
    private final Screen parent;
    private final Component fieldLabel;
    private final String currentName;
    private final Consumer<String> onRename;
    private EditBox nameBox;

    RenameProfileScreen(Screen parent, String currentName, Consumer<String> onRename) {
        this(parent, Component.translatable("modqualitypicker.rename.title"), Component.translatable("modqualitypicker.rename.name"), currentName, onRename);
    }

    RenameProfileScreen(Screen parent, Component title, Component fieldLabel, String currentName, Consumer<String> onRename) {
        super(title);
        this.parent = parent;
        this.fieldLabel = fieldLabel;
        this.currentName = currentName;
        this.onRename = onRename;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameBox = new EditBox(
                this.font,
                centerX - 120,
                centerY - 12,
                240,
                20,
                this.fieldLabel
        );
        this.nameBox.setMaxLength(64);
        this.nameBox.setValue(this.currentName);
        this.nameBox.setFocused(true);
        this.addRenderableWidget(this.nameBox);

        this.addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.rename.confirm"), button -> submit())
                .bounds(centerX - 122, centerY + 18, 118, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("modqualitypicker.rename.cancel"), button -> this.minecraft.setScreen(this.parent))
                .bounds(centerX + 4, centerY + 18, 118, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 52, 0xFFFFFF);
        guiGraphics.drawString(
                this.font,
                this.fieldLabel,
                this.width / 2 - 120,
                this.height / 2 - 26,
                0xD0D0D0,
                false
        );
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private void submit() {
        String name = this.nameBox.getValue().trim();
        if (!name.isBlank()) {
            this.onRename.accept(name);
        }
        this.minecraft.setScreen(this.parent);
    }
}
