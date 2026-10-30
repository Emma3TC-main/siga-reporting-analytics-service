package utp.siga.reporting;

import java.net.URI;

public final class TestEnvironmentSafety {

    private TestEnvironmentSafety() {
    }

    public static void assertDedicated(String jdbcUrl, String database, String user) {
        String databasePath = jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")
                ? null
                : URI.create(jdbcUrl.substring("jdbc:".length())).getPath();
        if (databasePath == null
                || database == null || !database.endsWith("_test")
                || user == null || !user.endsWith("_test")
                || !databasePath.equals("/" + database)) {
            throw new IllegalStateException("Reporting tests require a dedicated *_test Testcontainers database");
        }
    }
}
