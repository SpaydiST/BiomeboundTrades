package org.example1.biomeboundTrades.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TextUtil {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand()
            .toBuilder()
            .hexColors()
            .build();

    private TextUtil() {
    }

    public static Component color(String text) {
        return SERIALIZER.deserialize(text == null ? "" : text);
    }
}