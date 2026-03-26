package net.trollyloki.discit;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@NullMarked
public final class FormattingUtils {
    private FormattingUtils() {
    }

    private static final DateTimeFormatter SAVE_NAME_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("ddMMyy-HHmmss");

    public static String defaultSaveName(String sessionName, LocalDateTime dateTime) {
        return sessionName + "_" + SAVE_NAME_DATE_TIME_FORMATTER.format(dateTime);
    }

    public static String formatDuration(Duration duration) {
        return String.format("%,d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    public static String serverDisplayName(@Nullable String serverName) {
        if (serverName == null) return "*Unknown*";
        if (serverName.isEmpty()) return "*Unnamed*";
        else return serverName;
    }

    public static String inlineServerDisplayName(@Nullable String serverName) {
        return "**" + serverDisplayName(serverName) + "**";
    }

}
