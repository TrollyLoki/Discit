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
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import net.trollyloki.jicsit.server.query.QueryApi;
import net.trollyloki.jicsit.server.query.ServerState;
import net.trollyloki.jicsit.server.query.ServerStatus;
import net.trollyloki.jicsit.server.query.ServerSubState;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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

import static net.trollyloki.discit.FormattingUtils.formatDuration;
import static net.trollyloki.discit.FormattingUtils.serverDisplayName;
import static net.trollyloki.discit.InteractionListener.DASHBOARD_UPDATE_BUTTON_ID;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.GREEN_ACCENT;
import static net.trollyloki.discit.InteractionUtils.RED_ACCENT;
import static net.trollyloki.discit.InteractionUtils.YELLOW_ACCENT;
import static net.trollyloki.discit.interactions.AdvancedGameSettingsInteractions.AGS_BUTTON_ID;
import static net.trollyloki.discit.interactions.ListInteractions.AUTHENTICATE_BUTTON_ID;
import static net.trollyloki.discit.interactions.ReloadInteractions.RELOAD_BUTTON_ID;
import static net.trollyloki.discit.interactions.RenameInteractions.RENAME_BUTTON_ID;
import static net.trollyloki.discit.interactions.SaveInteractions.SAVE_BUTTON_ID;
import static net.trollyloki.discit.interactions.ServerOptionsInteractions.SERVER_OPTIONS_BUTTON_ID;
import static net.trollyloki.discit.interactions.UploadInteractions.UPLOAD_BUTTON_ID;

