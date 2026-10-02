package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-103 行为级证据：治理链路上真正会发给玩家的文案键全部存在。
 *
 * <p>键由 {@link CommunityRolePower#displayKey}、
 * {@link CommunityApprovalOutcome.Status} 与两个治理事件监听器实际使用；
 * 本测试把「库里查得到这条文案」当作行为断言，而不是对源码做字符串断言。</p>
 */
final class CommunityGovernanceMessagesTest {

    @Test
    void everyGovernanceKeyUsedAtRuntimeResolvesInBothBundles() throws Exception {
        for (String locale : List.of("zh_cn", "en_us")) {
            Map<String, Object> community = communitySection(locale);
            assertNotNull(community, locale + " 缺少 community 段");

            // 七角色的展示键：displayKey 返回的是完整的 community.* 键，这里剥掉前缀再查。
            for (CommunityRole role : CommunityRole.values()) {
                assertText(community, stripCommunityPrefix(CommunityRolePower.displayKey(role)), locale);
            }
            // 中英双语都覆盖七个角色名（含新增的 TREASURER/BUILDER/EVENT_HOST/RECRUITER）。
            for (String suffix : List.of("leader", "deputy", "treasurer", "builder", "event-host", "recruiter")) {
                assertText(community, "roles." + suffix, locale);
            }

            // 审批状态机每一条状态都有对应文案，玩家不会看到裸键。
            for (CommunityApprovalOutcome.Status status : CommunityApprovalOutcome.Status.values()) {
                String key = "governance." + status.name().toLowerCase(Locale.ROOT).replace('_', '-');
                assertText(community, key, locale);
            }

            // 命令与事件监听器引用的固定集合。
            for (String key : List.of("help", "governance.disabled", "governance.role-unknown", "governance.action-unknown",
                    "governance.assign-self", "governance.role-approver", "governance.role-domain",
                    "governance.role-member", "governance.moderator", "governance.non-moderator",
                    "governance.pending", "governance.pending-hint", "governance.approval-entry",
                    "governance.effective", "governance.submitted", "governance.role-rejected")) {
                assertText(community, key, locale);
            }
            // 命令层真实渲染出来的键：直接问 handler，手写清单漏项就会被这里抓住。
            for (String key : renderedKeys()) {
                assertText(community, key.substring("community.".length()), locale);
            }
        }
    }

    @Test
    void approvalStatusesMapToDistinctKeysSoPlayersCanTellThemApart() {
        // 同一人不能凑两次批准 / 本人不能自批，是两个不同的可观测状态，不能共用一个文案键。
        String already = statusKey(CommunityApprovalOutcome.Status.ALREADY_APPROVED);
        String self = statusKey(CommunityApprovalOutcome.Status.SELF_APPROVAL);
        String notAllowed = statusKey(CommunityApprovalOutcome.Status.NOT_ALLOWED);
        assertTrue(!already.equals(self) && !already.equals(notAllowed) && !self.equals(notAllowed),
                "审批状态文案键必须互不相同");
        // 领域动作前缀稳定，便于运营在文案里直接看到动作键。
        assertEquals("community.project", CommunityGovernanceAction.COMMUNITY_PROJECT.key());
        assertEquals("community.event", CommunityGovernanceAction.COMMUNITY_EVENT.key());
        assertEquals("community.role-grant", CommunityGovernanceAction.ROLE_GRANT.key());
    }

    /**
     * 命令层在「未启用」与「角色展示」两条路径上真实渲染的文案键。
     *
     * <p>开关关闭时唯一的输出就是 disabled；角色展示是只读路径，未启用也可用。</p>
     */
    private static List<String> renderedKeys() {
        // 返回的键都是 community.* 形式；下面去掉前缀后查 community 段。
        CommunityGovernanceHandler handler = new CommunityGovernanceHandler(null, null, null, false);
        List<String> keys = new java.util.ArrayList<>(handler
                .approve(null, CommunityGovernanceAction.COMMUNITY_PROJECT)
                .toCompletableFuture().join().keys());
        keys.addAll(handler.deny(null, CommunityGovernanceAction.COMMUNITY_PROJECT)
                .toCompletableFuture().join().keys());
        keys.addAll(handler.assign(null, null, "key").toCompletableFuture().join().keys());
        keys.addAll(handler.approve(null, null).toCompletableFuture().join().keys());
        keys.addAll(handler.roleLookup(null).keys());
        keys.addAll(handler.roleLookup("not-a-role").keys());
        assertTrue(keys.contains("community.governance.disabled"), "未启用时必须渲染 disabled");
        assertTrue(keys.contains("community.governance.role-line"), "角色展示必须渲染 role-line");
        return keys;
    }

    /** displayKey 返回 community.* 形式的完整键；本测试的根已经是 community 段。 */
    private static String stripCommunityPrefix(String key) {
        return key.startsWith("community.") ? key.substring("community.".length()) : key;
    }

    private static String statusKey(CommunityApprovalOutcome.Status status) {
        return "governance." + status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void assertText(Map<String, Object> community, String path, String locale) {
        Object value = resolve(community, path);
        // 单行文案是 String，帮助这类多行文案是 List<String>；两者都不能为空。
        boolean present = value instanceof String text
                ? !text.isBlank()
                : value instanceof List<?> lines && !lines.isEmpty()
                && lines.stream().allMatch(line -> line instanceof String text && !text.isBlank());
        assertTrue(present, locale + " 文案缺失: community." + path);
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Map<String, Object> root, String dottedPath) {
        Object current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> communitySection(String locale) throws Exception {
        String resource = "lang/" + locale + ".yml";
        // 与运行时 YamlMessageService 读同一份资源，保证断言的就是玩家看到的文案。
        try (InputStream stream = CommunityGovernanceMessagesTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "缺少语言资源 " + resource);
            Map<String, Object> root = new Yaml().load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Object community = root.get("community");
            assertTrue(community instanceof Map, resource + " 缺少 community 段");
            return new LinkedHashMap<>((Map<String, Object>) community);
        }
    }
}
