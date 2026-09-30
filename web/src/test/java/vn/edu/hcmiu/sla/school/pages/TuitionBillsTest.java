package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.model.SchoolTuitionBill;
import vn.edu.hcmiu.sla.school.pages.TuitionBills.Notice;

class TuitionBillsTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    static SchoolTuitionBill bill(String number, String status, long amount, long discount, LocalDate due,
            LocalDate paidOn) {
        return new SchoolTuitionBill(1, number, "20261", null, "Bill " + number, null, amount, discount, 0, status, due,
                paidOn, null);
    }

    static final SchoolTuitionBill LATE = bill("A", "unpaid", 10_000_000, 1_000_000, LocalDate.of(2026, 10, 15), null);
    static final SchoolTuitionBill SOON = bill("B", "paying", 1_105_650, 0, LocalDate.of(2026, 10, 10), null);
    static final SchoolTuitionBill UNDATED = bill("C", "partly_paid", 3_000_000, 0, null, null);
    static final SchoolTuitionBill PAID_TODAY = bill("D", "paid", 65_250_000, 0, null, TODAY);
    static final SchoolTuitionBill PAID_30_DAYS_AGO = bill("E", "paid", 100, 0, null, TODAY.minusDays(30));
    static final SchoolTuitionBill PAID_31_DAYS_AGO = bill("F", "paid", 200, 0, null, TODAY.minusDays(31));

    static final List<SchoolTuitionBill> ALL = List.of(PAID_31_DAYS_AGO, LATE, PAID_TODAY, UNDATED, SOON,
            PAID_30_DAYS_AGO);

    @Test
    void billsToPayComeSoonestDueFirstWithUndatedOnesLast() {
        assertThat(TuitionBills.toPay(ALL)).containsExactly(SOON, LATE, UNDATED);
    }

    @Test
    void paidBillsComeNewestFirst() {
        assertThat(TuitionBills.paid(ALL)).containsExactly(PAID_TODAY, PAID_30_DAYS_AGO, PAID_31_DAYS_AGO);
    }

    @Test
    void theNoticeAddsUpWhatIsLeftAfterDiscountsAndGivesTheEarliestDueDate() {
        assertThat(TuitionBills.notice(ALL, TODAY))
                .isEqualTo(new Notice(9_000_000 + 1_105_650 + 3_000_000, LocalDate.of(2026, 10, 10), false));
    }

    @Test
    void theNoticeIsOverdueOnceTheEarliestDueDateHasPassed() {
        assertThat(TuitionBills.notice(ALL, LocalDate.of(2026, 10, 11)).overdue()).isTrue();
        assertThat(TuitionBills.notice(ALL, LocalDate.of(2026, 10, 10)).overdue()).isFalse();
    }

    @Test
    void aBillWithoutADueDateIsNeverOverdue() {
        assertThat(TuitionBills.notice(List.of(UNDATED), TODAY)).isEqualTo(new Notice(3_000_000, null, false));
    }

    @Test
    void noNoticeWhenEverythingIsPaidOrThereAreNoBills() {
        assertThat(TuitionBills.notice(List.of(PAID_TODAY), TODAY)).isNull();
        assertThat(TuitionBills.notice(List.of(), TODAY)).isNull();
    }

    @Test
    void recentBillsAreTheOnesToPayThenPaymentsFromTheLast30Days() {
        assertThat(TuitionBills.recent(ALL, TODAY)).containsExactly(SOON, LATE, UNDATED, PAID_TODAY, PAID_30_DAYS_AGO);
    }
}
