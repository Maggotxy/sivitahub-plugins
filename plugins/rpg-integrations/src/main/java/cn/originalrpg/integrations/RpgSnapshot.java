package cn.originalrpg.integrations;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.WealthBalance;

import java.time.Instant;
import java.util.Locale;

record RpgSnapshot(
        String classId,
        String branchId,
        int ascension,
        int level,
        int energy,
        int energyCap,
        String gold,
        long merit,
        long starlight,
        Instant refreshedAt
) {
    static RpgSnapshot from(
            PlayerProfile profile, WealthBalance wealth, java.util.Map<Integer, String> branches, Instant refreshedAt
    ) {
        String currentBranch = branches.entrySet().stream().max(java.util.Map.Entry.comparingByKey())
                .map(java.util.Map.Entry::getValue).orElse("none");
        return new RpgSnapshot(
                profile.archetype().name().toLowerCase(Locale.ROOT),
                currentBranch,
                profile.ascension(),
                profile.level(),
                profile.energy(),
                profile.energyCap(),
                wealth.gold().toPlainString(),
                wealth.merit(),
                wealth.starlight(),
                refreshedAt
        );
    }
}
