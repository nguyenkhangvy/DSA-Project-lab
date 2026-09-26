package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

/** On an empty database (a teammate's laptop), Flyway's V1 creates every table the Python site had. */
@SpringBootTest
class MigrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void anEmptyDatabaseGetsEveryTable() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }

        assertThat(tables).contains(
                "users", "school_sync_devices", "school_sync_settings", "school_sync_runs", "school_changes",
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "flyway_schema_history");
    }

    @Test
    void theBaselineLeavesKeyNamesToMysqlLikeAlembicDid() throws Exception {
        // The student's database got MySQL's own names (email, user_id, school_courses_ibfk_1, ...). Unnamed keys
        // give a Flyway-made database the same names, so a later migration that changes a key by name works on both.
        String baseline = new ClassPathResource("db/migration/V1__baseline.sql").getContentAsString(StandardCharsets.UTF_8);

        assertThat(baseline).doesNotContain("CONSTRAINT");
    }
}
