package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class ClientCommands {
    private ClientCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("modqualitypicker")
                        .then(Commands.literal("open").executes(context -> openScreen()))
                        .then(Commands.literal("developer").executes(context -> openDeveloperScreen()))
                        .executes(context -> openScreen())
        );
    }

    private static int openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new PlayerQualityScreen(minecraft.screen)));
        return 1;
    }

    private static int openDeveloperScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new QualityProfileScreen(minecraft.screen)));
        return 1;
    }
}
