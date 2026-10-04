package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.TestClock;

/** Connect's one-time codes on their own: no Spring, and a clock the test moves. */
class ConnectCodesTest {

    // RFC 7636's example verifier and its challenge. The app's tests (test_connect.py) pin the same pair.
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 4, 12, 0);

    final TestClock clock = new TestClock();
    final ConnectCodes codes = new ConnectCodes(clock);

    @BeforeEach
    void atNoon() {
        clock.set(NOON);
    }

    String issue() {
        return codes.issue(7, "an@example.com", "LAPTOP-AN", CHALLENGE);
    }

    @Test
    void theChallengeIsTheVerifiersSha256InUrlSafeBase64() {
        assertThat(ConnectCodes.challenge(VERIFIER)).isEqualTo(CHALLENGE);
    }

    @Test
    void aCodeWithItsVerifierGivesItsConnectOnce() {
        String code = issue();

        assertThat(codes.redeem(code, VERIFIER)).hasValueSatisfying(pending -> {
            assertThat(pending.userId()).isEqualTo(7);
            assertThat(pending.email()).isEqualTo("an@example.com");
            assertThat(pending.name()).isEqualTo("LAPTOP-AN");
        });
        assertThat(codes.redeem(code, VERIFIER)).isEmpty();
    }

    @Test
    void aWrongVerifierFailsAndUsesTheCodeUp() {
        String code = issue();

        assertThat(codes.redeem(code, "x".repeat(43))).isEmpty();
        assertThat(codes.redeem(code, VERIFIER)).isEmpty();
    }

    @Test
    void aCodeLastsTwoMinutes() {
        String early = issue();
        String late = issue();

        clock.set(NOON.plusSeconds(119));
        assertThat(codes.redeem(early, VERIFIER)).isPresent();
        clock.set(NOON.plusMinutes(2));
        assertThat(codes.redeem(late, VERIFIER)).isEmpty();
    }

    @Test
    void anUnknownCodeFails() {
        assertThat(codes.redeem("c".repeat(43), VERIFIER)).isEmpty();
    }

    @Test
    void eachCodeIsNewAndShapedForALink() {
        String first = issue();
        String second = issue();

        assertThat(first).isNotEqualTo(second).matches(SyncContract.CONNECT_SECRET);
        assertThat(second).matches(SyncContract.CONNECT_SECRET);
    }
}
