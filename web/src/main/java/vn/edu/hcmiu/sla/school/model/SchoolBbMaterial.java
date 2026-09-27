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

/** A file, folder, link or page in a Blackboard course's content. */
@Entity
@Table(name = "school_bb_materials")
public class SchoolBbMaterial {

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

    @Column(nullable = false, length = 10)
    private String kind; // file / folder / link / document / other

    @Column(nullable = false, length = 500)
    private String path; // the folders it is in, e.g. "Week 5"

    @Column(name = "created_at")
    private LocalDateTime createdAt; // UTC

    @Column(nullable = false, length = 500)
    private String url;

    protected SchoolBbMaterial() {
    }

    public SchoolBbMaterial(SchoolBbCourse course, String bbId, String title, String kind, String path,
            LocalDateTime createdAt, String url) {
        this.userId = course.getUserId();
        this.course = course;
        this.bbId = bbId;
        this.title = title;
        this.kind = kind;
        this.path = path;
        this.createdAt = createdAt;
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

    public String getKind() {
        return kind;
    }

    public String getPath() {
        return path;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getUrl() {
        return url;
    }
}
