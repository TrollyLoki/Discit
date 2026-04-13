package net.trollyloki.discit.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.trustmanager.FingerprintBasedTrustManager;
import net.trollyloki.jicsit.server.query.QueryApi;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.TrustManager;
import java.net.SocketException;
import java.security.cert.CertificateException;
import java.time.Duration;

@NullMarked
public class ServerData implements Server {

    private final String host;
    private final int port;
    private final String fingerprint;

    private final TrustManager trustManager;

    private @Nullable String name;
    private @Nullable String token;

    private @Nullable String dashboardMessageId;
    private @Nullable String serverChannelId;

    @JsonCreator
    public ServerData(
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("fingerprint") String fingerprint
    ) {
        this.host = host;
        this.port = port;
        this.fingerprint = fingerprint;
        this.trustManager = new FingerprintBasedTrustManager() {
            @Override
            protected void checkFingerprintTrusted(String fingerprint) throws CertificateException {
                if (!fingerprint.equals(getFingerprint())) {
                    throw new CertificateException("Incorrect server fingerprint: " + fingerprint);
                }
            }
        };
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getFingerprint() {
        return fingerprint;
    }

    @Override
    public @Nullable String getName() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = name;
    }

    @Override
    public @Nullable String getToken() {
        return token;
    }

    public void setToken(@Nullable String token) {
        this.token = token;
    }

    public @Nullable String getDashboardMessageId() {
        return dashboardMessageId;
    }

    public void setDashboardMessageId(@Nullable String dashboardMessageId) {
        this.dashboardMessageId = dashboardMessageId;
    }

    public @Nullable String getServerChannelId() {
        return serverChannelId;
    }

    public void setServerChannelId(@Nullable String serverChannelId) {
        this.serverChannelId = serverChannelId;
    }

    @Override
    public QueryApi queryApi(@Nullable Duration timeout) throws SocketException {
        return QueryApi.of(host, port, timeout);
    }

    @Override
    public HttpsApi httpsApi(@Nullable Duration timeout) {
        HttpsApi httpsApi = HttpsApi.of(host, port, timeout, trustManager);
        httpsApi.setToken(token);
        return httpsApi;
    }

}
