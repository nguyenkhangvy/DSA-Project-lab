package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** How often the laptop syncs, and when "Sync now" was last pressed. One row per user. */
@Entity
@Table(name = "school_sync_settings")
public class SchoolSyncSettings {

    public static final int DEFAULT_INTERVAL_HOURS = 12;

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "interval_hours", nullable = false)
    private int intervalHours;

    @Column(name = "sync_requested_at")
    private LocalDateTime syncRequestedAt; // UTC

    protected SchoolSyncSettings() {
    }

    public SchoolSyncSettings(Integer userId, int intervalHours) {
        this.userId = userId;
        this.intervalHours = intervalHours;
    }

    public Integer getUserId() {
        return userId;
    }

    public int getIntervalHours() {
        return intervalHours;
    }

    public void setIntervalHours(int intervalHours) {
        this.intervalHours = intervalHours;
    }

    public LocalDateTime getSyncRequestedAt() {
        return syncRequestedAt;
    }

    public void setSyncRequestedAt(LocalDateTime syncRequestedAt) {
        this.syncRequestedAt = syncRequestedAt;
    }
}
