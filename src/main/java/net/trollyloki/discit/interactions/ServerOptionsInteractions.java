package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.trollyloki.discit.InteractionUtils;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.save.Session;
import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.https.ServerOptions;
import net.trollyloki.jicsit.server.https.ServerSessions;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.trollyloki.discit.FormattingUtils.*;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class ServerOptionsInteractions {
    private ServerOptionsInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerOptionsInteractions.class);

    public static final String
            SERVER_OPTIONS_BUTTON_ID = "server-options",
            SET_SERVER_OPTION_COMPONENT_ID = "set-server-option",
            AUTOLOAD_SESSION_NAME_SELECT_ID = "autoload-session-name",
            OPTIONS_RELOAD_BUTTON_ID = "options-reload";

    private static final Emoji RELOAD_EMOJI = Emoji.fromUnicode("🔄");

    private static StringSelectMenu autoloadSessionNameSelectMenu(String serverIdString, ServerSessions sessions, ServerGameState gameState) {
        String customId = buildId(AUTOLOAD_SESSION_NAME_SELECT_ID, serverIdString);
        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);

        List<String> sessionNames = new ArrayList<>(sessions.sessions().size() + 1);
        for (Session session : sessions.sessions()) {
            sessionNames.add(session.sessionName());
        }

        String current = gameState.autoLoadSessionName();
        if (!current.isEmpty() && !sessionNames.contains(current)) {
            sessionNames.addFirst(current);
        }

        if (sessionNames.isEmpty()) {
            selectMenu.addOption("null", "null").setDisabled(true);
            selectMenu.setPlaceholder("Server has no sessions");
        } else {
            int count = 0;
            for (String sessionName : sessionNames) {
                if (count == StringSelectMenu.OPTIONS_MAX_AMOUNT) {
                    LOGGER.warn("Truncated session name select options from {} to {}", sessionNames.size(), count);
                    break;
                }
                selectMenu.addOption(sessionName, sessionName);
                count++;
            }
            selectMenu.setPlaceholder("Select a session").setDefaultValues(current);
        }

        return selectMenu.build();
    }

    private static final String AUTOLOAD_SESSION_NAME = "Auto-Load Session Name";

    private static String getOptionName(String key) {
        return switch (key) {
            case ServerOptions.AUTO_PAUSE -> "Auto Pause";
            case ServerOptions.AUTO_SAVE_ON_DISCONNECT -> "Auto-Save on Player Disconnect";
            case ServerOptions.DISABLE_SEASONAL_EVENTS -> "Disable Seasonal Events";
            case ServerOptions.AUTOSAVE_INTERVAL -> "Autosave Interval";
            case ServerOptions.SERVER_RESTART_SCHEDULE -> "Server Restart Schedule";
            case ServerOptions.SEND_GAMEPLAY_DATA -> "Send Gameplay Data";
            case ServerOptions.NETWORK_QUALITY -> "Network Quality";
            default -> "Unknown";
        };
    }

    private static String getNetworkQualityName(String value) {
        return switch (value) {
            case "0" -> "Low";
            case "1" -> "Medium";
            case "2" -> "High";
            case "3" -> "Ultra";
            default -> "Unknown";
        };
    }

    private static String getPendingOrCurrentValue(ServerOptions options, String key) {
        String current = options.pending().get(key);
        if (current == null) {
            current = options.current().get(key);
        }
        return current;
    }

    private static StringSelectMenu networkQualitySelectMenu(String serverIdString, ServerOptions options) {
        String current = getPendingOrCurrentValue(options, ServerOptions.NETWORK_QUALITY);

        String customId = buildId(SET_SERVER_OPTION_COMPONENT_ID, serverIdString, ServerOptions.NETWORK_QUALITY);
        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);
        for (int i = 0; i <= 3; i++) {
            String value = Integer.toString(i);
            selectMenu.addOption(getNetworkQualityName(value), value);
        }
        selectMenu.setDefaultValues(current);

        return selectMenu.build();
    }

    private static String formatInterval(int totalSeconds) {
        if (totalSeconds <= 0) return "Off";
        else return formatDuration(totalSeconds);
    }

    private static final int[] AUTOSAVE_INTERVAL_OPTIONS;

    static {
        ArrayList<Integer> options = new ArrayList<>(StringSelectMenu.OPTIONS_MAX_AMOUNT - 1);
        int seconds = 0;

        // 5-minute intervals up to an hour
        for (; seconds < 60 * 60; seconds += 5 * 60) options.add(seconds);

        // 15-minute intervals up to 2 hours
        for (; seconds < 2 * 60 * 60; seconds += 15 * 60) options.add(seconds);

        // 30-minute intervals up to 4 hours
        for (; seconds < 4 * 60 * 60; seconds += 30 * 60) options.add(seconds);
        options.add(seconds);

        // finally whole hour intervals
        options.add(5 * 60 * 60);
        options.add(6 * 60 * 60);
        options.add(8 * 60 * 60);

        AUTOSAVE_INTERVAL_OPTIONS = new int[options.size()];
        for (int i = 0; i < options.size(); i++) {
            AUTOSAVE_INTERVAL_OPTIONS[i] = options.get(i);
        }
    }

    private static StringSelectMenu autosaveIntervalSelectMenu(String serverIdString, ServerOptions options) {
        int current = (int) Float.parseFloat(getPendingOrCurrentValue(options, ServerOptions.AUTOSAVE_INTERVAL));

        String customId = buildId(SET_SERVER_OPTION_COMPONENT_ID, serverIdString, ServerOptions.AUTOSAVE_INTERVAL);
        return createIntSelectMenu(customId, ServerOptionsInteractions::formatInterval, current, AUTOSAVE_INTERVAL_OPTIONS);
    }

    private static String formatRestartTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static final int[] RESTART_SCHEDULE_OPTIONS;

    static {
        // An option for each hour (01:00 to 24:00)
        RESTART_SCHEDULE_OPTIONS = new int[24];
        for (int i = 0; i < RESTART_SCHEDULE_OPTIONS.length; i++) {
            RESTART_SCHEDULE_OPTIONS[i] = (i + 1) * 60;
        }
    }

    private static StringSelectMenu restartScheduleSelectMenu(String serverIdString, ServerOptions options) {
        int current = (int) Float.parseFloat(getPendingOrCurrentValue(options, ServerOptions.SERVER_RESTART_SCHEDULE));

        String customId = buildId(SET_SERVER_OPTION_COMPONENT_ID, serverIdString, ServerOptions.SERVER_RESTART_SCHEDULE);
        return createIntSelectMenu(customId, ServerOptionsInteractions::formatRestartTime, current, RESTART_SCHEDULE_OPTIONS);
    }

    private static Button booleanButton(String serverIdString, ServerOptions options, String key) {
        String value;
        Emoji emoji;
        if ("true".equalsIgnoreCase(getPendingOrCurrentValue(options, key))) {
            value = "false";
            emoji = CHECKBOX_CHECKED_EMOJI;
        } else {
            value = "true";
            emoji = CHECKBOX_EMPTY_EMOJI;
        }

        if (options.pending().containsKey(key)) {
            emoji = RELOAD_EMOJI;
        }

        return Button.secondary(buildId(SET_SERVER_OPTION_COMPONENT_ID, serverIdString, key, value), getOptionName(key))
                .withEmoji(emoji);
    }

    private record OptionsInfo(ServerOptions options, ServerSessions sessions, ServerGameState gameState) {
        static OptionsInfo get(HttpsApi httpsApi) {
            return new OptionsInfo(httpsApi.getServerOptions(), httpsApi.enumerateSessions(), httpsApi.queryServerState());
        }
    }

    private static Container optionsContainer(String serverIdString, @Nullable String serverName, OptionsInfo optionsInfo) {
        List<ContainerChildComponent> components = new ArrayList<>(17);

        components.add(TextDisplay.of("# Server Options\n## " + escapedServerName(serverName)));
        components.add(Separator.createDivider(Separator.Spacing.SMALL));

        components.add(TextDisplay.of("### Dedicated Server"));
        components.add(TextDisplay.of(AUTOLOAD_SESSION_NAME));
        components.add(ActionRow.of(autoloadSessionNameSelectMenu(serverIdString, optionsInfo.sessions, optionsInfo.gameState)));
        components.add(ActionRow.of(
                booleanButton(serverIdString, optionsInfo.options, ServerOptions.AUTO_PAUSE),
                booleanButton(serverIdString, optionsInfo.options, ServerOptions.AUTO_SAVE_ON_DISCONNECT)
        ));

        components.add(TextDisplay.of("### Gameplay"));
        components.add(TextDisplay.of(getOptionName(ServerOptions.AUTOSAVE_INTERVAL)));
        components.add(ActionRow.of(autosaveIntervalSelectMenu(serverIdString, optionsInfo.options)));
        components.add(TextDisplay.of(getOptionName(ServerOptions.SERVER_RESTART_SCHEDULE)));
        components.add(ActionRow.of(restartScheduleSelectMenu(serverIdString, optionsInfo.options)));
        components.add(ActionRow.of(
                booleanButton(serverIdString, optionsInfo.options, ServerOptions.DISABLE_SEASONAL_EVENTS),
                booleanButton(serverIdString, optionsInfo.options, ServerOptions.SEND_GAMEPLAY_DATA)
        ));
        components.add(TextDisplay.of((optionsInfo.options.pending().containsKey(ServerOptions.NETWORK_QUALITY)
                ? RELOAD_EMOJI.getFormatted() + " " : "") + getOptionName(ServerOptions.NETWORK_QUALITY)));
        components.add(ActionRow.of(networkQualitySelectMenu(serverIdString, optionsInfo.options)));

        if (!optionsInfo.options.pending().isEmpty()) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of(RELOAD_EMOJI.getFormatted() + " Some changes require a reload or restart to be applied"));
            components.add(ActionRow.of(Button.primary(buildId(OPTIONS_RELOAD_BUTTON_ID, serverIdString), "Reload Session")));
        }

        return Container.of(components);
    }

    public static void onServerOptionsButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.deferReply(true).queue();

        requestAsyncWithMDC(server, "get server options for", OptionsInfo::get).thenAcceptAsync(withMDC(optionsInfo -> {

            event.getHook().editOriginalComponents(optionsContainer(serverIdString, server.getName(), optionsInfo))
                    .useComponentsV2().queue();

        })).exceptionallyAsync(withMDC(throwable -> {
            event.getHook().editOriginal(InteractionUtils.exceptionMessage(throwable)).queue();
            return null;
        }));
    }

    public static void onSetAutoloadSessionNameSelect(StringSelectInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.deferEdit().queue();

        String autoloadSessionName = event.getValues().getFirst();
        LOGGER.info("Setting auto-load session name for {} to \"{}\"", serverNameForLog(server.getName()), autoloadSessionName);

        requestAsyncWithMDC(server, "set auto-load session name for", httpsApi -> {
            httpsApi.setAutoLoadSessionName(autoloadSessionName);
            return OptionsInfo.get(httpsApi);
        }).thenAcceptAsync(withMDC(optionsInfo -> {
            logActionWithServer(event, "set " + AUTOLOAD_SESSION_NAME + " to " + escapeAll(autoloadSessionName) + " for", server.getName());

            event.getHook().editOriginalComponents(optionsContainer(serverIdString, server.getName(), optionsInfo))
                    .useComponentsV2().queue();

        })).exceptionallyAsync(withMDC(throwable -> {
            event.getHook().sendMessage(InteractionUtils.exceptionMessage(throwable)).setEphemeral(true).queue();
            return null;
        }));
    }

    public static void onSetServerOptionButton(ButtonInteractionEvent event, String serverIdString, String key, String value) {
        onSetServerOptionHelper(event, serverIdString, key, value);
    }

    public static void onSetServerOptionSelect(StringSelectInteractionEvent event, String serverIdString, String key) {
        onSetServerOptionHelper(event, serverIdString, key, event.getValues().getFirst());
    }

    private static void onSetServerOptionHelper(ComponentInteraction interaction, String serverIdString, String key, String value) {
        Server server = getServerIfAdmin(interaction, serverIdString);
        if (server == null)
            return;

        interaction.deferEdit().queue();

        Map<String, String> options = Map.of(key, value);
        LOGGER.info("Applying server options {} to {}", options, serverNameForLog(server.getName()));

        requestAsyncWithMDC(server, "apply server options to", httpsApi -> {
            httpsApi.applyServerOptions(options);
            return OptionsInfo.get(httpsApi);
        }).thenAcceptAsync(withMDC(optionsInfo -> {
            String newValue = getPendingOrCurrentValue(optionsInfo.options, key);

            String formattedValue = switch (key) {
                case ServerOptions.NETWORK_QUALITY -> getNetworkQualityName(newValue);
                case ServerOptions.AUTOSAVE_INTERVAL -> formatInterval((int) Float.parseFloat(newValue));
                case ServerOptions.SERVER_RESTART_SCHEDULE -> formatRestartTime((int) Float.parseFloat(newValue));
                default -> newValue;
            };

            logActionWithServer(interaction, "set " + getOptionName(key) + " to " + formattedValue + " for", server.getName());

            interaction.getHook().editOriginalComponents(optionsContainer(serverIdString, server.getName(), optionsInfo))
                    .useComponentsV2().queue();

        })).exceptionallyAsync(withMDC(throwable -> {
            interaction.getHook().sendMessage(InteractionUtils.exceptionMessage(throwable)).setEphemeral(true).queue();
            return null;
        }));
    }

    public static void onReloadToApplyServerOptionsButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.editComponents(TextDisplay.of("Reloading " + inlineServerDisplayName(server.getName()) + "..."))
                .useComponentsV2().queue();

        LOGGER.info("Reloading {} to apply server options", serverNameForLog(server.getName()));

        reloadHelper(event, server).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
            event.getHook().editOriginalComponents(TextDisplay.of(message))
                    .useComponentsV2().queue();
        }));
    }

}
