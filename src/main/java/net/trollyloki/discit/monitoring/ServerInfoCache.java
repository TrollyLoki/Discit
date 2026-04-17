package net.trollyloki.discit.monitoring;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.api.utils.Timestamp;
import net.trollyloki.discit.GuildManager;
import net.trollyloki.discit.interactions.ChangePasswordInteractions;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.query.ServerStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

import static net.trollyloki.discit.FormattingUtils.*;
import static net.trollyloki.discit.InteractionListener.DASHBOARD_REFRESH_BUTTON_ID;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.interactions.AdvancedGameSettingsInteractions.AGS_BUTTON_ID;
import static net.trollyloki.discit.interactions.ChangePasswordInteractions.CHANGE_PASSWORD_BUTTON_ID;
import static net.trollyloki.discit.interactions.InvalidateTokensInteractions.INVALIDATE_TOKENS_BUTTON_ID;
import static net.trollyloki.discit.interactions.ListInteractions.AUTHENTICATE_BUTTON_ID;
import static net.trollyloki.discit.interactions.ReloadInteractions.RELOAD_BUTTON_ID;
import static net.trollyloki.discit.interactions.RenameInteractions.RENAME_BUTTON_ID;
import static net.trollyloki.discit.interactions.SaveInteractions.SAVE_BUTTON_ID;
import static net.trollyloki.discit.interactions.ServerOptionsInteractions.SERVER_OPTIONS_BUTTON_ID;
import static net.trollyloki.discit.interactions.UploadInteractions.UPLOAD_BUTTON_ID;

@NullMarked
public class ServerInfoCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerInfoCache.class);

    private final UUID serverId;

    private final DashboardUpdater dashboardUpdater;

    private @Nullable String name;
    private @Nullable ServerStatus status;
    private @Nullable String message;
    private @Nullable ServerGameState gameState;
    private @Nullable Duration ping;

    private @Nullable Timestamp lastUpdated;

    private boolean authenticated;

    public ServerInfoCache(GuildManager guildManager, UUID serverId, boolean authenticated) {
        this.serverId = serverId;

        this.dashboardUpdater = new DashboardUpdater(guildManager, serverId);

        this.authenticated = authenticated;
    }

    public synchronized void setInfo(@Nullable String name, ServerStatus status, @Nullable String message, @Nullable ServerGameState gameState, @Nullable Duration ping) {
        if (Objects.equals(name, this.name)
                && status == this.status
                && Objects.equals(message, this.message)
                && Objects.equals(gameState, this.gameState)
        ) return;

        LOGGER.info("New server info for {}: \"{}\" {} {}", serverNameForLog(name), status, message == null ? null : '"' + message + '"', gameState);

        this.name = name;
        this.status = status;
        this.message = message;
        this.gameState = gameState;
        this.ping = ping;

        this.lastUpdated = TimeFormat.RELATIVE.now();

        updateDashboardMessage();
    }

    public synchronized void setAuthenticated(boolean authenticated) {
        if (authenticated == this.authenticated)
            return;

        this.authenticated = authenticated;

        updateDashboardMessage();
    }

    private void updateDashboardMessage() {
        dashboardUpdater.updateMessage(createContainer());
    }

    public synchronized void shutdown() {
        dashboardUpdater.shutdown();
    }

    private Container createContainer() {
        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("## " + escapedServerName(name)));

        String statusString = status != null ? status.toString() : "Unknown";
        if (gameState != null && gameState.isGamePaused()) {
            statusString += " – Paused";
        }
        components.add(TextDisplay.of(statusString));

        if (message != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of(Emoji.fromUnicode("⚠️").getFormatted() + " " + message));
        }

        if (gameState != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of("### " + escapeAll(gameState.activeSessionName())));
            components.add(TextDisplay.of("**Game Duration**\n" + formatGameDuration(gameState.totalGameDuration())));
            components.add(TextDisplay.of("### Current Players\n" + gameState.connectedPlayerCount() + "/" + gameState.playerLimit()));
            components.add(TextDisplay.of("### Average Tick Rate\n" + gameState.averageTickRate()));
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        String subtext = "-# Last updated " + lastUpdated;
        if (ping != null) {
            subtext += String.format(" (%,d ms)", ping.toMillis());
        }
        components.add(TextDisplay.of(subtext));

        if (status.isHttpsApiAvailable()) {
            List<ActionRowChildComponent> firstRow = new ArrayList<>(4);
            List<ActionRowChildComponent> secondRow = new ArrayList<>(3);
            List<ActionRowChildComponent> thirdRow = new ArrayList<>(3);

            boolean playing = status == ServerStatus.PLAYING;
            if (playing) {
                firstRow.add(Button.success(buildId(DASHBOARD_REFRESH_BUTTON_ID, serverId), "Refresh"));
            }

            if (!authenticated) {
                firstRow.add(Button.primary(buildId(AUTHENTICATE_BUTTON_ID, serverId), "Authenticate"));
            } else {
                if (playing) {
                    firstRow.add(Button.primary(buildId(RELOAD_BUTTON_ID, serverId), "Reload Session"));
                    firstRow.add(Button.secondary(buildId(SAVE_BUTTON_ID, serverId), "Download Save").withEmoji(Emoji.fromUnicode("💾")));
                }
                firstRow.add(Button.secondary(buildId(UPLOAD_BUTTON_ID, serverId), "Upload Save").withEmoji(Emoji.fromUnicode("📡")));

                secondRow.add(Button.secondary(buildId(RENAME_BUTTON_ID, serverId), "Rename Server").withEmoji(Emoji.fromUnicode("🪧")));
                secondRow.add(Button.secondary(buildId(SERVER_OPTIONS_BUTTON_ID, serverId), "Server Options").withEmoji(Emoji.fromUnicode("⚙️")));
                if (playing) {
                    secondRow.add(Button.secondary(buildId(AGS_BUTTON_ID, serverId), "Advanced Game Settings").withEmoji(Emoji.fromUnicode("✏️")));
                }

                thirdRow.add(changePasswordButton(ChangePasswordInteractions.PasswordType.CLIENT).withEmoji(Emoji.fromUnicode("🔓")));
                thirdRow.add(changePasswordButton(ChangePasswordInteractions.PasswordType.ADMIN).withEmoji(Emoji.fromUnicode("🔐")));
                thirdRow.add(Button.danger(buildId(INVALIDATE_TOKENS_BUTTON_ID, serverId), "Invalidate Tokens"));
            }

            components.add(ActionRow.of(firstRow));
            if (!secondRow.isEmpty()) components.add(ActionRow.of(secondRow));
            if (!thirdRow.isEmpty()) components.add(ActionRow.of(thirdRow));
        }

        return Container.of(components).withAccentColor(switch (status) {
            case OFFLINE -> RED_ACCENT;
            case IDLE, LOADING -> YELLOW_ACCENT;
            case PLAYING -> GREEN_ACCENT;
        });
    }

    private Button changePasswordButton(ChangePasswordInteractions.PasswordType type) {
        return Button.secondary(
                buildId(CHANGE_PASSWORD_BUTTON_ID, serverId, type.name().toLowerCase(Locale.ROOT)),
                "Change " + type
        );
    }

}
