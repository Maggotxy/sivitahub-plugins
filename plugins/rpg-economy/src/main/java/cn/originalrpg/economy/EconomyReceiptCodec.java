package cn.originalrpg.economy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

final class EconomyReceiptCodec {
    private static final String VERSION = "2";
    private static final int MAX_RECEIPTS = 16;
    private static final int MAX_SERIALIZED_LENGTH = 500_000;

    private EconomyReceiptCodec() {}

    static String encodeAll(List<Receipt> receipts) {
        if (receipts.size() > MAX_RECEIPTS) throw new IllegalArgumentException("Too many economy receipts");
        String value = receipts.stream().map(EconomyReceiptCodec::encode).collect(
                java.util.stream.Collectors.joining("\n"));
        if (value.length() > MAX_SERIALIZED_LENGTH) throw new IllegalArgumentException("Economy receipts are too large");
        return value;
    }

    static List<Receipt> decodeAll(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_SERIALIZED_LENGTH) return List.of();
        List<Receipt> result = new ArrayList<>();
        for (String line : encoded.split("\\R")) {
            decode(line).ifPresent(result::add);
            if (result.size() == MAX_RECEIPTS) break;
        }
        return List.copyOf(result);
    }

    private static String encode(Receipt receipt) {
        return String.join("|", VERSION, receipt.kind().name(), receipt.stage().name(),
                receipt.id().toString(), receipt.operationKey(), receipt.subjectId(), receipt.periodKey(),
                receipt.gold().toPlainString(), Long.toString(receipt.merit()),
                Integer.toString(receipt.maximumUnits()), receipt.rejection().name(), receipt.itemPayload());
    }

    private static Optional<Receipt> decode(String line) {
        String[] parts = line.split("\\|", -1);
        boolean legacy = parts.length == 11 && "1".equals(parts[0]);
        if (!legacy && (parts.length != 12 || !VERSION.equals(parts[0]))) return Optional.empty();
        try {
            Kind kind = Kind.valueOf(parts[1].toUpperCase(Locale.ROOT));
            Stage stage = Stage.valueOf(parts[2].toUpperCase(Locale.ROOT));
            UUID id = UUID.fromString(parts[3]);
            BigDecimal gold = new BigDecimal(parts[7]);
            long merit = Long.parseLong(parts[8]);
            int maximumUnits = Integer.parseInt(parts[9]);
            Rejection rejection = legacy ? Rejection.NONE
                    : Rejection.valueOf(parts[10].toUpperCase(Locale.ROOT));
            Receipt receipt = new Receipt(kind, stage, id, parts[4], parts[5], parts[6], gold,
                    merit, maximumUnits, rejection, parts[legacy ? 10 : 11]);
            return receipt.valid() ? Optional.of(receipt) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    enum Kind { COMMISSION, MERCHANT_BUYBACK }
    enum Stage { PREPARED, ESCROWED, REFUND_PENDING, RELEASE_PENDING, QUARANTINED }
    enum Rejection { NONE, MATERIAL_MISSING, DAILY_LIMIT, BALANCE_LIMIT, MERIT_LIMIT, INVALID }

    record Receipt(
            Kind kind,
            Stage stage,
            UUID id,
            String operationKey,
            String subjectId,
            String periodKey,
            BigDecimal gold,
            long merit,
            int maximumUnits,
            Rejection rejection,
            String itemPayload
    ) {
        Receipt {
            if (!EconomyReceiptCodec.valid(kind, stage, id, operationKey, subjectId, periodKey, gold,
                    merit, maximumUnits, rejection, itemPayload)) {
                throw new IllegalArgumentException("Invalid economy receipt");
            }
        }

        boolean valid() {
            return EconomyReceiptCodec.valid(kind, stage, id, operationKey, subjectId, periodKey, gold,
                    merit, maximumUnits, rejection, itemPayload);
        }

        Receipt withStage(Stage next) {
            return new Receipt(kind, next, id, operationKey, subjectId, periodKey, gold,
                    merit, maximumUnits, rejection, itemPayload);
        }

        Receipt withRejection(Rejection next) {
            return new Receipt(kind, stage, id, operationKey, subjectId, periodKey, gold,
                    merit, maximumUnits, next, itemPayload);
        }
    }

    private static boolean valid(
            Kind kind, Stage stage, UUID id, String operationKey, String subjectId, String periodKey,
            BigDecimal gold, long merit, int maximumUnits, Rejection rejection, String itemPayload
    ) {
        return kind != null && stage != null && rejection != null && id != null && operationKey != null
                && operationKey.matches("[A-Za-z0-9._:-]{3,96}") && subjectId != null
                && subjectId.matches("[a-z0-9._-]{2,64}") && periodKey != null
                && periodKey.matches("[A-Za-z0-9._:-]{0,32}") && gold != null && gold.signum() >= 0
                && merit >= 0 && maximumUnits >= 0 && maximumUnits <= 100_000
                && itemPayload != null && !itemPayload.isBlank() && itemPayload.length() <= 400_000;
    }
}
