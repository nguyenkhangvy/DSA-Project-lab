package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One exam from EduSoft's exam schedule. The start time is UTC. */
@Entity
@Table(name = "school_exams")
public class SchoolExam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "term_code", nullable = false, length = 20)
    private String termCode;

    @Column(name = "course_code", nullable = false, length = 20)
    private String courseCode;

    @Column(name = "course_name", nullable = false, length = 255)
    private String courseName;

    @Column(name = "exam_type", nullable = false, length = 10)
    private String examType; // midterm / final / other

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(length = 50)
    private String room;

    @Column(length = 500)
    private String notes;

    protected SchoolExam() {
    }

    public SchoolExam(Integer userId, String termCode, String courseCode, String courseName, String examType,
            LocalDateTime startAt, Integer durationMin, String room, String notes) {
        this.userId = userId;
        this.termCode = termCode;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.examType = examType;
        this.startAt = startAt;
        this.durationMin = durationMin;
        this.room = room;
        this.notes = notes;
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

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getExamType() {
        return examType;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public Integer getDurationMin() {
        return durationMin;
    }

    public String getRoom() {
        return room;
    }

    public String getNotes() {
        return notes;
    }
}
