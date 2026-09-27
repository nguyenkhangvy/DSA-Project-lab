package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A term's tuition from EduSoft. Amounts are VND; a negative balance means overpaid. */
@Entity
@Table(name = "school_tuition")
public class SchoolTuition {

    /** One line of the tuition bill, kept in the JSON column {@code items}. */
    public record Item(String description, long amount) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "term_code", nullable = false, length = 20)
    private String termCode;

    @Column(name = "amount_due", nullable = false)
    private long amountDue;

    @Column(name = "amount_paid", nullable = false)
    private long amountPaid;

    @Column(nullable = false)
    private long balance;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "status_text", length = 255)
    private String statusText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Item> items;

    protected SchoolTuition() {
    }

    public SchoolTuition(Integer userId, String termCode, long amountDue, long amountPaid, long balance,
            LocalDate dueDate, String statusText, List<Item> items) {
        this.userId = userId;
        this.termCode = termCode;
        this.amountDue = amountDue;
        this.amountPaid = amountPaid;
        this.balance = balance;
        this.dueDate = dueDate;
        this.statusText = statusText;
        this.items = items;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getTermCode() {
        return termCode;
    }

    public long getAmountDue() {
        return amountDue;
    }

    public long getAmountPaid() {
        return amountPaid;
    }

    public long getBalance() {
        return balance;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getStatusText() {
        return statusText;
    }

    public List<Item> getItems() {
        return items;
    }
}
