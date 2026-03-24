package net.trollyloki.discit;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@NullMarked
public final class Utils {
    private Utils() {
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

    // It's probably still a good idea to set up an external firewall, but checking this can't hurt
    private static boolean isPublic(InetAddress address) {
        return !address.isAnyLocalAddress() &&
                !address.isLoopbackAddress() &&
                !address.isSiteLocalAddress() &&
                !address.isLinkLocalAddress() &&
                !address.isMulticastAddress();
    }

    public static @Nullable InetAddress validateHostAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (isPublic(address) || "true".equals(System.getenv("ACCEPT_LOCAL_ADDRESSES"))) {
                return address;
            }
        } catch (UnknownHostException ignored) {
        }
        return null;
    }

}
