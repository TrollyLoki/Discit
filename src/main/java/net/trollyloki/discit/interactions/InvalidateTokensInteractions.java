package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.trollyloki.discit.InteractionUtils;
import net.trollyloki.discit.Server;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static net.trollyloki.discit.InteractionUtils.getGuildManager;
import static net.trollyloki.discit.InteractionUtils.getServerIfAdmin;
import static net.trollyloki.discit.InteractionUtils.logActionWithServer;
import static net.trollyloki.discit.InteractionUtils.requestAsyncWithMDC;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class InvalidateTokensInteractions {
    private InvalidateTokensInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(InvalidateTokensInteractions.class);

    public static final String
            INVALIDATE_TOKENS_BUTTON_ID = "invalidate-tokens";

    public static void onInvalidateTokensButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.deferReply(true).queue();

        LOGGER.info("Invalidating all API tokens for {}", serverNameForLog(server.getName()));

        requestAsyncWithMDC(server, "invalidate all API tokens for",
                InteractionUtils::invalidateTokens
        ).thenApplyAsync(withMDC(result -> {
            if (result.success()) {
                logActionWithServer(event, "invalidated all API tokens for", server.getName());
                getGuildManager(event).setServerToken(UUID.fromString(serverIdString), null);
            }
            return result.output();
        })).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
            event.getHook().editOriginal(message).queue();
        }));
    }

}