@NullMarked
public class ServerUpdater implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerUpdater.class);

    private static final Duration
            QUERY_TIMEOUT = Duration.ofSeconds(5),
            API_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUERY_RETRY_INTERVAL_NANOS = Duration.ofSeconds(5).toNanos();

    private final GuildManager guildManager;
    private final UUID serverId;
    private final Server server;

    private final ScheduledExecutorService executor;

    private @Nullable ScheduledFuture<?> scheduled;
    private @Nullable QueryApi queryApi;

    public ServerUpdater(GuildManager guildManager, UUID serverId) {
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

    private @Nullable String lastName;
    private @Nullable ServerStatus lastServerStatus;
    private @Nullable String lastErrorMessage;
    private short lastGameStateVersion;
    private boolean lastHasToken;

    private short cachedGameStateVersion;
    private @Nullable Long lastQueryNanoTime;
    private @Nullable ServerGameState cachedGameState;
    private @Nullable String cachedErrorMessage;

    private @Nullable String messageId;
    private boolean update = false;

    public void update() {
        update = true;
    }

    @Override
    public void close() {
        if (queryApi != null) {
            LOGGER.info("Closing update socket");
            queryApi.close();
        }

        executor.execute(() -> {
            GuildMessageChannel channel = guildManager.getDashboardChannel();
            if (channel == null) return;

            if (messageId == null) return;

            LOGGER.info("Deleting dashboard message for server \"{}\"", server.getName());
            channel.deleteMessageById(messageId).queue();
        });

        executor.shutdown();
    }

    private void run() {
        try {
            MDC.put("guild", guildManager.getGuild().getName());

            ServerStatus serverStatus;
            short gameStateVersion;
            String name;
            try {
                if (queryApi == null) queryApi = server.queryApi(QUERY_TIMEOUT);
                ServerState serverState = queryApi.pollServerState();

                // Update name if it changed
                if (!serverState.name().equals(server.getName())) {
                    guildManager.updateServerName(serverId, serverState.name());
                }

                serverStatus = serverState.status();
                gameStateVersion = serverState.subStateVersion(ServerSubState.SERVER_GAME_STATE);
                name = serverState.name();
            } catch (SocketTimeoutException e) {
                serverStatus = ServerStatus.OFFLINE;
                gameStateVersion = 0;
                name = server.getName();
            }

            if (serverStatus.isHttpsApiAvailable() && (update
                    || cachedGameStateVersion != gameStateVersion
                    || (cachedGameState == null && (lastQueryNanoTime == null || (System.nanoTime() - lastQueryNanoTime) >= QUERY_RETRY_INTERVAL_NANOS))
            )) {
                LOGGER.info("Querying HTTPS API of server \"{}\" for game state version {}", name, gameStateVersion);
                cachedGameStateVersion = gameStateVersion;
                lastQueryNanoTime = System.nanoTime();

                cachedGameState = null;
                try {
                    HttpsApi httpsApi = server.httpsApi(API_TIMEOUT);
                    if (httpsApi.getPrivilegeLevel() == PrivilegeLevel.NOT_AUTHENTICATED) {
                        httpsApi.passwordlessLogin(PrivilegeLevel.CLIENT);
                    }
                    cachedGameState = httpsApi.queryServerState();
                    cachedErrorMessage = null;
                } catch (ApiException e) {
                    cachedErrorMessage = "Unable to query game state: " + e.getMessage();
                } catch (Exception e) {
                    cachedErrorMessage = "Failed to query game state";
                    LOGGER.warn("Failed to query game state of server \"{}\"", name, e);
                }
            }

            boolean hasToken = server.hasToken();
            if (update
                    || !Objects.equals(name, lastName)
                    || serverStatus != lastServerStatus
                    || !Objects.equals(cachedErrorMessage, lastErrorMessage)
                    || cachedGameStateVersion != lastGameStateVersion
                    || hasToken != lastHasToken
            ) {
                LOGGER.info("Updating dashboard message for server \"{}\"", name);

                Container container = createDashboardContainer(serverId.toString(), name, serverStatus, cachedErrorMessage, cachedGameState, hasToken);
                updateDashboardMessage(container);

                lastName = name;
                lastServerStatus = serverStatus;
                lastErrorMessage = cachedErrorMessage;
                lastGameStateVersion = cachedGameStateVersion;
                lastHasToken = hasToken;

                update = false;
            }

        } catch (Exception e) {
            LOGGER.warn("Unexpected exception in update loop", e);
        }
    }

    private void updateDashboardMessage(Container container) {
        GuildMessageChannel channel = guildManager.getDashboardChannel();
        if (channel == null) {
            LOGGER.warn("Cannot access dashboard channel");
            return;
        }

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
            guildManager.updateDashboardMessageId(serverId, messageId);
        }
    }

    private static Container createDashboardContainer(String serverId, @Nullable String name, ServerStatus status, @Nullable String errorMessage, @Nullable ServerGameState gameState, boolean hasToken) {
        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("## " + serverDisplayName(name)));
        components.add(TextDisplay.of(status.toString()));

        if (status.isHttpsApiAvailable() && errorMessage != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of(errorMessage));
        }

        if (status == ServerStatus.PLAYING && gameState != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of("### " + gameState.activeSessionName()));
            components.add(TextDisplay.of("**Game Duration**\n" + formatDuration(gameState.totalGameDuration())));
            components.add(TextDisplay.of("### Current Players\n" + gameState.connectedPlayerCount() + "/" + gameState.playerLimit()));
            components.add(TextDisplay.of("### Average Tick Rate\n" + gameState.averageTickRate()));
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        components.add(TextDisplay.of("-# Last updated " + TimeFormat.RELATIVE.now()));

        Button updateButton = Button.success(buildId(DASHBOARD_UPDATE_BUTTON_ID, serverId), "Update");
        if (!status.isHttpsApiAvailable()) {
            components.add(ActionRow.of(updateButton));
        } else if (hasToken) {
            components.add(ActionRow.of(
                    updateButton,
                    Button.primary(buildId(RELOAD_BUTTON_ID, serverId), "Reload Session"),
                    Button.secondary(buildId(SAVE_BUTTON_ID, serverId), "Download Save"),
                    Button.secondary(buildId(UPLOAD_BUTTON_ID, serverId), "Upload Save")
            ));
            components.add(ActionRow.of(
                    Button.secondary(buildId(RENAME_BUTTON_ID, serverId), "Rename Server"),
                    Button.secondary(buildId(SERVER_OPTIONS_BUTTON_ID, serverId), "Server Options"),
                    Button.secondary(buildId(AGS_BUTTON_ID, serverId), "Advanced Game Settings")
            ));
        } else {
            components.add(ActionRow.of(
                    updateButton,
                    Button.primary(buildId(AUTHENTICATE_BUTTON_ID, serverId), "Authenticate")
            ));
        }

        return Container.of(components).withAccentColor(switch (status) {
            case OFFLINE -> RED_ACCENT;
            case IDLE, LOADING -> YELLOW_ACCENT;
            case PLAYING -> GREEN_ACCENT;
        });
    }

}
