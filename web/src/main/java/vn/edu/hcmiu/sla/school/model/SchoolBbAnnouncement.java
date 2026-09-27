package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An announcement in a Blackboard course. */
@Entity
@Table(name = "school_bb_announcements")
public class SchoolBbAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private SchoolBbCourse course;

    @Column(name = "bb_id", nullable = false, length = 64)
    private String bbId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "posted_at")
    private LocalDateTime postedAt; // UTC

    @Column(nullable = false, length = 500)
    private String url;

    protected SchoolBbAnnouncement() {
    }

    public SchoolBbAnnouncement(SchoolBbCourse course, String bbId, String title, String text,
            LocalDateTime postedAt, String url) {
        this.userId = course.getUserId();
        this.course = course;
        this.bbId = bbId;
        this.title = title;
        this.text = text;
        this.postedAt = postedAt;
        this.url = url;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public SchoolBbCourse getCourse() {
        return course;
    }

    public String getBbId() {
        return bbId;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public LocalDateTime getPostedAt() {
        return postedAt;
    }

    public String getUrl() {
        return url;
    }
}
