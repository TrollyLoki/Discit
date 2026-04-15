package net.trollyloki.discit.updater;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.*;

import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public abstract class MessageUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageUpdater.class);

    private final ExecutorService executor;

    private @Nullable String messageId;
    private @Nullable CompletableFuture<Message> messageEditFuture;

    public MessageUpdater(@Nullable String messageId, ThreadFactory threadFactory) {
        this.executor = Executors.newSingleThreadExecutor(threadFactory);

        this.messageId = messageId;
    }

    protected abstract @Nullable GuildMessageChannel getChannel();

    protected abstract void updateMessageId(String messageId);

    private void cancelMessageEditFuture() {
        if (messageEditFuture != null && !messageEditFuture.isDone()) {
            LOGGER.info("Cancelling pending message edit request");
            messageEditFuture.cancel(false);
            try {
                messageEditFuture.join();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void shutdown() {
        executor.execute(withMDC(() -> {
            cancelMessageEditFuture();

            GuildMessageChannel channel = getChannel();
            if (channel == null) return;

            if (messageId == null) return;

            LOGGER.info("Deleting message");
            channel.deleteMessageById(messageId).queue();
            messageId = null;
        }));
        executor.shutdown();
    }

    public synchronized void updateMessage(MessageTopLevelComponent component) {
        executor.execute(withMDC(() -> {
            cancelMessageEditFuture();

            GuildMessageChannel channel = getChannel();
            if (channel == null) return;

            if (messageId == null) {
                createNewMessage(channel, component);
                return;
            }

            String editingMessageId = messageId;
            try {
                messageEditFuture = channel.editMessageComponentsById(editingMessageId, component).useComponentsV2().submit();

                messageEditFuture.whenCompleteAsync(withMDC((_, throwable) -> {
                    if (throwable == null || throwable instanceof CancellationException) return;

                    if (!(throwable instanceof ErrorResponseException e) || e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                        LOGGER.warn("Error editing message", throwable);
                        return;
                    }

                    if (!Objects.equals(messageId, editingMessageId)) {
                        // A new message has already been sent
                        return;
                    }

                    // Send new message and update messageId
                    createNewMessage(channel, component);

                }), executor);
            } catch (Exception e) {
                LOGGER.warn("Cannot edit message", e);
            }
        }));
    }

    private void createNewMessage(GuildMessageChannel channel, MessageTopLevelComponent component) {
        try {
            LOGGER.info("Sending new message");
            Message newMessage = channel.sendMessageComponents(component).useComponentsV2().complete();
            messageId = newMessage.getId();
            updateMessageId(messageId);
        } catch (Exception exception) {
            LOGGER.warn("Error sending new message", exception);
        }
    }

}
