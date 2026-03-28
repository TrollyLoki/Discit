package net.trollyloki.discit;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

@NullMarked
public final class AddressUtils {
    private AddressUtils() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AddressUtils.class);

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
        LOGGER.info("Invalid host address \"{}\"", host);
        return null;
    }

}
