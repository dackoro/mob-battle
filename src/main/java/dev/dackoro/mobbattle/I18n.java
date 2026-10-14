package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import javax.annotation.Nonnull;

/**
 * Player-facing text. Keys live in Server/Languages/<locale>/server.lang as {@code mobbattle.<key>}; the client
 * shows each player the text of their own language and falls back to en-US. Server log lines stay in English.
 */
final class I18n {
    static final String PREFIX = "server.mobbattle.";

    private I18n() {
    }

    /** {@code tr("chat.heal.done").param("name", name)} reads {@code mobbattle.chat.heal.done} from the .lang. */
    @Nonnull
    static Message tr(@Nonnull String key) {
        return Message.translation(PREFIX + key);
    }

    /** The full id (with "server.") for places that take an id string, such as command descriptions. */
    @Nonnull
    static String id(@Nonnull String key) {
        return PREFIX + key;
    }

    /**
     * Sets a {@code .Text} property. The client takes a translation Message there (vanilla pages do, params too) but
     * drops the connection on a raw one ("CustomUI Set command couldn't set value", 2026-10-14, panel buttons), so
     * raw text goes as a plain String. Colored or joined messages belong in {@code .TextSpans}, never here.
     */
    static void text(@Nonnull UICommandBuilder ui, @Nonnull String selector, @Nonnull Message m) {
        if (m.getMessageId() == null) {
            String raw = m.getRawText();
            ui.set(selector, raw != null ? raw : "");
        } else {
            ui.set(selector, m);
        }
    }
}
