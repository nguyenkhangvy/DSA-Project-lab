package vn.edu.hcmiu.sla.school.pages;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import vn.edu.hcmiu.sla.school.model.SchoolTuitionBill;

/** Sorts and sums IUPay bills for the Tuition page and the Overview. Pure functions; dates are Vietnam dates. */
public final class TuitionBills {

    private TuitionBills() {
    }

    static final int RECENT_DAYS = 30; // a payment stays in the Overview's Bills list this long

    /** The Overview's notice: the total still to pay, the earliest due date (or null), and whether it has passed. */
    public record Notice(long total, LocalDate due, boolean overdue) {
    }

    private static final Comparator<SchoolTuitionBill> SOONEST_DUE = Comparator
            .comparing(SchoolTuitionBill::getDueDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(SchoolTuitionBill::getBillNo);

    private static final Comparator<SchoolTuitionBill> NEWEST_PAID = Comparator
            .comparing(SchoolTuitionBill::getPaidOn, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
            .thenComparing(SchoolTuitionBill::getBillNo);

    /** Bills not paid yet (unpaid, paying, partly paid), soonest due first; bills without a due date last. */
    public static List<SchoolTuitionBill> toPay(List<SchoolTuitionBill> bills) {
        return bills.stream().filter(bill -> !bill.isPaid()).sorted(SOONEST_DUE).toList();
    }

    /** Paid bills, newest payment first. */
    public static List<SchoolTuitionBill> paid(List<SchoolTuitionBill> bills) {
        return bills.stream().filter(SchoolTuitionBill::isPaid).sorted(NEWEST_PAID).toList();
    }

    /** What the Overview's notice says, or null when nothing is left to pay. */
    public static Notice notice(List<SchoolTuitionBill> bills, LocalDate today) {
        List<SchoolTuitionBill> toPay = toPay(bills);
        if (toPay.isEmpty()) {
            return null;
        }
        long total = toPay.stream().mapToLong(SchoolTuitionBill::getPayable).sum();
        LocalDate due = toPay.get(0).getDueDate();
        return new Notice(total, due, due != null && due.isBefore(today));
    }

    /** The Overview's Bills list: the bills to pay, then the payments of the last 30 days, newest first. */
    public static List<SchoolTuitionBill> recent(List<SchoolTuitionBill> bills, LocalDate today) {
        LocalDate since = today.minusDays(RECENT_DAYS);
        List<SchoolTuitionBill> recent = new ArrayList<>(toPay(bills));
        paid(bills).stream().filter(bill -> bill.getPaidOn() != null && !bill.getPaidOn().isBefore(since))
                .forEach(recent::add);
        return recent;
    }
}
