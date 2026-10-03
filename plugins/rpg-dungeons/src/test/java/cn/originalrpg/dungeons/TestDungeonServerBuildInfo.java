package cn.originalrpg.dungeons;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 测试用的最小 {@link ServerBuildInfo}。
 *
 * <p>{@code Bukkit.setServer(Server)} 会先读版本信息，而 Paper 的实现经 ServiceLoader
 * 找本接口；测试进程里没有服务端实现，装假 {@code Server} 会在
 * {@code ServerBuildInfo.buildInfo()} 处 {@code NoSuchElementException: No value present}。
 * 这里只提供构造函数路径需要的常量，不参与任何玩法逻辑。</p>
 */
public final class TestDungeonServerBuildInfo implements ServerBuildInfo {

    @Override
    public Key brandId() {
        return Key.key("papermc", "paper");
    }

    @Override
    public boolean isBrandCompatible(Key key) {
        return true;
    }

    @Override
    public String brandName() {
        return "Paper";
    }

    @Override
    public String minecraftVersionId() {
        return "26.2";
    }

    @Override
    public String minecraftVersionName() {
        return "26.2";
    }

    @Override
    public OptionalInt buildNumber() {
        return OptionalInt.of(100);
    }

    @Override
    public Instant buildTime() {
        return Instant.EPOCH;
    }

    @Override
    public Optional<String> gitBranch() {
        return Optional.empty();
    }

    @Override
    public Optional<String> gitCommit() {
        return Optional.empty();
    }

    @Override
    public String asString(ServerBuildInfo.StringRepresentation representation) {
        return "Paper 26.2";
    }
}
