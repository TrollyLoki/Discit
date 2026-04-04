package net.trollyloki.discit;

import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.query.QueryApi;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.net.SocketException;
import java.time.Duration;

@NullMarked
public interface Server {

    String getHost();

    int getPort();

    String getFingerprint();

    @Nullable String getName();

    boolean hasToken();

    QueryApi queryApi(@Nullable Duration timeout) throws SocketException;

    HttpsApi httpsApi(@Nullable Duration timeout);

}
