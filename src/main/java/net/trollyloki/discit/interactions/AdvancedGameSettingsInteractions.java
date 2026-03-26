package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.trollyloki.discit.Server;
import org.jspecify.annotations.NullMarked;

import static net.trollyloki.discit.InteractionUtils.getServerIfAdmin;

@NullMarked
public final class AdvancedGameSettingsInteractions {
    private AdvancedGameSettingsInteractions() {
    }

    public static final String
            AGS_BUTTON_ID = "ags",
            AGS_MODAL_ID = "ags";

    public static void onAdvancedGameSettingsButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        //TODO: Need checkbox support
        event.reply("Not yet implemented").setEphemeral(true).queue();
    }

}
