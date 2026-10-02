package cn.originalrpg.sulfur;

final class SulfurExtractionRules {
    private SulfurExtractionRules() {
    }

    static boolean cancelForMissingBlank(boolean tuned, boolean hasBlankImprint) {
        return tuned && !hasBlankImprint;
    }
}
