package vn.edu.hcmiu.sla.school.pages;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.sync.Changes;

/**
 * How the School pages write times, money and scores. Templates call it as {@code ${@schoolFormat.when(...)}};
 * times are UTC as stored and shown in Vietnam time.
 */
@Component
public class SchoolFormat {

    static final int PREVIEW_CHARACTERS = 120;
    static final Map<String, String> CHANGE_LABELS = Map.of("online", "Online", "makeup", "Make-up", "cancelled", "Cancelled");
    static final Map<String, String> BILL_STATUSES = Map.of("unpaid", "Unpaid", "paid", "Paid",
            "paying", "Payment in progress", "partly_paid", "Partly paid, check IUPay for the rest");

    /** "Tue 29/09 08:00", or "never". */
    public String when(LocalDateTime utc) {
        return utc == null ? "never" : VietnamTime.when(utc);
    }

    /** "08:00". */
    public String clock(LocalDateTime utc) {
        return VietnamTime.clock(utc);
    }

    /** "29/09/2026", the Vietnam date of a UTC time. */
    public String date(LocalDateTime utc) {
        return VietnamTime.fullDate(VietnamTime.date(utc));
    }

    /** "15/10/2026". */
    public String day(LocalDate day) {
        return VietnamTime.fullDate(day);
    }

    /** "Tue 29/09". */
    public String dayLabel(LocalDate day) {
        return VietnamTime.dayLabel(day);
    }

    /** "12,500,000". */
    public String money(long vnd) {
        return String.format(Locale.ROOT, "%,d", vnd);
    }

    /** "8.5", "10", "6.66667" (as the What-changed lines write scores). */
    public String score(Double value) {
        return Changes.number(value);
    }

    /** An assignment's grade: "8.5/10", "8.5", the grade's text, or "graded". */
    public String grade(SchoolBbAssignment assignment) {
        return Changes.grade(assignment.getScore(), assignment.getPointsPossible(), assignment.getGradeText());
    }

    /** The badge for a class changed by an announcement. */
    public String changeLabel(String change) {
        return CHANGE_LABELS.get(change);
    }

    /** A bill's status as the pages say it: "Unpaid", "Payment in progress", … */
    public String billStatus(String status) {
        return BILL_STATUSES.getOrDefault(status, status);
    }

    /** The first 120 characters, with "…" when there is more. */
    public String preview(String text) {
        if (text.codePointCount(0, text.length()) <= PREVIEW_CHARACTERS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, PREVIEW_CHARACTERS)) + "…";
    }
}
