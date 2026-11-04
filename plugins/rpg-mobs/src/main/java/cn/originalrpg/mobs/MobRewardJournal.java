package cn.originalrpg.mobs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single-writer, checksummed write-ahead instructions for natural-mob rewards.
 *
 * <p>A successful {@link #persist} is the durability acknowledgement: file data
 * is forced, renamed atomically, and the containing directory is forced before
 * returning. Unsupported atomic moves or directory sync fail closed. No database
 * grant is allowed merely because a write was queued or a staging file exists.</p>
 *
 * <p>The local file lock protects this directory from overlapping plugin instances;
 * it is not a distributed player lease. Deployments must preserve this directory
 * together with the authoritative database. Unacknowledged staging files are
 * counted and retained for diagnostics, but never replayed as accepted rewards.</p>
 */
final class MobRewardJournal implements MobRewardIoCoordinator.Store {
    static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final int MAGIC = 0x534D5231; // SMR1
    private static final int VERSION = 1;
    private static final String SUFFIX = ".mob-reward";
    private final Path root;
    private final int maximumRecords;
    private final long maximumBytes;
    private final Faults faults;
    private final Map<UUID, Long> sizes = new HashMap<>();
    private final Map<Path, Long> stagingSizes = new HashMap<>();
    private FileChannel lockChannel;
    private FileLock writerLock;
    private long bytes;
    private boolean scanned;
    private boolean closed;
    private volatile int orphanedStagingFiles;

    MobRewardJournal(Path root, int maximumRecords, long maximumBytes) {
        this(root, maximumRecords, maximumBytes, (step, intent) -> { });
    }

    MobRewardJournal(Path root, int maximumRecords, long maximumBytes, Faults faults) {
        this.root = root.toAbsolutePath().normalize();
        if (maximumRecords < 1 || maximumBytes < MAX_RECORD_BYTES) throw new IllegalArgumentException("Invalid journal limits");
        this.maximumRecords = maximumRecords;
        this.maximumBytes = maximumBytes;
        this.faults = java.util.Objects.requireNonNull(faults, "faults");
    }

    @Override
    public synchronized List<MobRewardIntent> load() throws IOException {
        open();
        List<MobRewardIntent> recovered = new ArrayList<>();
        Map<UUID, Long> found = new HashMap<>();
        Map<Path, Long> staging = new HashMap<>();
        long total = 0;
        int visited = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                if (++visited > maximumRecords * 2L + 32L) throw new IOException("Reward journal directory exceeds scan limit");
                String name = file.getFileName().toString();
                if (!name.endsWith(SUFFIX) && !name.endsWith(".staging")) continue;
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IOException("Invalid journal entry: " + name);
                long size = Files.size(file);
                if (size > MAX_RECORD_BYTES) throw new IOException("Reward journal entry exceeds size limit: " + name);
                total = Math.addExact(total, size);
                if (total > maximumBytes) throw new IOException("Reward journal exceeds byte budget; preserve files and raise the limit to recover");
                if (name.endsWith(".staging")) {
                    staging.put(file, size);
                    continue;
                }
                if (found.size() >= maximumRecords) throw new IOException("Reward journal exceeds record budget; preserve files and raise the limit to recover");
                MobRewardIntent intent = read(file);
                if (!name.equals(intent.killId() + SUFFIX) || found.putIfAbsent(intent.killId(), size) != null) {
                    throw new IOException("Reward journal filename/identity mismatch: " + name);
                }
                recovered.add(intent);
            }
        }
        recovered.sort(Comparator.comparingLong(MobRewardIntent::createdAtMillis).thenComparing(value -> value.killId().toString()));
        // A previous process may have exited after rename but before its fsync
        // acknowledgement. Confirm surviving final files before replay can issue
        // any new authority calls; a staging file is still not an accepted intent.
        for (MobRewardIntent intent : recovered) forceFile(path(intent.killId()));
        forceDirectory(root);
        sizes.clear(); sizes.putAll(found);
        stagingSizes.clear(); stagingSizes.putAll(staging);
        orphanedStagingFiles = staging.size();
        bytes = total;
        scanned = true;
        return List.copyOf(recovered);
    }

    @Override
    public synchronized MobRewardIntent persist(MobRewardIntent proposed) throws IOException {
        if (!scanned) load();
        else open();
        Path target = path(proposed.killId());
        MobRewardIntent durable = proposed;
        if (Files.exists(target)) {
            MobRewardIntent existing = read(target);
            if (!existing.sameObservation(proposed)) throw new IOException("Kill ID already contains different reward content: " + proposed.killId());
            if (existing.eligibility() != MobRewardIntent.Eligibility.UNKNOWN) {
                if (proposed.eligibility() != MobRewardIntent.Eligibility.UNKNOWN && !existing.equals(proposed)) {
                    throw new IOException("Resolved reward eligibility cannot change: " + proposed.killId());
                }
                durable = existing; // A shutdown retry must never downgrade a completed decision.
            }
            if (existing.equals(durable)) {
                // A prior rename may have succeeded before directory fsync reported an error.
                // Existence alone therefore cannot substitute for this acknowledgement.
                forceFile(target);
                forceDirectory(root);
                long size = Files.size(target);
                Long recorded = sizes.put(proposed.killId(), size);
                bytes += size - (recorded == null ? 0 : recorded);
                return existing;
            }
        }
        byte[] encoded = encode(durable);
        Long previous = sizes.get(proposed.killId());
        if (previous == null && sizes.size() >= maximumRecords) throw new IOException("Reward journal record budget is full");
        Path staging = root.resolve(proposed.killId() + ".staging");
        if (Files.deleteIfExists(staging)) {
            Long abandoned = stagingSizes.remove(staging);
            if (abandoned != null) bytes -= abandoned;
            orphanedStagingFiles = stagingSizes.size();
        }
        long nextBytes = bytes - (previous == null ? 0 : previous) + encoded.length;
        if (nextBytes > maximumBytes) throw new IOException("Reward journal byte budget is full");
        try {
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            faults.at(Step.FILE_FORCED, durable);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Track the physical file even when the acknowledgement below fails.
            sizes.put(proposed.killId(), (long) encoded.length);
            bytes = nextBytes;
            faults.at(Step.RENAMED, durable);
            forceDirectory(root);
            faults.at(Step.DIRECTORY_FORCED, durable);
            return durable;
        } finally {
            // A hard process exit leaves this unaccepted file intact. Ordinary
            // failed writes clean only their own staging file, never an intent.
            Files.deleteIfExists(staging);
        }
    }

    @Override
    public synchronized void remove(MobRewardIntent intent) throws IOException {
        if (!scanned) load();
        else open();
        Path target = path(intent.killId());
        if (Files.exists(target) && !read(target).equals(intent)) {
            throw new IOException("Cannot remove a different reward intent: " + intent.killId());
        }
        Files.deleteIfExists(target);
        faults.at(Step.DELETED, intent);
        forceDirectory(root);
        Long size = sizes.remove(intent.killId());
        if (size != null) bytes -= size;
    }

    int orphanedStagingFiles() {
        return orphanedStagingFiles;
    }

    private void open() throws IOException {
        if (closed) throw new IOException("Reward journal is closed");
        if (writerLock != null) return;
        List<Path> created = new ArrayList<>();
        for (Path parent = root; parent != null && !Files.exists(parent); parent = parent.getParent()) created.add(parent);
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Reward journal root must not be a symbolic link");
        for (int index = created.size() - 1; index >= 0; index--) {
            Path parent = created.get(index).getParent();
            if (parent != null) forceDirectory(parent);
        }
        FileChannel candidate = FileChannel.open(root.resolve(".writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = candidate.tryLock();
            if (lock == null) throw new IOException("Another process owns the mob reward journal");
            lockChannel = candidate;
            writerLock = lock;
        } catch (IOException | OverlappingFileLockException exception) {
            candidate.close();
            throw new IOException("Cannot exclusively open the mob reward journal", exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        try {
            if (writerLock != null) writerLock.release();
        } finally {
            try { if (lockChannel != null) lockChannel.close(); }
            finally { writerLock = null; lockChannel = null; }
        }
    }

    private Path path(UUID id) { return root.resolve(id + SUFFIX); }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (UnsupportedOperationException exception) { throw new IOException("Directory fsync is required for reward acceptance", exception); }
    }

    private static byte[] encode(MobRewardIntent intent) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(content)) {
            uuid(output, intent.killId()); uuid(output, intent.playerId());
            output.writeLong(intent.createdAtMillis()); output.writeInt(intent.mobLevel()); output.writeLong(intent.experience());
            text(output, intent.eligibility().name()); output.writeInt(intent.playerEffectiveLevel());
            output.writeInt(intent.deliveries().size());
            for (MobRewardIntent.Delivery delivery : intent.deliveries()) {
                uuid(output, delivery.deliveryId()); text(output, delivery.kind()); text(output, delivery.itemPayload());
                text(output, delivery.visualKind()); text(output, delivery.itemMessageKey()); output.writeBoolean(delivery.pityGuaranteed());
            }
            output.writeInt(intent.pityRolls().size());
            for (MobRewardIntent.PityRoll roll : intent.pityRolls()) {
                text(output, roll.counter()); output.writeInt(roll.before()); output.writeInt(roll.after()); output.writeInt(roll.guarantee());
                output.writeDouble(roll.chance()); output.writeDouble(roll.randomUnit());
                output.writeBoolean(roll.dropped()); output.writeBoolean(roll.guaranteed());
            }
        }
        byte[] body = content.toByteArray();
        if (body.length + 44 > MAX_RECORD_BYTES) throw new IOException("Reward snapshot exceeds the bounded record size");
        ByteArrayOutputStream envelope = new ByteArrayOutputStream(body.length + 44);
        try (DataOutputStream output = new DataOutputStream(envelope)) {
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(body.length);
            output.write(body); output.write(checksum(body));
        }
        return envelope.toByteArray();
    }

    private static MobRewardIntent read(Path file) throws IOException {
        byte[] encoded;
        try (var input = Files.newInputStream(file)) { encoded = input.readNBytes(MAX_RECORD_BYTES + 1); }
        if (encoded.length > MAX_RECORD_BYTES) throw new IOException("Oversized mob reward: " + file.getFileName());
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unsupported mob reward journal version");
            int length = input.readInt();
            if (length < 1 || length > MAX_RECORD_BYTES - 44 || encoded.length != length + 44) throw new IOException("Truncated or trailing reward bytes");
            byte[] body = input.readNBytes(length);
            if (!MessageDigest.isEqual(checksum(body), input.readNBytes(32))) throw new IOException("Mob reward checksum mismatch");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(body))) {
                UUID kill = uuid(data); UUID player = uuid(data);
                long created = data.readLong(); int mob = data.readInt(); long experience = data.readLong();
                MobRewardIntent.Eligibility eligibility = MobRewardIntent.Eligibility.valueOf(text(data, 32));
                int level = data.readInt();
                int count = boundedCount(data, MobRewardIntent.MAX_DELIVERIES);
                List<MobRewardIntent.Delivery> deliveries = new ArrayList<>(count);
                for (int index = 0; index < count; index++) deliveries.add(new MobRewardIntent.Delivery(
                        uuid(data), text(data, 32), text(data, MobRewardIntent.MAX_PAYLOAD_CHARACTERS),
                        text(data, 32), text(data, 160), data.readBoolean()));
                int rolls = boundedCount(data, 2);
                List<MobRewardIntent.PityRoll> pity = new ArrayList<>(rolls);
                for (int index = 0; index < rolls; index++) pity.add(new MobRewardIntent.PityRoll(text(data, 64),
                        data.readInt(), data.readInt(), data.readInt(), data.readDouble(), data.readDouble(),
                        data.readBoolean(), data.readBoolean()));
                if (data.read() != -1) throw new IOException("Trailing reward snapshot fields");
                return new MobRewardIntent(kill, player, created, mob, experience, eligibility, level, deliveries, pity);
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid mob reward journal: " + file.getFileName(), exception);
        }
    }

    private static int boundedCount(DataInputStream input, int limit) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > limit) throw new IOException("Reward collection limit exceeded");
        return count;
    }

    private static void uuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream input) throws IOException { return new UUID(input.readLong(), input.readLong()); }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }

    private static String text(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) throw new IOException("Reward string limit exceeded");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated reward string");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] checksum(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    enum Step { FILE_FORCED, RENAMED, DIRECTORY_FORCED, DELETED }
    @FunctionalInterface interface Faults { void at(Step step, MobRewardIntent intent) throws IOException; }
}
