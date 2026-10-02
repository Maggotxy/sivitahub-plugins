package cn.originalrpg.dungeons;

import cn.originalrpg.api.model.DungeonRewardDelivery;
import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.DungeonTicketCandidate;
import cn.originalrpg.api.model.SeasonExpeditionCompletion;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/** Small fsync-backed write-ahead journal for database-outage and hard-crash recovery. */
final class DungeonRewardJournal implements DungeonRewardIoCoordinator.Store {
    private static final int MAGIC = 0x53445231; // SDR1
    private static final int VERSION = 1;
    private static final int BATCH_MAGIC = 0x53444231; // SDB1
    private static final int BATCH_VERSION = 2;
    private static final int MAX_STRING_BYTES = 1_100_000;
    private static final int MAX_DELIVERIES = 16;

    private final Path root;

    DungeonRewardJournal(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void persist(DungeonRewardBatch batch) throws IOException {
        Files.createDirectories(root);
        Path target = batchPath(batch.runId());
        if (Files.isRegularFile(target)) {
            DungeonRewardBatch existing = readBatch(target);
            if (!existing.equals(batch)) {
                throw new IOException("Reward batch key already contains different content: " + target.getFileName());
            }
            return;
        }
        Path temporary = root.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                         java.nio.channels.Channels.newOutputStream(channel)))) {
                output.writeInt(BATCH_MAGIC);
                output.writeInt(BATCH_VERSION);
                output.writeInt(batch.requests().size());
                for (DungeonRewardRequest request : batch.requests()) writeRequest(output, request);
                writeSeasonCompletion(output, batch.seasonCompletion());
                output.flush();
                channel.force(true);
            }
            moveAtomically(temporary, target);
            forceDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized void persist(DungeonRewardRequest request) throws IOException {
        Files.createDirectories(root);
        Path target = path(request);
        if (Files.isRegularFile(target)) {
            DungeonRewardRequest existing = read(target);
            if (!existing.equals(request)) {
                throw new IOException("Reward journal key already contains different content: " + target.getFileName());
            }
            return;
        }
        Path temporary = root.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                         java.nio.channels.Channels.newOutputStream(channel)))) {
                write(output, request);
                output.flush();
                channel.force(true);
            }
            moveAtomically(temporary, target);
            forceDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized List<DungeonRewardRequest> loadAll() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.reward")) {
            stream.forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        List<DungeonRewardRequest> requests = new ArrayList<>(files.size());
        for (Path file : files) requests.add(read(file));
        return List.copyOf(requests);
    }

    @Override
    public synchronized List<DungeonRewardBatch> load() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<DungeonRewardBatch> batches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.batch")) {
            for (Path file : stream) batches.add(readBatch(file));
        }
        // Version-1 per-player journals remain replayable after an in-place upgrade.
        // They are grouped by run where possible, but new writes are always atomic batches.
        Map<UUID, List<DungeonRewardRequest>> legacy = new java.util.TreeMap<>(
                Comparator.comparing(UUID::toString));
        for (DungeonRewardRequest request : loadAll()) {
            legacy.computeIfAbsent(request.runId(), ignored -> new ArrayList<>()).add(request);
        }
        legacy.forEach((runId, requests) -> batches.add(new DungeonRewardBatch(runId, requests)));
        batches.sort(Comparator.comparing(batch -> batch.runId().toString()));
        return List.copyOf(batches);
    }

    synchronized void remove(DungeonRewardRequest request) throws IOException {
        Files.deleteIfExists(path(request));
        forceDirectory();
    }

    @Override
    public synchronized void remove(DungeonRewardBatch batch) throws IOException {
        Files.deleteIfExists(batchPath(batch.runId()));
        for (DungeonRewardRequest request : batch.requests()) Files.deleteIfExists(path(request));
        forceDirectory();
    }

    private Path path(DungeonRewardRequest request) {
        return root.resolve(request.runId() + "_" + request.playerId() + ".reward");
    }

    private Path batchPath(UUID runId) {
        return root.resolve(runId + ".batch");
    }

    private DungeonRewardBatch readBatch(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != BATCH_MAGIC) {
                throw new IOException("Unsupported reward batch format: " + file.getFileName());
            }
            int version = input.readInt();
            if (version < 1 || version > BATCH_VERSION) {
                throw new IOException("Unsupported reward batch format: " + file.getFileName());
            }
            int size = input.readInt();
            if (size < 1 || size > 8) throw new IOException("Invalid reward batch size");
            List<DungeonRewardRequest> requests = new ArrayList<>(size);
            for (int index = 0; index < size; index++) requests.add(readRequest(input, file));
            Optional<SeasonExpeditionCompletion> season = version >= 2
                    ? readSeasonCompletion(input, file) : Optional.empty();
            if (input.read() != -1) throw new IOException("Trailing bytes in reward batch");
            return new DungeonRewardBatch(requests.getFirst().runId(), requests, season);
        } catch (EOFException exception) {
            throw new IOException("Truncated reward batch: " + file.getFileName(), exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid reward batch: " + file.getFileName(), exception);
        }
    }

    private DungeonRewardRequest read(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported reward journal format: " + file.getFileName());
            }
            DungeonRewardRequest request = readRequest(input, file);
            if (input.read() != -1) throw new IOException("Trailing bytes in reward journal");
            return request;
        } catch (EOFException exception) {
            throw new IOException("Truncated reward journal: " + file.getFileName(), exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid reward journal: " + file.getFileName(), exception);
        }
    }

    private DungeonRewardRequest readRequest(DataInputStream input, Path file) throws IOException {
        UUID runId = readUuid(input);
        String dungeonId = readString(input);
        UUID playerId = readUuid(input);
        long experience = input.readLong();
        int size = input.readInt();
        if (size < 1 || size > MAX_DELIVERIES) throw new IOException("Invalid reward delivery count");
        List<DungeonRewardDelivery> deliveries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) deliveries.add(readDelivery(input));
        Optional<DungeonTicketCandidate> ticket = Optional.empty();
        if (input.readBoolean()) {
            DungeonRewardDelivery delivery = readDelivery(input);
            String pityGroup = readString(input);
            double chance = input.readDouble();
            int guarantee = input.readInt();
            ticket = Optional.of(new DungeonTicketCandidate(delivery, pityGroup, chance, guarantee));
        }
        try {
            return new DungeonRewardRequest(runId, dungeonId, playerId, experience, deliveries, ticket);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid reward request in " + file.getFileName(), exception);
        }
    }

    private static void write(DataOutputStream output, DungeonRewardRequest request) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        writeRequest(output, request);
    }

    private static void writeRequest(DataOutputStream output, DungeonRewardRequest request) throws IOException {
        writeUuid(output, request.runId());
        writeString(output, request.dungeonId());
        writeUuid(output, request.playerId());
        output.writeLong(request.experience());
        output.writeInt(request.deliveries().size());
        for (DungeonRewardDelivery delivery : request.deliveries()) writeDelivery(output, delivery);
        output.writeBoolean(request.ticketCandidate().isPresent());
        if (request.ticketCandidate().isPresent()) {
            DungeonTicketCandidate ticket = request.ticketCandidate().orElseThrow();
            writeDelivery(output, ticket.delivery());
            writeString(output, ticket.pityGroup());
            output.writeDouble(ticket.chance());
            output.writeInt(ticket.guarantee());
        }
    }

    private static void writeSeasonCompletion(
            DataOutputStream output, Optional<SeasonExpeditionCompletion> completion
    ) throws IOException {
        output.writeBoolean(completion.isPresent());
        if (completion.isEmpty()) return;
        SeasonExpeditionCompletion value = completion.orElseThrow();
        writeUuid(output, value.runId());
        writeString(output, value.seasonId());
        writeString(output, value.rotationId());
        writeString(output, value.dungeonId());
        output.writeInt(value.partySize());
        output.writeLong(value.durationMillis());
        output.writeInt(value.participantIds().size());
        for (UUID playerId : value.participantIds()) writeUuid(output, playerId);
        output.writeLong(value.completedAt().toEpochMilli());
    }

    private static Optional<SeasonExpeditionCompletion> readSeasonCompletion(
            DataInputStream input, Path file
    ) throws IOException {
        if (!input.readBoolean()) return Optional.empty();
        UUID runId = readUuid(input);
        String seasonId = readString(input);
        String rotationId = readString(input);
        String dungeonId = readString(input);
        int partySize = input.readInt();
        long durationMillis = input.readLong();
        int participants = input.readInt();
        if (participants < 1 || participants > 8) {
            throw new IOException("Invalid season participant count in " + file.getFileName());
        }
        List<UUID> participantIds = new ArrayList<>(participants);
        for (int index = 0; index < participants; index++) participantIds.add(readUuid(input));
        try {
            return Optional.of(new SeasonExpeditionCompletion(
                    runId, seasonId, rotationId, dungeonId, partySize, durationMillis,
                    participantIds, Instant.ofEpochMilli(input.readLong())));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid season completion in " + file.getFileName(), exception);
        }
    }

    private static DungeonRewardDelivery readDelivery(DataInputStream input) throws IOException {
        return new DungeonRewardDelivery(readUuid(input), readString(input), readString(input));
    }

    private static void writeDelivery(DataOutputStream output, DungeonRewardDelivery delivery) throws IOException {
        writeUuid(output, delivery.deliveryId());
        writeString(output, delivery.itemPayload());
        writeString(output, delivery.source());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid reward journal string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated reward journal string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Reward journal string is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private void forceDirectory() throws IOException {
        if (!Files.isDirectory(root)) return;
        try (FileChannel channel = FileChannel.open(root, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException ignored) {
            // Some filesystems do not expose directory fsync; the atomic file is still usable.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }
}
