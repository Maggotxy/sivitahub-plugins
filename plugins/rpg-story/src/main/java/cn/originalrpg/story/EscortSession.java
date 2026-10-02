package cn.originalrpg.story;

import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

final class EscortSession {
    final UUID playerId;
    final Player player;
    final UUID sessionToken;
    final StoryQuest quest;
    final NPC npc;
    final List<Location> route;
    final BossBar bossBar;
    int waypoint = 1;
    int elapsedTicks;
    int hardDistanceTicks;
    int navigationRetries;
    int messageCooldownTicks;
    boolean pausedForLoad;

    EscortSession(Player player, UUID sessionToken, StoryQuest quest, NPC npc, List<Location> route, BossBar bossBar) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.sessionToken = sessionToken;
        this.quest = quest;
        this.npc = npc;
        this.route = List.copyOf(route);
        this.bossBar = bossBar;
    }

    Location target() {
        return route.get(waypoint);
    }

    float progress() {
        return EscortRules.progress(waypoint, route.size());
    }
}
