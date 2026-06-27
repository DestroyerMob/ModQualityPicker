package org.destroyermob.modqualitypicker;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.destroyermob.modqualitypicker.config.ModQualityPickerConfig;
import org.destroyermob.modqualitypicker.runtime.QualityRuntime;
import org.slf4j.Logger;

@Mod(ModQualityPicker.MOD_ID)
public final class ModQualityPicker {
    public static final String MOD_ID = "modqualitypicker";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ModQualityPicker(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ModQualityPickerConfig.SPEC);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                QualityRuntime.bootstrap();
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to bootstrap Mod Quality Picker profile storage", exception);
            }
        });
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}

