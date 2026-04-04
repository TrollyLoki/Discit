package net.trollyloki.discit.data;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@NullMarked
public class GuildData {

    private final Map<UUID, ServerData> servers = new ConcurrentHashMap<>();

    private @Nullable String adminRoleId;
    private @Nullable String dashboardChannelId;
    private @Nullable String logChannelId;
    private long offlineAlertDelaySeconds = -1;

    public Map<UUID, ServerData> getServers() {
        return servers;
    }

    public @Nullable String getAdminRoleId() {
        return adminRoleId;
    }

    public void setAdminRoleId(@Nullable String adminRoleId) {
        this.adminRoleId = adminRoleId;
    }

    public @Nullable String getDashboardChannelId() {
        return dashboardChannelId;
    }

    public void setDashboardChannelId(@Nullable String dashboardChannelId) {
        this.dashboardChannelId = dashboardChannelId;
    }

    public @Nullable String getLogChannelId() {
        return logChannelId;
    }

    public void setLogChannelId(@Nullable String logChannelId) {
        this.logChannelId = logChannelId;
    }

    public long getOfflineAlertDelaySeconds() {
        return offlineAlertDelaySeconds;
    }

    public void setOfflineAlertDelaySeconds(long offlineAlertDelaySeconds) {
        this.offlineAlertDelaySeconds = offlineAlertDelaySeconds;
    }

}
