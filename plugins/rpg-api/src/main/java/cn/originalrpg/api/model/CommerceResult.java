package cn.originalrpg.api.model;

public record CommerceResult(Status status, CommerceStatus account) {
    public enum Status {
        SUCCESS,
        MEMBERSHIP_LIMIT_REACHED,
        LIFETIME_LIMIT_REACHED,
        DUPLICATE,
        INVALID
    }
}
