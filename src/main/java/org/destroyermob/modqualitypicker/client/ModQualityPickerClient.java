package org.destroyermob.modqualitypicker.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.destroyermob.modqualitypicker.ModQualityPicker;

import java.util.function.Supplier;

@Mod(value = ModQualityPicker.MOD_ID, dist = Dist.CLIENT)
public final class ModQualityPickerClient {
    public ModQualityPickerClient(IEventBus modEventBus, ModContainer modContainer) {
        Supplier<IConfigScreenFactory> screenFactory = () -> (container, parent) -> new QualityProfileScreen(parent);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, screenFactory);
        NeoForge.EVENT_BUS.addListener(ClientCommands::register);
    }
}
