package net.trollyloki.discit;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.trollyloki.discit.data.GuildData;
import net.trollyloki.discit.data.ServerData;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.trollyloki.discit.AddressUtils.validateHostAddress;

@NullMarked
public class GuildManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuildManager.class);

    private static final Emoji ALERT_EMOJI = Emoji.fromUnicode("⚠️");

    private static final ObjectMapper DATA_MAPPER = new ObjectMapper();

    private static File dataFile(String guildId) {
        return new File("data/" + guildId + ".json");
    }

    private final JDA jda;
    private final String guildId;
    private final GuildData data;

    private final Map<UUID, ServerMonitor> monitors = new HashMap<>();

    private GuildManager(JDA jda, String guildId, GuildData data) {
        this.jda = jda;
        this.guildId = guildId;
        this.data = data;
    }

    public static GuildManager load(JDA jda, String guildId) throws IOException {
        File dataFile = dataFile(guildId);
        GuildData data = dataFile.exists() ? DATA_MAPPER.readValue(dataFile, GuildData.class) : new GuildData();

        GuildManager guildManager = new GuildManager(jda, guildId, data);
        guildManager.data.getServers().keySet().forEach(guildManager::initServer);
        return guildManager;
    }

    private synchronized void save() {
        File dataFile = dataFile(guildId);
        try {
            boolean ignored = dataFile.getParentFile().mkdirs();
            DATA_MAPPER.writerWithDefaultPrettyPrinter().writeValue(dataFile(guildId), data);
        } catch (IOException e) {
            LOGGER.error("Failed to save data for guild {}", guildId, e);
        }
    }

    public Guild getGuild() {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("Guild missing: " + guildId);
        }
        return guild;
    }

    public boolean hasAdminRole(Member member) {
        Role adminRole = getAdminRole();
        return adminRole != null && member.getUnsortedRoles().contains(adminRole);
    }

    public @Nullable Role getAdminRole() {
        String roleId = data.getAdminRoleId();
        if (roleId == null) return null;
        return getGuild().getRoleById(roleId);
    }

    public @Nullable GuildMessageChannel getDashboardChannel() {
        String channelId = data.getDashboardChannelId();
        if (channelId == null) return null;
        return getGuild().getChannelById(GuildMessageChannel.class, channelId);
    }

    public @Nullable GuildMessageChannel getLogChannel() {
        String channelId = data.getLogChannelId();
        if (channelId == null) return null;
        return getGuild().getChannelById(GuildMessageChannel.class, channelId);
    }

    public @Nullable Duration getOfflineAlertDelay() {
        long seconds = data.getOfflineAlertDelaySeconds();
        return seconds < 0 ? null : Duration.ofSeconds(seconds);
    }

    public void setAdminRole(@Nullable String roleId) {
        data.setAdminRoleId(roleId);
        save();
    }

    public void setDashboardChannel(@Nullable String channelId) {
        data.setDashboardChannelId(channelId);
        save();
    }

    public void setLogChannel(@Nullable String channelId) {
        data.setLogChannelId(channelId);
        save();
    }

    public void setOfflineAlertDelay(@Nullable Duration delay) {
        if (delay != null && delay.isNegative()) {
            throw new IllegalArgumentException("Non-null delay cannot be negative");
        }
        data.setOfflineAlertDelaySeconds(delay == null ? -1 : delay.toSeconds());
        save();
    }

    public void logAction(User user, String action) {
        GuildMessageChannel channel = getLogChannel();
        if (channel == null) return;

        channel.sendMessage(user.getAsMention() + " " + action).setAllowedMentions(Collections.emptySet()).queue();
    }

    public void logAlert(String alert) {
        GuildMessageChannel channel = getLogChannel();
        if (channel == null) return;

        String text = ALERT_EMOJI.getFormatted() + " " + alert;
        Role role = getAdminRole();
        if (role != null) text += " " + getAdminRole().getAsMention();

        channel.sendMessage(text).queue();
    }

    public boolean isDashboard(@Nullable Channel channel) {
        return channel != null && channel.getId().equals(data.getDashboardChannelId());
    }

    public Map<UUID, Server> getServers() {
        return Collections.unmodifiableMap(data.getServers());
    }

    public @Nullable Server getServer(UUID serverId) {
        return data.getServers().get(serverId);
    }

    public synchronized Map.Entry<UUID, Server> addServer(String host, int port, String fingerprint) {
        if (validateHostAddress(host) == null) {
            throw new IllegalArgumentException("Invalid host address");
        }

        ServerData serverData = new ServerData(host, port, fingerprint);

        UUID serverId;
        do {
            serverId = UUID.randomUUID();
        } while (data.getServers().putIfAbsent(serverId, serverData) != null);
        save();

        initServer(serverId);

        return Map.entry(serverId, serverData);
    }

    private void initServer(UUID serverId) {
        monitors.put(serverId, new ServerMonitor(this, serverId));
    }

    public synchronized @Nullable Server removeServer(UUID serverId) {
        ServerData serverData = data.getServers().remove(serverId);
        if (serverData == null) return null;
        save();

        ServerMonitor monitor = monitors.remove(serverId);
        if (monitor != null) monitor.close();

        return serverData;
    }

    public void refreshServer(UUID serverId) {
        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) {
            monitor.refresh();
        } else {
            LOGGER.warn("Could not find monitor for server {}", serverId);
        }
    }

    public void updateServerName(UUID serverId, @Nullable String name) {
        ServerData server = data.getServers().get(serverId);
        if (server != null) {
            server.setName(name);
            save();
        }
    }

    public boolean setServerToken(UUID serverId, @Nullable String token) {
        ServerData server = data.getServers().get(serverId);
        if (server != null) {
            server.setToken(token);
            save();

            ServerMonitor monitor = monitors.get(serverId);
            if (monitor != null) monitor.getDashboardUpdater().setAuthenticated(server.hasToken());

            return true;
        }
        return false;
    }

    public @Nullable String getDashboardMessageId(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server != null) {
            return server.getDashboardMessageId();
        }
        return null;
    }

    public void updateDashboardMessageId(UUID serverId, @Nullable String messageId) {
        ServerData server = data.getServers().get(serverId);
        if (server != null) {
            server.setDashboardMessageId(messageId);
            save();
        }
    }

}
