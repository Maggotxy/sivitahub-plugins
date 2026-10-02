package cn.originalrpg.admin;

import java.util.List;

/**
 * 破坏性管理动作的显式确认字面量。
 *
 * <p>「确认」在本管理面上只有一个含义：命令里出现了完整单词 {@code CONFIRM}。
 * 把常量与判定收在一处，是为了避免各子命令各写一遍字符串 —— 那正是
 * 「有的子命令忘了拦、有的拦成大小写敏感」这类误触漏洞的来源。
 * 本类只做字面量判定，不含任何业务权限或状态机逻辑。</p>
 */
final class AdminConfirmation {
    /** 破坏性动作必须附带的确认字面量（大小写不敏感）。 */
    static final String LITERAL = "CONFIRM";

    private AdminConfirmation() {
    }

    /** 单个参数是否就是确认字面量；{@code null} 视为「没确认」。 */
    static boolean isConfirmed(String token) {
        return token != null && token.equalsIgnoreCase(LITERAL);
    }

    /** 参数里是否出现确认字面量；{@code null} 数组或空参数一律视为「没确认」。 */
    static boolean isConfirmed(String[] args) {
        if (args == null) return false;
        for (String arg : args) {
            if (isConfirmed(arg)) return true;
        }
        return false;
    }

    /** Tab 补全用的候选：只有已经打了前缀才对得上。 */
    static List<String> completions(String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        return LITERAL.toLowerCase(java.util.Locale.ROOT).startsWith(normalized)
                ? List.of(LITERAL) : List.of();
    }
}
