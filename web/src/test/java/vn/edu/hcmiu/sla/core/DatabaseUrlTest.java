package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void theUsualLocalAddressBecomesAJdbcUrlUserAndPassword() {
        DatabaseUrl db = DatabaseUrl.parse("mysql+pymysql://sla_app:secret@localhost:3306/school_life?charset=utf8mb4");

        assertThat(db.jdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/school_life?characterEncoding=UTF-8");
        assertThat(db.username()).isEqualTo("sla_app");
        assertThat(db.password()).isEqualTo("secret");
    }

    @Test
    void percentCodesAreDecodedButAPlusStaysAPlus() {
        DatabaseUrl db = DatabaseUrl.parse("mysql+pymysql://sla_app:pa%40ss%2Bword+1@db.example.com:3306/sla");

        assertThat(db.password()).isEqualTo("pa@ss+word+1");
        assertThat(db.jdbcUrl()).isEqualTo("jdbc:mysql://db.example.com:3306/sla?characterEncoding=UTF-8");
    }

    @Test
    void anAddressWithoutAPortOrOptionsWorks() {
        assertThat(DatabaseUrl.parse("mysql://u:p@localhost/db").jdbcUrl())
                .isEqualTo("jdbc:mysql://localhost/db?characterEncoding=UTF-8");
    }

    @Test
    void quotesAroundTheValueAreIgnored() {
        assertThat(DatabaseUrl.parse("\"mysql+pymysql://u:p@localhost:3306/db\"").username()).isEqualTo("u");
    }

    @Test
    void spacesAndAWindowsLineEndAroundTheValueAreIgnored() {
        char carriageReturn = 13; // what a .env saved on Windows can leave at the end of a line
        DatabaseUrl db = DatabaseUrl.parse("  mysql+pymysql://u:p@localhost:3306/db?charset=utf8mb4 " + carriageReturn);

        assertThat(db.jdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/db?characterEncoding=UTF-8");
        assertThat(db.password()).isEqualTo("p");
    }

    @Test
    void aRawAtSignInThePasswordGetsTheSameHintAsThePythonSite() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql+pymysql://sla_app:pass@word@localhost:3306/sla"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DATABASE_URL contains more than one '@'. If your database password has an '@' in it, "
                        + "write it as %40 in .env (for example, pass@word becomes pass%40word).");
    }

    @Test
    void anythingButMysqlIsRefusedWithAHint() {
        assertThatThrownBy(() -> DatabaseUrl.parse("sqlite:///school.db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a MySQL address");
    }
}
