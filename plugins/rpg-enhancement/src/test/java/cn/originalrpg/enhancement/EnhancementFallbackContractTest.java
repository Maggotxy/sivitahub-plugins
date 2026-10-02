package cn.originalrpg.enhancement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺配置时的价格回退合同（G-302 第二批）。
 *
 * <p>原实现读 {@code RefineCommand.java} 等源码做 {@code contains("getString(\"refinement.price\", \"400.00\")")}。</p>
 *
 * <p><b>为什么不能完全行为化</b>：这四条断言证明的是「缺配置时价格回退到评审过的数字」。
 * 生产实现是 {@code plugin.getConfig().getString(key, default)}，其中 {@code Plugin} 来自
 * paper-api，而 {@code rpg-enhancement} 的测试 classpath 里没有 paper-api
 * （{@code compileOnly(project(":plugins:rpg-api"))} 不传递 paper-api），
 * 因此无法在测试里构造出命令对象去真实读取配置。</p>
 *
 * <p><b>本轮的落地方案</b>（按 G-302 要求②）：把这条「构建契约」抽到
 * {@code scripts/check-config-fallbacks.sh} 静态门禁，并由本测试真实执行该脚本。
 * 门禁断言的是「每个价格键的查找都带显式非空默认值」——删掉默认值门禁即红、测试即红。
 * 真实解析出来的价格由 {@link MutationConfigurationContractTest}（SnakeYAML 解析
 * {@code config.yml}）覆盖，两者互补：一个证明「配置真的被读」，一个证明「缺失时有兜底」。</p>
 */
final class EnhancementFallbackContractTest {

    @Test
    void missingConfigurationCannotSilentlyRestoreLegacyDiscounts() throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-config-fallbacks.sh");
        assertTrue(Files.isRegularFile(gate), "缺少配置回退门禁脚本: " + gate);

        Process process = new ProcessBuilder("sh", gate.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "配置回退门禁未在 60 秒内结束");
        assertEquals(0, process.exitValue(),
                "缺配置回退门禁失败：价格键必须带显式默认值\n" + output);
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
