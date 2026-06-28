package org.destroyermob.modqualitypicker.mixin;

import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.destroyermob.modqualitypicker.client.QualityOptionsButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
            ),
            index = 0,
            require = 0
    )
    private LayoutElement modqualitypicker$addQualitySelector(LayoutElement vanillaContents) {
        return QualityOptionsButton.wrapOptionsContents(vanillaContents, (OptionsScreen) (Object) this);
    }
}
