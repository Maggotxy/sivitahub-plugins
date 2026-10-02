package cn.originalrpg.dungeons;

enum SeasonExpeditionAffix {
    FORTIFIED("fortified"),
    RELENTLESS("relentless"),
    SWARMING("swarming"),
    BRUTAL("brutal");

    private final String key;

    SeasonExpeditionAffix(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }
}
