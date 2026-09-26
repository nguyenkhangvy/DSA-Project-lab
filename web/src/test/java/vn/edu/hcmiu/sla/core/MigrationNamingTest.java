package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Migrations are named V&lt;date&gt;_&lt;module&gt;_&lt;number&gt;__&lt;what&gt;.sql with module 1 = School, 2 = Expense,
 * 3 = Health, so two teammates adding a migration on the same day never pick the same version.
 */
class MigrationNamingTest {

    static final Pattern NAME = Pattern.compile("V\\d{8}_[123]_\\d+__[a-z0-9_]+\\.sql");

    @Test
    void everyMigrationHasAVersionNoOtherModuleCanTake() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*");

        assertThat(files).isNotEmpty();
        for (Resource file : files) {
            String name = file.getFilename();
            assertThat(name.equals("V1__baseline.sql") || NAME.matcher(name).matches())
                    .as("%s should be named like V20261001_2_1__expense_tables.sql "
                            + "(date, module: 1 School, 2 Expense, 3 Health, then a number)", name)
                    .isTrue();
        }
    }
}
