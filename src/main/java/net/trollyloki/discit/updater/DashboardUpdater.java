package net.trollyloki.discit.updater;

import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.trollyloki.discit.GuildManager;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

import static net.trollyloki.discit.LoggingUtils.serverThreadFactory;

@NullMarked
public class DashboardUpdater extends MessageUpdater {

    private final GuildManager guildManager;
    private final UUID serverId;

    public DashboardUpdater(GuildManager guildManager, UUID serverId) {
        super(
                guildManager.getDashboardMessageId(serverId),
                serverThreadFactory(serverId, "Dashboard Update Thread")
        );
        this.guildManager = guildManager;
        this.serverId = serverId;
    }

    @Override
    protected @Nullable GuildMessageChannel getChannel() {
        return guildManager.getDashboardChannel();
    }

    @Override
    protected void updateMessageId(String messageId) {
        guildManager.updateDashboardMessageId(serverId, messageId);
    }

}
