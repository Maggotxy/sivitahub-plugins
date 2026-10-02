package cn.originalrpg.pets;

import java.util.Set;

final class PetPolicy {
    private final Set<String> dungeonPrefixes;
    private final Set<String> blockedWorlds;
    private final Set<String> captureWorlds;

    PetPolicy(Set<String> dungeonPrefixes, Set<String> blockedWorlds, Set<String> captureWorlds) {
        this.dungeonPrefixes = Set.copyOf(dungeonPrefixes);
        this.blockedWorlds = Set.copyOf(blockedWorlds);
        this.captureWorlds = Set.copyOf(captureWorlds);
    }

    boolean inDungeon(String worldName) {
        return worldName != null && dungeonPrefixes.stream().anyMatch(worldName::startsWith);
    }

    boolean petsBlocked(String worldName) {
        return worldName == null || blockedWorlds.contains(worldName) || inDungeon(worldName);
    }

    boolean canCapture(String worldName) {
        return worldName != null && captureWorlds.contains(worldName) && !petsBlocked(worldName);
    }

    Denial summonDenial(boolean inCombat, String worldName) {
        if (inDungeon(worldName)) return Denial.DUNGEON;
        if (blockedWorlds.contains(worldName)) return Denial.WORLD;
        if (inCombat) return Denial.COMBAT;
        return Denial.NONE;
    }

    enum Denial { NONE, COMBAT, DUNGEON, WORLD }
}
