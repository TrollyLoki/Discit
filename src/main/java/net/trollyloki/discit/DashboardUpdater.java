package net.trollyloki.discit;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.RequestException;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import net.trollyloki.jicsit.server.query.QueryApi;
import net.trollyloki.jicsit.server.query.ServerState;
import net.trollyloki.jicsit.server.query.ServerStatus;
import net.trollyloki.jicsit.server.query.ServerSubState;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.awt.*;
import java.io.Closeable;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static net.trollyloki.discit.Utils.formatDuration;
import static net.trollyloki.discit.Utils.serverDisplayName;

@NullMarked
public class DashboardUpdater implements Closeable {

    private static final Duration
            QUERY_TIMEOUT = Duration.ofSeconds(5),
            API_TIMEOUT = Duration.ofSeconds(3);

    private static final Color
            RED = Color.getHSBColor(.000f, .75f, 1.00f),
            YELLOW = Color.getHSBColor(.125f, .75f, 1.00f),
            GREEN = Color.getHSBColor(.375f, .75f, 1.00f);

    private final GuildManager guildManager;
    private final UUID serverId;
    private final Server server;

    private final ScheduledExecutorService executor;

    private @Nullable ScheduledFuture<?> scheduled;
    private @Nullable QueryApi queryApi;

    public DashboardUpdater(GuildManager guildManager, UUID serverId) {
        this.guildManager = guildManager;
        this.serverId = serverId;

        Server server = guildManager.getServer(serverId);
        if (server == null) throw new IllegalArgumentException("Unknown server ID: " + serverId);
        this.server = server;

        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    public synchronized void start() {
        if (scheduled == null) {
            scheduled = executor.scheduleAtFixedRate(this::run, 0, 500, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    private @Nullable ServerStatus previousServerStatus;
    private short previousGameStateVersion;
    private @Nullable String previousName;

    private @Nullable ServerGameState cachedGameState;

    private @Nullable String messageId;
    private boolean update = false;

    public void update() {
        update = true;
    }

    @Override
    public void close() {
        if (queryApi != null) {
            queryApi.close();
        }

        executor.execute(() -> {
            GuildMessageChannel channel = guildManager.getDashboardChannel();
            if (channel == null) return;

            if (messageId == null) return;

            channel.deleteMessageById(messageId).queue();
        });

        executor.shutdown();
    }

    private void run() {
        try {

            GuildMessageChannel channel = guildManager.getDashboardChannel();
            if (channel == null) {
                throw new IllegalStateException("Cannot access dashboard channel");
            }

            ServerStatus serverStatus;
            short gameStateVersion;
            String name;
            try {
                if (queryApi == null) queryApi = server.queryApi(QUERY_TIMEOUT);
                ServerState serverState = queryApi.pollServerState();

                // Update name if it changed
                if (!serverState.name().equals(server.getName())) {
                    guildManager.setServerName(serverId, serverState.name());
                }

                serverStatus = serverState.status();
                gameStateVersion = serverState.subStateVersion(ServerSubState.SERVER_GAME_STATE);
                name = serverState.name();
            } catch (SocketTimeoutException e) {
                serverStatus = ServerStatus.OFFLINE;
                gameStateVersion = 0;
                name = server.getName();
            }

            //TODO: Always retrying if we don't have an up-to-date cached game state can lead to HTTPS spam
            boolean queryHttps = serverStatus.isHttpsApiAvailable() && (cachedGameState == null || gameStateVersion != previousGameStateVersion);
            String errorMessage = null;
            if (queryHttps) {
                System.out.println("QUERYING HTTPS API for version " + gameStateVersion);
                cachedGameState = null;
                try {
                    HttpsApi httpsApi = server.httpsApi(API_TIMEOUT);
                    if (httpsApi.getPrivilegeLevel() == PrivilegeLevel.NOT_AUTHENTICATED) {
                        httpsApi.passwordlessLogin(PrivilegeLevel.CLIENT);
                    }
                    cachedGameState = httpsApi.queryServerState();
                } catch (ApiException e) {
                    errorMessage = e.getMessage();
                } catch (RequestException e) {
                    errorMessage = "Failed to query server game state";
                    e.printStackTrace();
                }
            }

            if (update || serverStatus != previousServerStatus || queryHttps || !Objects.equals(name, previousName)) {
                System.out.println("UPDATING DASHBOARD MESSAGE");
                List<ContainerChildComponent> components = new ArrayList<>();

                components.add(TextDisplay.of("## " + serverDisplayName(name)));
                components.add(TextDisplay.of(serverStatus.toString()));

                if (errorMessage != null || serverStatus == ServerStatus.PLAYING && cachedGameState != null) {
                    components.add(Separator.createDivider(Separator.Spacing.SMALL));
                    if (errorMessage != null) {
                        components.add(TextDisplay.of(errorMessage));
                    } else {
                        components.add(TextDisplay.of("### " + cachedGameState.activeSessionName()));
                        components.add(TextDisplay.of("Game Duration: " + formatDuration(cachedGameState.totalGameDuration())));
                        components.add(TextDisplay.of("### Current Players\n" + cachedGameState.connectedPlayerCount() + "/" + cachedGameState.playerLimit()));
                        components.add(TextDisplay.of("### Average Tick Rate\n" + cachedGameState.averageTickRate()));
                    }
                }

                components.add(Separator.createDivider(Separator.Spacing.SMALL));
                components.add(TextDisplay.of("-# Last updated " + TimeFormat.RELATIVE.now()));
                components.add(ActionRow.of(
                        Button.success("dashboard-update:" + serverId, "Update"),
                        Button.primary("dashboard-reload:" + serverId, "Reload"),
                        Button.secondary("dashboard-save:" + serverId, "Download Save"),
                        Button.secondary("dashboard-upload:" + serverId, "Upload Save")
                ));

                Container container = Container.of(components).withAccentColor(switch (serverStatus) {
                    case OFFLINE -> RED;
                    case IDLE, LOADING -> YELLOW;
                    case PLAYING -> GREEN;
                });

                if (messageId == null) messageId = guildManager.getDashboardMessageId(serverId);

                Message message = null;
                if (messageId != null) {
                    try {
                        // try to edit existing message
                        message = channel.editMessageComponentsById(messageId, container)
                                .useComponentsV2().complete();
                    } catch (ErrorResponseException e) {
                        if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE)
                            throw e;
                    }
                }
                if (message == null) {
                    // send a new message
                    message = channel.sendMessageComponents(container)
                            .useComponentsV2().complete();

                    messageId = message.getId();
                    guildManager.setDashboardMessageId(serverId, messageId);
                }

                previousServerStatus = serverStatus;
                previousGameStateVersion = gameStateVersion;
                previousName = name;

                update = false;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
