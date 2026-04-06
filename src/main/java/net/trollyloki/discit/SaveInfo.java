package net.trollyloki.discit;

import net.dv8tion.jda.api.utils.TimeFormat;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

import static net.trollyloki.discit.FormattingUtils.escapeAll;
import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;

@NullMarked
public record SaveInfo(String name, @Nullable String sessionName, Instant timestamp) {

    public String formatted(@Nullable String serverName) {
        return (sessionName != null ? escapeAll(sessionName) + " on " : "") + inlineServerDisplayName(serverName) + " at " + TimeFormat.DEFAULT.atInstant(timestamp);
    }

}
