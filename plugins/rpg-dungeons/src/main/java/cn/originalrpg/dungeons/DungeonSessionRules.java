package cn.originalrpg.dungeons;

import java.util.UUID;

final class DungeonSessionRules {
    private DungeonSessionRules() {
    }

    static boolean departedRegisteredInstance(String registeredWorld, String previousWorld, String currentWorld) {
        return registeredWorld != null
                && registeredWorld.equals(previousWorld)
                && !registeredWorld.equals(currentWorld);
    }

    static boolean isCurrentSession(UUID registeredSession, UUID scheduledSession) {
        return registeredSession != null && registeredSession.equals(scheduledSession);
    }
}
