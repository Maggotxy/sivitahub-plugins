package cn.originalrpg.api.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Compact, dependency-free codec used to persist one operation in player PDC. */
public final class EquipmentOperationEnvelope {
    private static final int MAGIC = 0x53454f50; // SEOP
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 4_000_000;
    private static final int MAX_DELIVERIES = 16;

    private EquipmentOperationEnvelope() {
    }

    public static String encode(EquipmentOperationRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                writeUuid(output, request.operationId());
                writeUuid(output, request.playerId());
                output.writeByte(request.kind().ordinal());
                output.writeBoolean(request.itemId().isPresent());
                if (request.itemId().isPresent()) writeUuid(output, request.itemId().orElseThrow());
                writeString(output, request.inputFingerprint());
                writeString(output, request.requestPayload());
                writeString(output, request.goldCost().toPlainString());
                writeString(output, request.debitKey());
                writeList(output, request.committedDeliveries());
                writeList(output, request.rejectedDeliveries());
            }
            if (bytes.size() > MAX_TEXT_BYTES) throw new IllegalArgumentException("Equipment envelope is too large");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode equipment operation", exception);
        }
    }

    public static Optional<EquipmentOperationRequest> decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_TEXT_BYTES * 2L) return Optional.empty();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_TEXT_BYTES) return Optional.empty();
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return Optional.empty();
                UUID operationId = readUuid(input);
                UUID playerId = readUuid(input);
                int kindOrdinal = input.readUnsignedByte();
                if (kindOrdinal >= EquipmentOperationKind.values().length) return Optional.empty();
                Optional<UUID> itemId = input.readBoolean() ? Optional.of(readUuid(input)) : Optional.empty();
                String fingerprint = readString(input);
                String requestPayload = readString(input);
                BigDecimal cost = new BigDecimal(readString(input));
                String debitKey = readString(input);
                List<String> committed = readList(input);
                List<String> rejected = readList(input);
                if (input.read() != -1) return Optional.empty();
                EquipmentOperationRequest request = new EquipmentOperationRequest(operationId, playerId,
                        EquipmentOperationKind.values()[kindOrdinal], itemId, fingerprint,
                        requestPayload, cost, debitKey, committed, rejected);
                if (!request.settleable()) return Optional.empty();
                return Optional.of(request);
            }
        } catch (IllegalArgumentException | IOException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    /** Fingerprint of exact escrow payloads plus the player-visible operation choices. */
    public static String fingerprint(String requestPayload, List<String> escrowPayloads) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, requestPayload);
            for (String payload : escrowPayloads) update(digest, payload);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Stable IDs make result and recovery mailbox inserts idempotent. */
    public static UUID derivedId(UUID operationId, String lane, int index) {
        String value = "sivita-equipment-operation\n" + operationId + '\n' + lane + '\n' + index;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public static long deterministicSeed(UUID operationId, String lane) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operationId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(lane.getBytes(StandardCharsets.UTF_8));
            byte[] value = digest.digest();
            long seed = 0L;
            for (int index = 0; index < Long.BYTES; index++) seed = seed << 8 | value[index] & 0xffL;
            return seed;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void writeList(DataOutputStream output, List<String> values) throws IOException {
        if (values.size() > MAX_DELIVERIES) throw new IllegalArgumentException("Too many equipment deliveries");
        output.writeByte(values.size());
        for (String value : values) writeString(output, value);
    }

    private static List<String> readList(DataInputStream input) throws IOException {
        int size = input.readUnsignedByte();
        if (size > MAX_DELIVERIES) throw new IOException("Too many equipment deliveries");
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) values.add(readString(input));
        return List.copyOf(values);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("Equipment envelope field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES) throw new IOException("Invalid equipment envelope field");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated equipment envelope");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
