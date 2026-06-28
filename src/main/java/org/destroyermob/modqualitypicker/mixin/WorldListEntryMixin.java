package org.destroyermob.modqualitypicker.mixin;

import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.destroyermob.modqualitypicker.client.QualityWorldOpenFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {
    @Shadow
    @Final
    private SelectWorldScreen screen;

    @Shadow
    @Final
    LevelSummary summary;

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void modqualitypicker$checkQualityProfile(CallbackInfo callbackInfo) {
        if (QualityWorldOpenFlow.shouldBypass()) {
            return;
        }

        WorldSelectionList.WorldListEntry entry = (WorldSelectionList.WorldListEntry) (Object) this;
        if (QualityWorldOpenFlow.openWithCheck(this.screen, entry, this.summary)) {
            callbackInfo.cancel();
        }
    }
}

