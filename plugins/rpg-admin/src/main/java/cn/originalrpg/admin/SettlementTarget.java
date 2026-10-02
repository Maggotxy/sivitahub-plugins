package cn.originalrpg.admin;

import cn.originalrpg.api.rules.SeasonSettlementState;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 管理面入参 {@code <target>} 到九态的解析。
 *
 * <p>这里**只做名字解析**：把操作者打的那一眼可读的名字（如 {@code verifying}）
 * 翻成 {@link SeasonSettlementState}。合法边由
 * {@link SeasonSettlementState#canTransitionTo} 与 {@code SeasonSettlementService}
 * 判定 —— 本类刻意不复制那张边表，避免出现第二套状态机。</p>
 */
final class SettlementTarget {
    private SettlementTarget() {
    }

    /** 解析目标态；名字未知时返回空，由调用方提示可用值，而不是抛异常。 */
    static Optional<SeasonSettlementState> parse(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        if (token.strip().equalsIgnoreCase("advance")) return Optional.empty();
        String normalized = token.strip().toUpperCase(java.util.Locale.ROOT);
        return Arrays.stream(SeasonSettlementState.values())
                .filter(state -> state.name().equals(normalized))
                .findFirst();
    }

    /** 补全候选：全部九个状态名，便于操作者看到可选项。 */
    static List<String> names() {
        return Arrays.stream(SeasonSettlementState.values())
                .map(state -> state.name().toLowerCase(java.util.Locale.ROOT))
                .toList();
    }
}
