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
import static net.trollyloki.discit.interactions.ServerSettingsInteractions.SERVER_SETTINGS_BUTTON_ID;
import static net.trollyloki.discit.interactions.UploadInteractions.UPLOAD_BUTTON_ID;

@NullMarked
public class ServerUpdater implements Closeable {

    private static final Duration
            QUERY_TIMEOUT = Duration.ofSeconds(5),
            API_TIMEOUT = Duration.ofSeconds(3);

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
    private short lastGameStateVersion;
    private boolean lastHasToken;

    private @Nullable ServerGameState cachedGameState;
    private short cachedGameStateVersion;

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
            boolean queryHttps = serverStatus.isHttpsApiAvailable() && (update || cachedGameState == null || cachedGameStateVersion != gameStateVersion);
            String errorMessage = null;
            if (queryHttps) {
                System.out.println("QUERYING HTTPS API for version " + gameStateVersion);
                try {
                    HttpsApi httpsApi = server.httpsApi(API_TIMEOUT);
                    if (httpsApi.getPrivilegeLevel() == PrivilegeLevel.NOT_AUTHENTICATED) {
                        httpsApi.passwordlessLogin(PrivilegeLevel.CLIENT);
                    }
                    cachedGameState = httpsApi.queryServerState();
                    cachedGameStateVersion = gameStateVersion;
                } catch (ApiException e) {
                    errorMessage = "Unable to query game state: " + e.getMessage();
                } catch (RequestException e) {
                    errorMessage = "Failed to query game state";
                    e.printStackTrace();
                }
            }

            boolean hasToken = server.hasToken();
            if (update || !Objects.equals(name, lastName) || serverStatus != lastServerStatus || errorMessage != null || cachedGameStateVersion != lastGameStateVersion || hasToken != lastHasToken) {
                System.out.println("UPDATING DASHBOARD MESSAGE");

                Container container = createDashboardContainer(serverId.toString(), name, serverStatus, errorMessage, cachedGameState, hasToken);

                updateDashboardMessage(container);

                lastName = name;
                lastServerStatus = serverStatus;
                lastGameStateVersion = cachedGameStateVersion;
                lastHasToken = hasToken;

                update = false;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateDashboardMessage(Container container) {
        GuildMessageChannel channel = guildManager.getDashboardChannel();
        if (channel == null) {
            System.err.println("Cannot access dashboard channel");
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
            guildManager.setDashboardMessageId(serverId, messageId);
        }
    }

    private static Container createDashboardContainer(String serverId, @Nullable String name, ServerStatus status, @Nullable String errorMessage, @Nullable ServerGameState gameState, boolean hasToken) {
        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("## " + serverDisplayName(name)));
        components.add(TextDisplay.of(status.toString()));

        if (errorMessage != null || status == ServerStatus.PLAYING && gameState != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            if (errorMessage != null) {
                components.add(TextDisplay.of(errorMessage));
            } else {
                components.add(TextDisplay.of("### " + gameState.activeSessionName()));
                components.add(TextDisplay.of("Game Duration: " + formatDuration(gameState.totalGameDuration())));
                components.add(TextDisplay.of("### Current Players\n" + gameState.connectedPlayerCount() + "/" + gameState.playerLimit()));
                components.add(TextDisplay.of("### Average Tick Rate\n" + gameState.averageTickRate()));
            }
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
                    Button.secondary(buildId(SERVER_SETTINGS_BUTTON_ID, serverId), "Server Settings"),
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
