package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One line in the "What changed" feed. */
@Entity
@Table(name = "school_changes")
public class SchoolChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "sync_run_id", nullable = false)
    private Integer syncRunId;

    @Column(nullable = false, length = 20)
    private String section; // timetable / exams / tuition / blackboard

    @Column(nullable = false, length = 10)
    private String kind; // added / removed / changed

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // UTC

    @Column(name = "seen_at")
    private LocalDateTime seenAt;

    protected SchoolChange() {
    }

    public SchoolChange(Integer userId, Integer syncRunId, String section, String kind, String summary,
            LocalDateTime createdAt) {
        this.userId = userId;
        this.syncRunId = syncRunId;
        this.section = section;
        this.kind = kind;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getSyncRunId() {
        return syncRunId;
    }

    public String getSection() {
        return section;
    }

    public String getKind() {
        return kind;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(LocalDateTime seenAt) {
        this.seenAt = seenAt;
    }
}
