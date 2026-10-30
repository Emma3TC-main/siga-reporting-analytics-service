package utp.siga.reporting;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;

public abstract class ReportingIntegrationEnvironment {

    protected static final String EXCHANGE = "siga.events";
    protected static final String QUEUE = "reporting.projections.test.v1";
    protected static final String MOVEMENT_KEY = "inventory.movement.confirmed.v1";
    protected static final String PRODUCT_KEY = "catalog.product.updated.v1";

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_reporting_test")
            .withUsername("siga_reporting_test")
            .withPassword("reporting-test-only");

    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13.7-management-alpine");

    static {
        Startables.deepStart(POSTGRES, RABBIT).join();
    }

    @BeforeAll
    static void safetyGuard() {
        TestEnvironmentSafety.assertDedicated(
                POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName(), POSTGRES.getUsername());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("reporting.consumer.exchange", () -> EXCHANGE);
        registry.add("reporting.consumer.queue", () -> QUEUE);
        registry.add("reporting.consumer.movement-routing-key", () -> MOVEMENT_KEY);
        registry.add("reporting.consumer.product-routing-key", () -> PRODUCT_KEY);
        registry.add("reporting.consumer.retry.max-attempts", () -> "3");
        registry.add("reporting.consumer.retry.initial-interval-ms", () -> "10");
        registry.add("reporting.consumer.retry.multiplier", () -> "1.1");
        registry.add("reporting.consumer.retry.max-interval-ms", () -> "30");
        registry.add("reporting.security.jwks-uri", () -> "http://127.0.0.1:1/.well-known/jwks.json");
        registry.add("reporting.security.issuer", () -> "siga-identity");
        registry.add("reporting.security.audience", () -> "siga-api");
        registry.add("reporting.sources.catalog-base-url", () -> "http://127.0.0.1:1/api/v1");
        registry.add("reporting.sources.inventory-base-url", () -> "http://127.0.0.1:1/api/v1");
    }
}
