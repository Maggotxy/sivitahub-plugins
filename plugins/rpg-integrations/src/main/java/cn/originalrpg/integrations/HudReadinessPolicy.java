package cn.originalrpg.integrations;

import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.time.Duration;
import java.time.Instant;

/** Gates bitmap HUDs on confirmed client resources and available player data. */
final class HudReadinessPolicy {
    private HudReadinessPolicy() {
    }

    static boolean ready(
            boolean online, PlayerResourcePackStatusEvent.Status packStatus,
            Instant profileRefreshedAt, boolean skillsReady, Duration staleAfter, Instant now
    ) {
        // Match the guide's ResourcePackUiPolicy: accepting or downloading the
        // pack is insufficient, and a failed reload hides custom visuals again.
        return online && packStatus == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED
                && profileRefreshedAt != null && skillsReady
                && Duration.between(profileRefreshedAt, now).compareTo(staleAfter) <= 0;
    }
}
