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
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.trollyloki.discit.FormattingUtils.*;
import static net.trollyloki.discit.InteractionListener.DASHBOARD_REFRESH_BUTTON_ID;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.*;
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
    private @Nullable CompletableFuture<Message> messageEditFuture;

    public DashboardUpdater(GuildManager guildManager, UUID serverId, boolean authenticated) {
        this.guildManager = guildManager;
        this.serverId = serverId;

        this.executor = Executors.newSingleThreadExecutor(serverThreadFactory(serverId, "Dashboard Update Thread"));

        this.authenticated = authenticated;
    }

    public synchronized void setInfo(@Nullable String name, ServerStatus status, @Nullable String message, @Nullable ServerGameState gameState, @Nullable Duration ping) {
        executor.execute(() -> {
            setMDC(guildManager);

            if (Objects.equals(name, this.name)
                    && status == this.status
                    && Objects.equals(message, this.message)
                    && Objects.equals(gameState, this.gameState)
            ) return;

            LOGGER.info("New dashboard info for {}: \"{}\" {} {}", serverNameForLog(name), status, message == null ? null : '"' + message + '"', gameState);

            this.name = name;
            this.status = status;
            this.message = message;
            this.gameState = gameState;
            this.ping = ping;

            this.lastUpdated = TimeFormat.RELATIVE.now();

            updateMessage();
        });
    }

    public synchronized void setAuthenticated(boolean authenticated) {
        executor.execute(() -> {
            setMDC(guildManager);

            if (authenticated == this.authenticated)
                return;

            this.authenticated = authenticated;

            updateMessage();
        });
    }

    public synchronized void shutdown() {
        executor.execute(() -> {
            setMDC(guildManager);

            if (messageEditFuture != null && !messageEditFuture.isDone()) {
                messageEditFuture.cancel(true);
                try {
                    messageEditFuture.join();
                } catch (Exception ignored) {
                }
            }

            GuildMessageChannel channel = guildManager.getDashboardChannel();
            if (channel == null) return;

            if (messageId == null) return;

            LOGGER.info("Deleting dashboard message for {}", serverNameForLog(name));
            channel.deleteMessageById(messageId).queue();
        });
        executor.shutdown();
    }

    public synchronized void updateMessage() {
        executor.execute(() -> {
            setMDC(guildManager);

            if (messageEditFuture != null && !messageEditFuture.isDone()) {
                LOGGER.info("Cancelling previous message edit request for {}", serverNameForLog(name));
                messageEditFuture.cancel(false);
            }

            GuildMessageChannel channel = guildManager.getDashboardChannel();
            if (channel == null) {
                LOGGER.warn("Cannot access dashboard channel");
                return;
            }

            Container container = createContainer();

            if (messageId == null) {
                messageId = guildManager.getDashboardMessageId(serverId);
            }
            if (messageId == null) {
                createNewMessage(channel, container);
                return;
            }

            String editingMessageId = messageId;
            try {
                messageEditFuture = channel.editMessageComponentsById(editingMessageId, container).useComponentsV2().submit();

                messageEditFuture.whenCompleteAsync((_, throwable) -> {
                    if (throwable == null || throwable instanceof CancellationException) return;
                    setMDC(guildManager);

                    if (!(throwable instanceof ErrorResponseException e) || e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                        LOGGER.warn("Error editing dashboard message for {}", serverNameForLog(name), throwable);
                        return;
                    }

                    if (!Objects.equals(messageId, editingMessageId)) {
                        // A new message has already been sent
                        return;
                    }

                    // Send new message and update messageId
                    createNewMessage(channel, container);

                }, executor);
            } catch (Exception e) {
                LOGGER.warn("Cannot edit dashboard message for {}", serverNameForLog(name), e);
            }
        });
    }

    private void createNewMessage(GuildMessageChannel channel, Container container) {
        try {
            LOGGER.info("Sending new dashboard message for {}", serverNameForLog(name));
            Message newMessage = channel.sendMessageComponents(container).useComponentsV2().complete();
            messageId = newMessage.getId();
            guildManager.updateDashboardMessageId(serverId, messageId);
        } catch (Exception exception) {
            LOGGER.warn("Error sending new dashboard message for {}", serverNameForLog(name), exception);
        }
    }

    private Container createContainer() {
        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("## " + escapedServerName(name)));
        components.add(TextDisplay.of(status != null ? status.toString() : "Unknown"));

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
