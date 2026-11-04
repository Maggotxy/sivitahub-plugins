package cn.originalrpg.api.model;

/** Passed/denied counts are checks in the local process, not settled rewards or global totals. */
public record MechanismSwitch(String id, boolean enabled, long allowedChecks, long deniedChecks) { }
