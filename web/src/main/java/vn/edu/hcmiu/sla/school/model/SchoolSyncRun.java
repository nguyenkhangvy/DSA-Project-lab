package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One sync by the laptop: when it ran, how it ended, and how each part went. Times are UTC. */
@Entity
@Table(name = "school_sync_runs")
public class SchoolSyncRun {

    public static final String RUNNING = "running";
    public static final String MAIL = "mail"; // trigger of a mail-only run: never counts as a full sync
    public static final String SUCCESS = "success";
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "device_id")
    private Integer deviceId; // empty once the device is deleted

    @Column(name = "`trigger`", nullable = false, length = 20) // quoted: TRIGGER is a MySQL keyword
    private String trigger; // scheduled / manual / import

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(nullable = false, length = 10)
    private String status; // running / success / partial / failed

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Per part: {"timetable": {"status": "ok"}, "tuition": {"status": "failed", "error_code": …}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Map<String, String>> sections;

    protected SchoolSyncRun() {
    }

    public SchoolSyncRun(Integer userId, Integer deviceId, String trigger, LocalDateTime startedAt) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.trigger = trigger;
        this.startedAt = startedAt;
        this.status = RUNNING;
    }

    /** Ends the run. */
    public void finish(String status, LocalDateTime finishedAt, String errorCode, String errorMessage) {
        this.status = status;
        this.finishedAt = finishedAt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getDeviceId() {
        return deviceId;
    }

    public String getTrigger() {
        return trigger;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Map<String, String>> getSections() {
        return sections;
    }

    public void setSections(Map<String, Map<String, String>> sections) {
        this.sections = sections;
    }
}
