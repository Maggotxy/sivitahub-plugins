package cn.originalrpg.core.testing;

/**
 * 部署环境夹具测试的分类常量。
 *
 * <p>这些测试断言的是「运维侧部署产物」（运行中的 Paper 配置、数据包、资源包图片、
 * 第三方插件 jar 的命令注册表、BungeeCord 风格的跨插件对话包）。
 * 本仓（CNB 工作仓）按设计只含 {@code plugins/}、{@code docs/}、{@code tools/}，
 * 不含 {@code server/} 与 {@code resource-pack/}（参见首个 commit 的说明），
 * 所以它们必然无法在此环境通过 —— 这是环境缺口，不是代码缺陷。</p>
 *
 * <p>门禁仍应跑这些测试：{@code .cnb.yml} 的 test 阶段显式排除 {@code env} 标签，
 * 同时在流水线日志里跑一遍被排除的清单。这样「被排除的东西」是可见的，
 * 而不是静默跳过（禁止 {@code @Disabled} / 删测试 / 改恒真）。</p>
 */
public final class DeploymentEnvironmentFixtures {

    /** JUnit 5 标签名：表示该测试需要完整部署环境夹具。 */
    public static final String TAG = "env";

    private DeploymentEnvironmentFixtures() {
    }
}
