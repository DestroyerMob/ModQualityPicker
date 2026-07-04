package org.destroyermob.modqualitypicker.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.Locale;

final class ClientText {
    private ClientText() {
    }

    static Component text(String key, String fallback, Object... args) {
        if (I18n.exists(key)) {
            return Component.translatable(key, args);
        }
        return Component.literal(formatFallback(fallback, args));
    }

    private static String formatFallback(String fallback, Object... args) {
        return args.length == 0 ? fallback : String.format(Locale.ROOT, fallback, args);
    }
}
