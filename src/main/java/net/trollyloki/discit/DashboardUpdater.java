package net.trollyloki.discit;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.api.utils.Timestamp;
import net.trollyloki.discit.interactions.ChangePasswordInteractions;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.query.ServerStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.trollyloki.discit.FormattingUtils.formatDuration;
import static net.trollyloki.discit.FormattingUtils.serverDisplayName;
import static net.trollyloki.discit.InteractionListener.DASHBOARD_REFRESH_BUTTON_ID;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.GREEN_ACCENT;
import static net.trollyloki.discit.InteractionUtils.RED_ACCENT;
import static net.trollyloki.discit.InteractionUtils.YELLOW_ACCENT;
import static net.trollyloki.discit.LoggingUtils.setMDC;
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
public class DashboardUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardUpdater.class);

    private final GuildManager guildManager;
    private final UUID serverId;

    private final ExecutorService executor;

    private @Nullable String name;
    private @Nullable ServerStatus status;
    private @Nullable String message;
    private @Nullable ServerGameState gameState;
    private @Nullable Duration ping;

    private @Nullable Timestamp lastUpdated;

    private boolean authenticated;

    private @Nullable String messageId;
    private @Nullable CompletableFuture<Message> messageUpdateFuture;

    public DashboardUpdater(GuildManager guildManager, UUID serverId, boolean authenticated) {
        this.guildManager = guildManager;
        this.serverId = serverId;

        this.executor = Executors.newSingleThreadExecutor();

        this.authenticated = authenticated;
    }

    private void execute(Runnable runnable) {
        executor.execute(() -> {
            setMDC(guildManager);
            runnable.run();
        });
    }

    public void setInfo(@Nullable String name, ServerStatus status, @Nullable String message, @Nullable ServerGameState gameState, @Nullable Duration ping) {
        execute(() -> {
            if (Objects.equals(name, this.name)
                    && status == this.status
                    && Objects.equals(message, this.message)
                    && Objects.equals(gameState, this.gameState)
            ) return;

            LOGGER.info("New dashboard info for server \"{}\": \"{}\" {} {}", name, status, message == null ? null : '"' + message + '"', gameState);

            this.name = name;
            this.status = status;
            this.message = message;
            this.gameState = gameState;
            this.ping = ping;

            this.lastUpdated = TimeFormat.RELATIVE.now();

            updateMessage();
        });
    }

    public void setAuthenticated(boolean authenticated) {
        execute(() -> {
            if (authenticated == this.authenticated)
                return;

            this.authenticated = authenticated;

            updateMessage();
        });
    }

    private void cancelMessageUpdate() {
        if (messageUpdateFuture != null && !messageUpdateFuture.isDone()) {
            LOGGER.info("Cancelling previous message update for server \"{}\"", name);
            messageUpdateFuture.cancel(false);
            try {
                messageUpdateFuture.join();
            } catch (CancellationException | CompletionException ignored) {
            }
        }
    }

    public void shutdown() {
        execute(() -> {
            cancelMessageUpdate();

            GuildMessageChannel channel = guildManager.getDashboardChannel();
            if (channel == null) return;

            if (messageId == null) return;

            LOGGER.info("Deleting dashboard message for server \"{}\"", name);
            channel.deleteMessageById(messageId).queue();
        });
        executor.shutdown();
    }

    public void updateMessage() {
        GuildMessageChannel channel = guildManager.getDashboardChannel();
        if (channel == null) {
            LOGGER.warn("Cannot access dashboard channel");
            return;
        }

        execute(() -> {

            // Cancel any pending request
            cancelMessageUpdate();

            Container container = createContainer();

            // Submit new edit request
            if (messageId == null)
                messageId = guildManager.getDashboardMessageId(serverId);
            if (messageId != null) {
                CompletableFuture<Message> future = channel.editMessageComponentsById(messageId, container).useComponentsV2().submit();

                future.exceptionallyComposeAsync(throwable -> {
                    if (future.isCancelled()) return future;
                    setMDC(guildManager);

                    if (throwable instanceof ErrorResponseException response && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                        messageUpdateFuture = sendNewDashboardMessage(channel, container);
                        return messageUpdateFuture;
                    } else {
                        LOGGER.warn("Error editing dashboard message for server \"{}\"", name, throwable);
                        return future;
                    }

                }, executor);

                messageUpdateFuture = future;
            } else {
                messageUpdateFuture = sendNewDashboardMessage(channel, container);
            }

        });
    }

    private CompletableFuture<Message> sendNewDashboardMessage(GuildMessageChannel channel, Container container) {
        CompletableFuture<Message> future = channel.sendMessageComponents(container).useComponentsV2().submit();

        future.whenCompleteAsync((newMessage, throwable) -> {
            setMDC(guildManager);

            if (newMessage != null) {
                messageId = newMessage.getId();
                guildManager.updateDashboardMessageId(serverId, messageId);
            }

            if (throwable != null) {
                LOGGER.warn("Error sending new dashboard message for server \"{}\"", name, throwable);
            }

        }, executor);
        return future;
    }

    private Container createContainer() {
        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("## " + serverDisplayName(name)));
        components.add(TextDisplay.of(status != null ? status.toString() : "Unknown"));

        if (message != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of(message));
        }

        if (gameState != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of("### " + gameState.activeSessionName()));
            components.add(TextDisplay.of("**Game Duration**\n" + formatDuration(gameState.totalGameDuration())));
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
            boolean playing = status == ServerStatus.PLAYING;

            List<ActionRowChildComponent> firstRow = new ArrayList<>(4);

            if (playing) {
                firstRow.add(Button.success(buildId(DASHBOARD_REFRESH_BUTTON_ID, serverId), "Refresh"));
            }

            if (!authenticated) {
                firstRow.add(Button.primary(buildId(AUTHENTICATE_BUTTON_ID, serverId), "Authenticate"));

                components.add(ActionRow.of(firstRow));
            } else {
                List<ActionRowChildComponent> secondRow = new ArrayList<>(3);
                List<ActionRowChildComponent> thirdRow = new ArrayList<>(3);

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

                components.add(ActionRow.of(firstRow));
                components.add(ActionRow.of(secondRow));
                components.add(ActionRow.of(thirdRow));
            }
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
