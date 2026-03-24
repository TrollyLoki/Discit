package net.trollyloki.discit;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.trollyloki.discit.data.GuildData;
import net.trollyloki.discit.data.ServerData;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.trollyloki.discit.Utils.validateHostAddress;

@NullMarked
public class GuildManager {

    private static final ObjectMapper DATA_MAPPER = new ObjectMapper();

    private static File dataFile(String guildId) {
        return new File("data/" + guildId + ".json");
    }

    private final JDA jda;
    private final String guildId;
    private final GuildData data;

    private final Map<UUID, DashboardUpdater> updaters = new HashMap<>();

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

    private void save() {
        File dataFile = dataFile(guildId);
        try {
            dataFile.getParentFile().mkdirs();
            DATA_MAPPER.writerWithDefaultPrettyPrinter().writeValue(dataFile(guildId), data);
        } catch (IOException e) {
            System.err.println("FAILED TO SAVE DATA FOR GUILD " + guildId);
            e.printStackTrace();
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
        return member.getUnsortedRoles().contains(getAdminRole());
    }

    public @Nullable Role getAdminRole() {
        String roleId = data.getAdminRoleId();
        if (roleId == null) return null;
        return getGuild().getRoleById(roleId);
    }

    public @Nullable GuildMessageChannel getActionLogChannel() {
        String channelId = data.getActionLogChannelId();
        if (channelId == null) return null;
        return getGuild().getChannelById(GuildMessageChannel.class, channelId);
    }

    public @Nullable GuildMessageChannel getDashboardChannel() {
        String channelId = data.getDashboardChannelId();
        if (channelId == null) return null;
        return getGuild().getChannelById(GuildMessageChannel.class, channelId);
    }

    public void setAdminRole(@Nullable String roleId) {
        data.setAdminRoleId(roleId);
        save();
    }

    public void setActionLogChannel(@Nullable String channelId) {
        data.setActionLogChannelId(channelId);
        save();
    }

    public void setDashboardChannel(@Nullable String channelId) {
        data.setDashboardChannelId(channelId);
        save();
    }

    public void logAction(User user, String action) {
        GuildMessageChannel channel = getActionLogChannel();
        if (channel == null) return;
        channel.sendMessage(user.getAsMention() + " " + action).setAllowedMentions(Collections.emptySet()).queue();
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

    public synchronized UUID addServer(String host, int port, String fingerprint) {
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

        return serverId;
    }

    private void initServer(UUID serverId) {
        DashboardUpdater updater = new DashboardUpdater(this, serverId);
        updaters.put(serverId, updater);
        updater.start();
    }

    public synchronized @Nullable Server removeServer(UUID serverId) {
        ServerData serverData = data.getServers().remove(serverId);
        if (serverData == null) return null;
        save();

        try (DashboardUpdater updater = updaters.remove(serverId)) {
            updater.stop();
        }

        // Delete dashboard message
        if (data.getDashboardChannelId() != null && serverData.getDashboardMessageId() != null) {
            TextChannel channel = jda.getTextChannelById(data.getDashboardChannelId());
            if (channel != null) {
                channel.deleteMessageById(serverData.getDashboardMessageId()).queue();
            }
        }

        return serverData;
    }

    public void updateServer(UUID serverId) {
        DashboardUpdater updater = updaters.get(serverId);
        if (updater != null) {
            updater.update();
        }
    }

    public void setServerName(UUID serverId, @Nullable String name) {
        data.getServers().get(serverId).setName(name);
        save();
    }

    public void setServerToken(UUID serverId, @Nullable String token) {
        data.getServers().get(serverId).setToken(token);
        save();
    }

    public @Nullable String getDashboardMessageId(UUID serverId) {
        return data.getServers().get(serverId).getDashboardMessageId();
    }

    public void setDashboardMessageId(UUID serverId, @Nullable String messageId) {
        data.getServers().get(serverId).setDashboardMessageId(messageId);
        save();
    }

}
