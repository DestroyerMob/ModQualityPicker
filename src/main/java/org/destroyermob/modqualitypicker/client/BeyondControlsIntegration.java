package org.destroyermob.modqualitypicker.client;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.entrypoint.ControlifyEntrypoint;
import dev.isxander.controlify.api.entrypoint.InitContext;
import dev.isxander.controlify.api.entrypoint.PreInitContext;
import dev.isxander.controlify.api.event.ControlifyEvents;
import dev.isxander.controlify.bindings.ControlifyBindings;
import net.minecraft.client.Minecraft;

/** Controller-only paging for the editor's custom profile and mod lists. */
public final class BeyondControlsIntegration implements ControlifyEntrypoint {
    @Override
    public void onControlifyPreInit(PreInitContext context) {
    }

    @Override
    public void onControlifyInit(InitContext context) {
        ControlifyEvents.ACTIVE_CONTROLLER_TICKED.register(event -> {
            if (!(Minecraft.getInstance().screen instanceof QualityProfileScreen screen)) return;
            var controller = event.controller();
            if (ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed()) {
                screen.controllerPageList(-1);
            } else if (ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed()) {
                screen.controllerPageList(1);
            }
        });
    }

    @Override
    public void onControllersDiscovered(ControlifyApi controlify) {
    }
}
