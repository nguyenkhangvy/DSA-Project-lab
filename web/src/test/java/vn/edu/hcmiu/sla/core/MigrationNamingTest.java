package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Migrations are named V&lt;date&gt;_1_&lt;number&gt;__&lt;what&gt;.sql, where 1 is the School module. */
class MigrationNamingTest {

    static final Pattern NAME = Pattern.compile("V\\d{8}_1_\\d+__[a-z0-9_]+\\.sql");

    @Test
    void everyMigrationIsNamedByDateModuleAndNumber() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*");

        assertThat(files).isNotEmpty();
        for (Resource file : files) {
            String name = file.getFilename();
            assertThat(name.equals("V1__baseline.sql") || NAME.matcher(name).matches())
                    .as("%s should be named like V20261001_1_1__new_table.sql "
                            + "(date, module: 1 School, then a number)", name)
                    .isTrue();
        }
    }
}
