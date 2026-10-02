package cn.originalrpg.integrations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 插件侧必须与 {@code docs/reference/hud-layout.json} 这份唯一来源一致。
 *
 * <p>本测试断言的是「实现与唯一来源一致」这件事本身，不是某一年的数字：
 * 文件里写几格，实现就必须给几格。改资源包槽位却忘了改插件时，这里会红。</p>
 */
class HudRailSpecTest {
    private static final Path LAYOUT = repositoryRoot().resolve("docs/reference/hud-layout.json");

    @Test
    void slotCountMatchesTheSingleSourceOfTruth() throws IOException {
        String json = Files.readString(LAYOUT, StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("\"slot\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(json);
        assertEquals(true, block.find(), "hud-layout.json 缺少 slot 段");
        Matcher count = Pattern.compile("\"count\"\\s*:\\s*(\\d+)").matcher(block.group(1));
        assertEquals(true, count.find(), "hud-layout.json 的 slot 段缺少 count");
        assertEquals(Integer.parseInt(count.group(1)), HudRailSpec.slotCountFromJson(json),
                "插件侧槽位数与唯一来源不一致");
        assertEquals(Integer.parseInt(count.group(1)), HudRailSpec.slotCount(),
                "从磁盘读到的工作目录解析失败时槽位数会回落，说明仓库根定位有问题");
    }

    @Test
    void brokenOrMissingJsonFallsBackInsteadOfThrowing() {
        assertEquals(HudRailSpec.DEFAULT_SLOT_COUNT, HudRailSpec.slotCountFromJson(null));
        assertEquals(HudRailSpec.DEFAULT_SLOT_COUNT, HudRailSpec.slotCountFromJson(""));
        assertEquals(HudRailSpec.DEFAULT_SLOT_COUNT, HudRailSpec.slotCountFromJson("{\"rail\":{\"width\":26}}"));
        // 越界值必须被拒绝，否则一个改坏的文档会把占位符推进非法槽位。
        assertEquals(HudRailSpec.DEFAULT_SLOT_COUNT,
                HudRailSpec.slotCountFromJson("{\"slot\": {\"count\": 99}}"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
