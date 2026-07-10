package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

public final class QualityOptionsButton {
    private QualityOptionsButton() {
    }

    public static LayoutElement wrapOptionsContents(LayoutElement vanillaContents, OptionsScreen screen) {
        AbstractWidget qualityButton = createQualityCycleButton(screen);
        if (vanillaContents instanceof GridLayout gridLayout) {
            gridLayout.addChild(qualityButton, 5, 0);
            return gridLayout;
        }

        LinearLayout layout = LinearLayout.vertical().spacing(4);
        layout.addChild(vanillaContents);
        layout.addChild(qualityButton);
        return layout;
    }

    private static AbstractWidget createQualityCycleButton(OptionsScreen screen) {
        Button button = Button.builder(Component.translatable("modqualitypicker.settings.button"), ignored ->
                        screen.getMinecraft().setScreen(new PlayerQualityScreen(screen)))
                .bounds(0, 0, 150, 20)
                .build();
        button.setTooltip(Tooltip.create(Component.translatable("modqualitypicker.settings.open_note")));
        return button;
    }
}
