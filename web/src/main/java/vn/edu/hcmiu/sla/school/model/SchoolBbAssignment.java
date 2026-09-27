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

/** An assignment in a Blackboard course, with its grade once there is one. */
@Entity
@Table(name = "school_bb_assignments")
public class SchoolBbAssignment {

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
    private String name;

    @Column(name = "due_at")
    private LocalDateTime dueAt; // UTC

    // DOUBLE: MySQL's FLOAT keeps about 7 digits, so a score like 6.666666667 would change on every read.
    @Column(name = "points_possible")
    private Double pointsPossible;

    private Double score;

    @Column(name = "grade_text", length = 50)
    private String gradeText;

    @Column(nullable = false, length = 20)
    private String status; // not_graded / needs_grading / graded / exempt

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(nullable = false, length = 500)
    private String url;

    protected SchoolBbAssignment() {
    }

    public SchoolBbAssignment(SchoolBbCourse course, String bbId, String name, LocalDateTime dueAt,
            Double pointsPossible, Double score, String gradeText, String status, String feedback, String url) {
        this.userId = course.getUserId();
        this.course = course;
        this.bbId = bbId;
        this.name = name;
        this.dueAt = dueAt;
        this.pointsPossible = pointsPossible;
        this.score = score;
        this.gradeText = gradeText;
        this.status = status;
        this.feedback = feedback;
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

    public String getName() {
        return name;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public Double getPointsPossible() {
        return pointsPossible;
    }

    public Double getScore() {
        return score;
    }

    public String getGradeText() {
        return gradeText;
    }

    public String getStatus() {
        return status;
    }

    public String getFeedback() {
        return feedback;
    }

    public String getUrl() {
        return url;
    }
}
