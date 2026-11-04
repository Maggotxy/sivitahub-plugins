package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public historical projection of a completed community project; not a new reward ledger. */
public record CivilizationProject(UUID communityId, String name, String tag, String periodKey,
                                  BigDecimal goldContributed, int verifiedRecruits, long contributors,
                                  long honorAwarded, Instant completedAt, boolean communityDissolved) {
    public CivilizationProject {
        Objects.requireNonNull(communityId, "communityId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(periodKey, "periodKey");
        Objects.requireNonNull(goldContributed, "goldContributed");
        Objects.requireNonNull(completedAt, "completedAt");
        if (goldContributed.signum() < 0 || verifiedRecruits < 0 || contributors < 0 || honorAwarded < 0) {
            throw new IllegalArgumentException("Historical project totals cannot be negative");
        }
    }
}
