package cn.originalrpg.tools.dbmigration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

final class PaperStopGate {
    private PaperStopGate() {
    }

    static void requireStopped(Path runtime) throws MigrationException {
        if (!Files.isDirectory(runtime)) {
            throw new MigrationException("Paper runtime directory does not exist: " + runtime);
        }
        Optional<Long> runningPid = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(process -> isProjectPaper(process, runtime))
                .map(ProcessHandle::pid)
                .findFirst();
        if (runningPid.isPresent()) {
            throw new MigrationException(
                    "Paper must be stopped before database migration (running PID " + runningPid.get() + ")"
            );
        }
    }

    static boolean commandLooksLikePaper(String command, String[] arguments, Path processCwd, Path runtime) {
        boolean jarArgument = Arrays.stream(arguments)
                .map(value -> value.toLowerCase(Locale.ROOT).replace('\\', '/'))
                .anyMatch(value -> value.equals("paper.jar") || value.endsWith("/paper.jar"));
        if (!jarArgument) return false;

        Path normalizedRuntime = runtime.toAbsolutePath().normalize();
        boolean runtimeCommand = false;
        if (command != null && !command.isBlank()) {
            try {
                runtimeCommand = Path.of(command).toAbsolutePath().normalize()
                        .startsWith(normalizedRuntime.resolve("java"));
            } catch (RuntimeException ignored) {
                runtimeCommand = false;
            }
        }
        boolean runtimeCwd = processCwd != null
                && processCwd.toAbsolutePath().normalize().equals(normalizedRuntime);
        return runtimeCommand || runtimeCwd;
    }

    private static boolean isProjectPaper(ProcessHandle process, Path runtime) {
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse("");
        String[] arguments = info.arguments().orElseGet(() -> new String[0]);
        Path cwd = processWorkingDirectory(process.pid()).orElse(null);
        return commandLooksLikePaper(command, arguments, cwd, runtime);
    }

    private static Optional<Path> processWorkingDirectory(long pid) {
        Path procCwd = Path.of("/proc", Long.toString(pid), "cwd");
        try {
            return Optional.of(procCwd.toRealPath());
        } catch (IOException | SecurityException ignored) {
            return Optional.empty();
        }
    }
}
