package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** When the laptop last read IUPay successfully. One row per user; no row means IUPay was never read. */
@Entity
@Table(name = "school_tuition_status")
public class SchoolTuitionStatus {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt; // UTC

    protected SchoolTuitionStatus() {
    }

    public SchoolTuitionStatus(Integer userId, LocalDateTime checkedAt) {
        this.userId = userId;
        this.checkedAt = checkedAt;
    }

    public Integer getUserId() {
        return userId;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }
}
