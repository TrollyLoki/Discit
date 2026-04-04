package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
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
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.trollyloki.discit.FormattingUtils.formatDuration;
import static net.trollyloki.discit.FormattingUtils.serverDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;

@NullMarked
public final class ServerOptionsInteractions {
    private ServerOptionsInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerOptionsInteractions.class);

    public static final String
            SERVER_OPTIONS_BUTTON_ID = "server-options",
            SET_SERVER_OPTION_COMPONENT_ID = "set-server-option",
            AUTOLOAD_SESSION_NAME_SELECT_ID = "autoload-session-name";

    private static StringSelectMenu autoloadSessionNameSelectMenu(String serverIdString, ServerSessions sessions, ServerGameState gameState) {
        String customId = buildId(AUTOLOAD_SESSION_NAME_SELECT_ID, serverIdString);
        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);

        List<String> sessionNames = new ArrayList<>(sessions.sessions().size() + 1);
        for (Session session : sessions.sessions()) {
            sessionNames.add(session.sessionName());
        }

        String current = gameState.autoLoadSessionName();
        if (!current.isEmpty() && !sessionNames.contains(current)) {
            sessionNames.add(0, current);
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

    private static StringSelectMenu autosaveIntervalSelectMenu(String serverIdString, ServerOptions options) {
        int current = (int) Float.parseFloat(getPendingOrCurrentValue(options, ServerOptions.AUTOSAVE_INTERVAL));

        String customId = buildId(SET_SERVER_OPTION_COMPONENT_ID, serverIdString, ServerOptions.AUTOSAVE_INTERVAL);
        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);

        selectMenu.addOption(formatInterval(0), "0");
        boolean currentAdded = current == 0;
        // Discord only allows a maximum of 25 options, and we need one for "Off" and possibly one for the current value
        int seconds = 5 * 60;
        for (int i = 2; i < StringSelectMenu.OPTIONS_MAX_AMOUNT; i++) {

            // Insert the current value where it should be if necessary
            if (!currentAdded) {
                if (seconds > current) {
                    // Insert the current value before this value
                    selectMenu.addOption(formatInterval(current), Integer.toString(current));
                    currentAdded = true;
                } else {
                    currentAdded = seconds == current;
                }
            }

            selectMenu.addOption(formatInterval(seconds), Integer.toString(seconds));

            // Options in 5-minute intervals up to an hour, then in 15-minute intervals up to 2 hours,
            // then in 30-minute intervals up to 4 hours, then finally in whole hour intervals
            if (seconds < 60 * 60) seconds += 5 * 60;
            else if (seconds < 2 * 60 * 60) seconds += 15 * 60;
            else if (seconds < 4 * 60 * 60) seconds += 30 * 60;
            else seconds += 60 * 60;
        }
        if (!currentAdded) {
            // Insert the current value at the end since it must be bigger than every other option
            selectMenu.addOption(formatInterval(current), Integer.toString(current));
        }
        selectMenu.setDefaultValues(Integer.toString(current));

        return selectMenu.build();
    }

    private static String formatRestartTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static StringSelectMenu restartScheduleSelectMenu(String serverIdString, ServerOptions options) {
        int current = (int) Float.parseFloat(getPendingOrCurrentValue(options, ServerOptions.SERVER_RESTART_SCHEDULE));

        String customId = buildId(SET_SERVER_OPTION_COMPONENT_ID, serverIdString, ServerOptions.SERVER_RESTART_SCHEDULE);
        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);

        boolean currentAdded = false;
        // An option for each hour plus possibly one for the current value fits perfectly into the 25 option limit
        for (int i = 1; i <= 24; i++) {
            int minutes = 60 * i;

            // Insert the current value where it should be if necessary
            if (!currentAdded) {
                if (minutes > current) {
                    // Insert the current value before this value
                    selectMenu.addOption(formatRestartTime(current), Integer.toString(current));
                    currentAdded = true;
                } else {
                    currentAdded = minutes == current;
                }
            }

            selectMenu.addOption(formatRestartTime(minutes), Integer.toString(minutes));
        }
        if (!currentAdded) {
            // Insert the current value at the end since it must be bigger than every other option
            selectMenu.addOption(formatRestartTime(current), Integer.toString(current));
        }
        selectMenu.setDefaultValues(Integer.toString(current));

        return selectMenu.build();
    }

    private static Button booleanButton(String serverIdString, ServerOptions options, String key) {
        String customId = buildId(SET_SERVER_OPTION_COMPONENT_ID, serverIdString, key);
        String optionName = getOptionName(key);

        if ("true".equalsIgnoreCase(getPendingOrCurrentValue(options, key))) {
            return Button.secondary(buildId(customId, "false"), optionName).withEmoji(CHECKBOX_CHECKED_EMOJI);
        } else {
            return Button.secondary(buildId(customId, "true"), optionName).withEmoji(CHECKBOX_EMPTY_EMOJI);
        }
    }

    private record OptionsInfo(ServerOptions options, ServerSessions sessions, ServerGameState gameState) {
        static OptionsInfo get(HttpsApi httpsApi) {
            return new OptionsInfo(httpsApi.getServerOptions(), httpsApi.enumerateSessions(), httpsApi.queryServerState());
        }
    }

    private static Container optionsContainer(String serverIdString, @Nullable String serverName, OptionsInfo optionsInfo) {
        return Container.of(
                TextDisplay.of("# Server Options\n## " + serverDisplayName(serverName)),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### Dedicated Server"),
                TextDisplay.of(AUTOLOAD_SESSION_NAME),
                ActionRow.of(autoloadSessionNameSelectMenu(serverIdString, optionsInfo.sessions, optionsInfo.gameState)),
                ActionRow.of(
                        booleanButton(serverIdString, optionsInfo.options, ServerOptions.AUTO_PAUSE),
                        booleanButton(serverIdString, optionsInfo.options, ServerOptions.AUTO_SAVE_ON_DISCONNECT)
                ),
                TextDisplay.of("### Gameplay"),
                TextDisplay.of(getOptionName(ServerOptions.AUTOSAVE_INTERVAL)),
                ActionRow.of(autosaveIntervalSelectMenu(serverIdString, optionsInfo.options)),
                TextDisplay.of(getOptionName(ServerOptions.SERVER_RESTART_SCHEDULE)),
                ActionRow.of(restartScheduleSelectMenu(serverIdString, optionsInfo.options)),
                ActionRow.of(
                        booleanButton(serverIdString, optionsInfo.options, ServerOptions.DISABLE_SEASONAL_EVENTS),
                        booleanButton(serverIdString, optionsInfo.options, ServerOptions.SEND_GAMEPLAY_DATA)
                ),
                TextDisplay.of(getOptionName(ServerOptions.NETWORK_QUALITY)),
                ActionRow.of(networkQualitySelectMenu(serverIdString, optionsInfo.options))
        );
    }

    public static void onServerOptionsButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.deferReply(true).queue();

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        requestAsync(server, "get server options for", OptionsInfo::get).thenAcceptAsync(optionsInfo -> {

            MDC.setContextMap(mdc);
            event.getHook().editOriginalComponents(optionsContainer(serverIdString, server.getName(), optionsInfo))
                    .useComponentsV2().queue();

        }).exceptionallyAsync(throwable -> {
            event.getHook().editOriginal(throwable.getMessage()).queue();
            return null;
        });
    }

    public static void onSetAutoloadSessionNameSelect(StringSelectInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.deferEdit().queue();

        String autoloadSessionName = event.getValues().get(0);
        LOGGER.info("Setting auto-load session name for server \"{}\" to \"{}\"", server.getName(), autoloadSessionName);

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        requestAsync(server, "set auto-load session name for", httpsApi -> {
            httpsApi.setAutoLoadSessionName(autoloadSessionName);
            return OptionsInfo.get(httpsApi);
        }).thenAcceptAsync(optionsInfo -> {
            logActionWithServer(event, "set " + AUTOLOAD_SESSION_NAME + " to " + autoloadSessionName + " for", server.getName());

            MDC.setContextMap(mdc);
            event.getHook().editOriginalComponents(optionsContainer(serverIdString, server.getName(), optionsInfo))
                    .useComponentsV2().queue();

        }).exceptionallyAsync(throwable -> {
            event.getHook().sendMessage(throwable.getMessage()).setEphemeral(true).queue();
            return null;
        });
    }

    public static void onSetServerOptionButton(ButtonInteractionEvent event, String serverIdString, String key, String value) {
        onSetServerOptionHelper(event, serverIdString, key, value);
    }

    public static void onSetServerOptionSelect(StringSelectInteractionEvent event, String serverIdString, String key) {
        onSetServerOptionHelper(event, serverIdString, key, event.getValues().get(0));
    }

    private static void onSetServerOptionHelper(ComponentInteraction interaction, String serverIdString, String key, String value) {
        Server server = getServerIfAdmin(interaction, serverIdString);
        if (server == null)
            return;

        interaction.deferEdit().queue();

        Map<String, String> options = Map.of(key, value);
        LOGGER.info("Applying server options {} to server \"{}\"", options, server.getName());

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        requestAsync(server, "apply server options to", httpsApi -> {
            httpsApi.applyServerOptions(options);
            return OptionsInfo.get(httpsApi);
        }).thenAcceptAsync(optionsInfo -> {
            String newValue = getPendingOrCurrentValue(optionsInfo.options, key);

            String formattedValue = switch (key) {
                case ServerOptions.NETWORK_QUALITY -> getNetworkQualityName(newValue);
                case ServerOptions.AUTOSAVE_INTERVAL -> formatInterval((int) Float.parseFloat(newValue));
                case ServerOptions.SERVER_RESTART_SCHEDULE -> formatRestartTime((int) Float.parseFloat(newValue));
                default -> newValue;
            };

            logActionWithServer(interaction, "set " + getOptionName(key) + " to " + formattedValue + " for", server.getName());

            MDC.setContextMap(mdc);
            interaction.getHook().editOriginalComponents(optionsContainer(serverIdString, server.getName(), optionsInfo))
                    .useComponentsV2().queue();

        }).exceptionallyAsync(throwable -> {
            interaction.getHook().sendMessage(throwable.getMessage()).setEphemeral(true).queue();
            return null;
        });
    }

}
