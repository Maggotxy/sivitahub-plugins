package cn.originalrpg.mobs;

import java.nio.file.Path;
import java.util.UUID;

/** A separate JVM halts without shutdown hooks at the selected real journal boundary. */
public final class MobRewardCrashFixture {
    private MobRewardCrashFixture() { }
    public static void main(String[] args) throws Exception {
        MobRewardJournal.Step stop = MobRewardJournal.Step.valueOf(args[1]);
        MobRewardIntent intent = MobRewardFixtures.reward(UUID.fromString(args[2]), UUID.fromString(args[3]), 42L, 2);
        MobRewardJournal journal = new MobRewardJournal(Path.of(args[0]), 8, MobRewardFixtures.BYTE_LIMIT,
                (step, ignored) -> { if (step == stop) Runtime.getRuntime().halt(73); });
        journal.persist(intent);
        throw new AssertionError("Crash boundary was not reached");
    }
}
