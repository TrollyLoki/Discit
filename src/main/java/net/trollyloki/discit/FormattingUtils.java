package net.trollyloki.discit;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@NullMarked
public final class FormattingUtils {
    private FormattingUtils() {
    }

    private static final DateTimeFormatter SAVE_NAME_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("ddMMyy-HHmmss");

    public static String defaultSaveName(String sessionName, LocalDateTime dateTime) {
        return sessionName + "_" + SAVE_NAME_DATE_TIME_FORMATTER.format(dateTime);
    }

    public static String formatGameDuration(Duration duration) {
        return String.format("%,d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
    }

    public static String escapeAll(String text) {
        return text.replaceAll("([\\\\*_`|~#<@>\\[\\]()\\-.])", "\\\\$1");
    }

    public static String safeMonospace(String text) {
        return '`' + text.replaceAll("`", "") + '`';
    }

    public static String serverDisplayName(@Nullable String serverName) {
        if (serverName == null) return "*Unknown*";
        if (serverName.isEmpty()) return "*Unnamed*";
        else return serverName;
    }

    public static String escapedServerName(@Nullable String serverName) {
        return serverDisplayName(serverName == null ? null : escapeAll(serverName));
    }

    public static String inlineServerDisplayName(@Nullable String serverName) {
        return "**" + escapedServerName(serverName) + "**";
    }

    public static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 60 / 60;
        long minutes = totalSeconds / 60 % 60;
        long seconds = totalSeconds % 60;

        List<String> parts = new ArrayList<>(3);

        if (hours > 1) parts.add(hours + " hours");
        else if (hours == 1) parts.add("1 hour");

        if (minutes > 1) parts.add(minutes + " minutes");
        else if (minutes == 1) parts.add("1 minute");

        if (seconds > 1) parts.add(seconds + " seconds");
        else if (seconds == 1) parts.add("1 second");

        return String.join(" ", parts);
    }

}
