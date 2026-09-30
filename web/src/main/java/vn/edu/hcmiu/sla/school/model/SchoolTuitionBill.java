package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One tuition bill from IUPay. Amounts are VND; dueDate and paidOn are Vietnam dates. Each good IUPay read
 * replaces the student's bills. status: unpaid / paid / paying / partly_paid.
 */
@Entity
@Table(name = "school_tuition_bills")
public class SchoolTuitionBill {

    public static final String PAID = "paid";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "bill_no", nullable = false, length = 40)
    private String billNo;

    @Column(name = "term_code", nullable = false, length = 20)
    private String termCode;

    @Column(name = "term_name", length = 255)
    private String termName;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "fee_type", length = 255)
    private String feeType;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long discount;

    @Column(nullable = false)
    private long fee;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(length = 100)
    private String channel;

    protected SchoolTuitionBill() {
    }

    public SchoolTuitionBill(Integer userId, String billNo, String termCode, String termName, String description,
            String feeType, long amount, long discount, long fee, String status, LocalDate dueDate, LocalDate paidOn,
            String channel) {
        this.userId = userId;
        this.billNo = billNo;
        this.termCode = termCode;
        this.termName = termName;
        this.description = description;
        this.feeType = feeType;
        this.amount = amount;
        this.discount = discount;
        this.fee = fee;
        this.status = status;
        this.dueDate = dueDate;
        this.paidOn = paidOn;
        this.channel = channel;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getBillNo() {
        return billNo;
    }

    public String getTermCode() {
        return termCode;
    }

    public String getTermName() {
        return termName;
    }

    public String getDescription() {
        return description;
    }

    public String getFeeType() {
        return feeType;
    }

    public long getAmount() {
        return amount;
    }

    public long getDiscount() {
        return discount;
    }

    public long getFee() {
        return fee;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public String getChannel() {
        return channel;
    }

    /** What the bill asks for after its discount (IUPay leaves the fee to the payment). */
    public long getPayable() {
        return amount - discount;
    }

    public boolean isPaid() {
        return PAID.equals(status);
    }
}
