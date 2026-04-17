package net.trollyloki.discit;

import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@NullMarked
public class MessageLinesUpdater {

    private final InteractionHook hook;
    private final List<String> messageLines;

    private @Nullable CompletableFuture<?> messageUpdateFuture = null;
    private boolean stopped = false;

    public MessageLinesUpdater(InteractionHook hook, List<String> messageLines) {
        this.hook = hook;
        this.messageLines = messageLines;
    }

    private void waitForUpdate(boolean cancel) {
        synchronized (messageLines) {
            if (messageUpdateFuture != null) {
                if (cancel) messageUpdateFuture.cancel(true);
                try {
                    messageUpdateFuture.join();
                } catch (CompletionException | CancellationException ignored) {
                }
            }
        }
    }

    public void update() {
        synchronized (messageLines) {
            if (stopped) return;
            // Make sure update requests are completed in order
            waitForUpdate(true);
            messageUpdateFuture = hook.editOriginal(String.join("\n", messageLines)).submit();
        }
    }

    public void stop() {
        synchronized (messageLines) {
            stopped = true;
            waitForUpdate(false);
        }
    }

}
