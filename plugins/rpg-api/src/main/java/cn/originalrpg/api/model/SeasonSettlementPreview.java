package cn.originalrpg.api.model;

import java.util.Objects;

/**
 * 换季的资金影响预览（C-010 的 PREVIEWED 一态）。
 * {@code digest} 是全部事实的稳定指纹：审批与结算必须复算出同一个指纹，
 * 否则说明预览之后资金又变了，结算必须先失败而不是照旧执行。
 */
public record SeasonSettlementPreview(
        String fromSeasonId,
        String toSeasonId,
        long players,
        long settledPlayers,
        long retainedMerit,
        long seasonMerit,
        long honorTotal,
        String digest
) {
    public SeasonSettlementPreview {
        fromSeasonId = requireId(fromSeasonId, "fromSeasonId");
        toSeasonId = requireId(toSeasonId, "toSeasonId");
        if (players < 0 || settledPlayers < 0 || retainedMerit < 0 || seasonMerit < 0 || honorTotal < 0) {
            throw new IllegalArgumentException("Season settlement preview counters must not be negative");
        }
        digest = Objects.requireNonNull(digest, "digest");
    }

    private static String requireId(String value, String name) {
        String stripped = Objects.requireNonNull(value, name).strip();
        if (stripped.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return stripped;
    }
}
