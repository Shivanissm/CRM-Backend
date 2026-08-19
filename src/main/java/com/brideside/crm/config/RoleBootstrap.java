package com.brideside.crm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures standard CRM roles exist in the {@code roles} table on startup.
 */
@Component
@Order(0)
public class RoleBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoleBootstrap.class);

    private static final String[][] ROLES = {
            {
                    "ADMIN",
                    "Administrator with full access. Can create, update, delete any data and manage all users."
            },
            {
                    "CATEGORY_MANAGER",
                    "Category manager. Oversees sales and presales teams within their category."
            },
            {
                    "SALES",
                    "Sales user. Manages deals, organizations, pipelines, and presales team members."
            },
            {
                    "PRESALES",
                    "Presales user. Supports sales with leads, activities, and deals under a sales manager."
            }
    };

    private final JdbcTemplate jdbcTemplate;

    public RoleBootstrap(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureRoleEnumValues();
            seedRolesIfNeeded();
        } catch (Exception e) {
            log.warn("Role bootstrap failed: {}", e.toString());
        }
    }

    private void ensureRoleEnumValues() {
        String columnType = jdbcTemplate.query(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roles' AND COLUMN_NAME = 'name'",
                rs -> rs.next() ? rs.getString(1) : null);
        if (columnType == null || !columnType.toLowerCase().contains("enum")) {
            return;
        }
        if (columnType.contains("'SALES'") && columnType.contains("'PRESALES'")) {
            return;
        }
        jdbcTemplate.execute(
                """
                ALTER TABLE roles
                    MODIFY COLUMN name ENUM('ADMIN', 'CATEGORY_MANAGER', 'SALES', 'PRESALES')
                    NOT NULL
                """
        );
        log.info("Updated roles.name ENUM to include ADMIN, CATEGORY_MANAGER, SALES, PRESALES");
    }

    private void seedRolesIfNeeded() {
        int inserted = 0;
        for (String[] role : ROLES) {
            String name = role[0];
            String description = role[1];
            int updated = jdbcTemplate.update(
                    """
                    INSERT INTO roles (name, description)
                    SELECT ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = ?)
                    """,
                    name, description, name
            );
            inserted += updated;
        }
        if (inserted > 0) {
            log.info("Role bootstrap inserted {} role row(s)", inserted);
        } else {
            log.debug("Role bootstrap: all standard roles already present");
        }
    }
}
