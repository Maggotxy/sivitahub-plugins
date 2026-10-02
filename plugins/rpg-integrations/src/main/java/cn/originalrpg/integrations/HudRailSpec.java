package cn.originalrpg.integrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 右侧战斗轨的尺寸契约在插件侧的读取入口。
 *
 * <p>真相源只有一份 —— {@code docs/reference/hud-layout.json}，它同时被资源包门禁
 * （{@code resource-pack/tools/verify_hud_layout.py}）和 {@code build.sh} 读取。
 * 插件侧如果各自写死「4 个槽位」，改尺寸时就会出现「资源包已经 4 格、占位符还在给 3 格」
 * 这类只有玩家看得见的错位，所以这里也读同一份文件。</p>
 *
 * <p>读不到文件时回落到 reviewed 常量而不是抛错：这一步发生在插件启用路径上，
 * 一个缺失的文档文件不应该让整个集成插件起不来。</p>
 */
final class HudRailSpec {
    /** docs/reference/hud-layout.json 里 slot.count 的 reviewed 值。 */
    static final int DEFAULT_SLOT_COUNT = 4;
    private static final Pattern SLOT_SECTION =
            Pattern.compile("\"slot\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern SLOT_COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

    private HudRailSpec() {
    }

    /** 轨道显示的技能槽数量，与资源包的槽位影格数一致。 */
    static int slotCount() {
        return slotCountFromJson(layoutJson());
    }

    static int slotCountFromJson(String json) {
        if (json == null || json.isBlank()) return DEFAULT_SLOT_COUNT;
        Matcher section = SLOT_SECTION.matcher(json);
        if (!section.find()) return DEFAULT_SLOT_COUNT;
        Matcher count = SLOT_COUNT.matcher(section.group(1));
        if (!count.find()) return DEFAULT_SLOT_COUNT;
        int parsed = Integer.parseInt(count.group(1));
        // 槽位数量只可能在 1..9 之间（净空高度与网格限制），越界说明文件被改坏了。
        return parsed >= 1 && parsed <= 9 ? parsed : DEFAULT_SLOT_COUNT;
    }

    private static String layoutJson() {
        try {
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            while (root != null && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
                root = root.getParent();
            }
            if (root == null) return null;
            Path layout = root.resolve("docs/reference/hud-layout.json");
            return Files.isRegularFile(layout) ? Files.readString(layout) : null;
        } catch (RuntimeException | java.io.IOException exception) {
            return null;
        }
    }
}
