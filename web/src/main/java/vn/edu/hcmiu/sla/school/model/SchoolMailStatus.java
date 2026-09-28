package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** When the laptop last read the Inbox, from which day, and whether Outlook was online. One row per user. */
@Entity
@Table(name = "school_mail_status")
public class SchoolMailStatus {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(nullable = false)
    private LocalDate since;

    @Column(nullable = false)
    private boolean connected;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt; // UTC

    protected SchoolMailStatus() {
    }

    public SchoolMailStatus(Integer userId, LocalDate since, boolean connected, LocalDateTime syncedAt) {
        this.userId = userId;
        this.since = since;
        this.connected = connected;
        this.syncedAt = syncedAt;
    }

    public Integer getUserId() {
        return userId;
    }

    public LocalDate getSince() {
        return since;
    }

    public boolean isConnected() {
        return connected;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}
