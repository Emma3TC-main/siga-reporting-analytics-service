package utp.siga.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DatabaseMigrationTest extends ReportingIntegrationEnvironment {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesOnlyFourMvpTablesInAnalytics() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='analytics' AND table_type='BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);
        assertEquals(List.of("inventory_projection", "movement_projection", "processed_event", "product_projection"), tables);
        assertEquals(List.of("1", "2"), jdbc.queryForList(
                """
                SELECT version FROM analytics.flyway_schema_history
                WHERE success=true AND version IS NOT NULL
                ORDER BY installed_rank
                """,
                String.class));
    }

    @Test
    void noCrossSchemaObjectsOrForeignKeysExist() {
        Long foreignSchemas = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema IN ('catalog','inventory','iam','audit','evidence')
                """, Long.class);
        Long foreignKeys = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema='analytics' AND constraint_type='FOREIGN KEY'
                """, Long.class);
        assertEquals(0L, foreignSchemas);
        assertEquals(0L, foreignKeys);
    }
}
