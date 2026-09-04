package com.uniblox.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniblox.store.product.Product;
import com.uniblox.store.product.ProductRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the persistence stack works end to end against a real PostgreSQL server:
 * the container starts, Spring connects to it, and Flyway brings an empty database
 * up to the current schema.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseMigrationIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductRepository products;

    @Test
    void flywayMigratesAnEmptyPostgresDatabase() {
        // Spring connected, and it is talking to PostgreSQL rather than an embedded stand-in.
        String product = jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>)
                        connection -> connection.getMetaData().getDatabaseProductName());
        assertThat(product).isEqualTo("PostgreSQL");

        // Flyway ran, and every migration it recorded succeeded. The assertion is on
        // "all applied cleanly" rather than an exact count, so adding a migration does
        // not break this test.
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, description, success from flyway_schema_history order by installed_rank");
        assertThat(history)
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).containsEntry("success", true))
                .extracting(row -> row.get("version"))
                .contains("1");

        // The migration's effects are really there, and the JPA mapping matches the schema.
        assertThat(products.findAll())
                .hasSize(5)
                .extracting(Product::getName)
                .contains("Limited Edition Vinyl");
    }
}
