package net.trollyloki.discit;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;

@NullMarked
public final class AddressUtils {
    private AddressUtils() {
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
