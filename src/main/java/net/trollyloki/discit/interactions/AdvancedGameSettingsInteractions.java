package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.server.https.AdvancedGameSettings;
import net.trollyloki.jicsit.server.https.HttpsApi;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static net.trollyloki.discit.FormattingUtils.serverDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;

@NullMarked
public final class AdvancedGameSettingsInteractions {
    private AdvancedGameSettingsInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedGameSettingsInteractions.class);

    public static final String
            AGS_BUTTON_ID = "ags",
            AGS_ENABLE_BUTTON_ID = "ags-enable",
            AGS_VALUE_SELECT_ID = "ags-value";

    private static final Emoji WARNING_EMOJI = Emoji.fromUnicode("⚠️");

    private static String getSettingName(String key) {
        return switch (key) {
            case AdvancedGameSettings.NO_POWER -> "No Power";
            case AdvancedGameSettings.NO_FUEL -> "No Fuel";
            case AdvancedGameSettings.NO_UNLOCK_COST -> "No Unlock Cost";
            case AdvancedGameSettings.UNLOCK_ALTERNATE_RECIPES_INSTANTLY -> "Unlock Alternate Recipes Instantly";
            case AdvancedGameSettings.DISABLE_ARACHNID_CREATURES -> "Disable Arachnid Creatures";
            case AdvancedGameSettings.NO_BUILD_COST -> "No Build Cost";
            case AdvancedGameSettings.GOD_MODE -> "God Mode";
            case AdvancedGameSettings.FLIGHT_MODE -> "Flight Mode";
            case AdvancedGameSettings.SET_GAME_PHASE -> "Set Game Phase";
            case AdvancedGameSettings.UNLOCK_ALL_TIERS -> "Unlock All Tiers";
            case AdvancedGameSettings.UNLOCK_ALL_RESEARCH -> "Unlock All Research in the MAM";
            case AdvancedGameSettings.UNLOCK_ALL_IN_AWESOME_SHOP -> "Unlock Everything in the AWESOME Shop";
            default -> "Unknown";
        };
    }

    private static String getPhaseName(int phase) {
        return switch (phase) {
            case 0 -> "Onboarding";
            case 1 -> "Distribution Platform";
            case 2 -> "Construction Dock";
            case 3 -> "Main Body";
            case 4 -> "Propulsion Systems";
            case 5 -> "Assembly";
            case 6 -> "Launch";
            case 7 -> "Completed";
            default -> "Unknown";
        };
    }

    private static StringSelectMenu phaseSelectMenu(String serverIdString, AdvancedGameSettings ags) {
        int current = Integer.parseInt(ags.settings().get(AdvancedGameSettings.SET_GAME_PHASE));

        String customId = buildId(AGS_VALUE_SELECT_ID, serverIdString, AdvancedGameSettings.SET_GAME_PHASE);
        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);
        for (int i = current; i <= 7; i++) {
            selectMenu.addOption(getPhaseName(i), Integer.toString(i));
        }
        selectMenu.setDefaultValues(Integer.toString(current));

        return selectMenu.build();
    }

    private static Button booleanButton(String serverIdString, AdvancedGameSettings ags, String key) {
        String customId = buildId(AGS_ENABLE_BUTTON_ID, serverIdString, key);
        String settingName = getSettingName(key);

        if ("true".equalsIgnoreCase(ags.settings().get(key))) {
            return Button.secondary(customId, settingName).withEmoji(CHECKBOX_CHECKED_EMOJI).asDisabled();
        } else {
            return Button.secondary(customId, settingName).withEmoji(CHECKBOX_EMPTY_EMOJI);
        }
    }

    private static Container settingsContainer(String serverIdString, @Nullable String serverName, AdvancedGameSettings ags) {
        String header = "# Advanced Game Settings\n## " + serverDisplayName(serverName);
        if (!ags.enabled()) {
            header += "\n" + WARNING_EMOJI.getFormatted() + " Advanced Game Settings are not currently enabled. Interacting with any of the below controls will automatically enable them for the current session.";
        }
        return Container.of(
                TextDisplay.of(header),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### Gameplay"),
                ActionRow.of(
                        booleanButton(serverIdString, ags, AdvancedGameSettings.NO_POWER),
                        booleanButton(serverIdString, ags, AdvancedGameSettings.NO_FUEL),
                        booleanButton(serverIdString, ags, AdvancedGameSettings.NO_UNLOCK_COST),
                        booleanButton(serverIdString, ags, AdvancedGameSettings.UNLOCK_ALTERNATE_RECIPES_INSTANTLY)
                ),
                TextDisplay.of("### Player Defaults"),
                ActionRow.of(
                        booleanButton(serverIdString, ags, AdvancedGameSettings.NO_BUILD_COST),
                        booleanButton(serverIdString, ags, AdvancedGameSettings.GOD_MODE),
                        booleanButton(serverIdString, ags, AdvancedGameSettings.FLIGHT_MODE)
                ),
                TextDisplay.of("### Creatures"),
                ActionRow.of(
                        booleanButton(serverIdString, ags, AdvancedGameSettings.DISABLE_ARACHNID_CREATURES)
                ),
                TextDisplay.of("### Progression\n" + WARNING_EMOJI.getFormatted() + " These settings are **irreversible** unless a previous save is loaded."),
                ActionRow.of(phaseSelectMenu(serverIdString, ags)),
                ActionRow.of(
                        booleanButton(serverIdString, ags, AdvancedGameSettings.UNLOCK_ALL_TIERS),
                        booleanButton(serverIdString, ags, AdvancedGameSettings.UNLOCK_ALL_RESEARCH),
                        booleanButton(serverIdString, ags, AdvancedGameSettings.UNLOCK_ALL_IN_AWESOME_SHOP)
                )
        );
    }

    public static void onAdvancedGameSettingsButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.deferReply(true).queue();

        requestAsync(server, "get Advanced Game Settings on", HttpsApi::getAdvancedGameSettings).thenAcceptAsync(ags -> {

            event.getHook().editOriginalComponents(settingsContainer(serverIdString, server.getName(), ags))
                    .useComponentsV2().queue();

        }).exceptionallyAsync(throwable -> {
            event.getHook().editOriginal(throwable.getMessage()).queue();
            return null;
        });
    }

    public static void onAdvancedGameSettingEnableButton(ButtonInteractionEvent event, String serverIdString, String key) {
        onAdvancedGameSettingHelper(event, serverIdString, key, "true");
    }

    public static void onAdvancedGameSettingValueSelect(StringSelectInteractionEvent event, String serverIdString, String key) {
        onAdvancedGameSettingHelper(event, serverIdString, key, event.getValues().get(0));
    }

    private static void onAdvancedGameSettingHelper(ComponentInteraction interaction, String serverIdString, String key, String value) {
        Server server = getServerIfAdmin(interaction, serverIdString);
        if (server == null)
            return;

        interaction.deferEdit().queue();

        Map<String, String> settings = Map.of(key, value);
        LOGGER.info("Applying Advanced Game Settings {} on server \"{}\"", settings, server.getName());

        requestAsync(server, "apply Advanced Game Settings on", httpsApi -> {
            httpsApi.applyAdvancedGameSettings(settings);
            return httpsApi.getAdvancedGameSettings();
        }).thenAcceptAsync(ags -> {
            String newValue = ags.settings().get(key);

            String action;
            if (key.equals(AdvancedGameSettings.SET_GAME_PHASE)) {
                action = "set the game phase to " + getPhaseName(Integer.parseInt(newValue));
            } else {
                action = (newValue.equalsIgnoreCase("true") ? "enabled " : "disabled ") + getSettingName(key);
            }
            logActionWithServer(interaction, action + " (Advanced Game Setting) on", server.getName());

            interaction.getHook().editOriginalComponents(settingsContainer(serverIdString, server.getName(), ags))
                    .useComponentsV2().queue();

        }).exceptionallyAsync(throwable -> {
            interaction.getHook().sendMessage(throwable.getMessage()).setEphemeral(true).queue();
            return null;
        });
    }

}
