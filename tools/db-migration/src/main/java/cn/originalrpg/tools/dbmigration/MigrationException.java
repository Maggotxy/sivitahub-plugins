package cn.originalrpg.tools.dbmigration;

public final class MigrationException extends Exception {
    private static final long serialVersionUID = 1L;

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
