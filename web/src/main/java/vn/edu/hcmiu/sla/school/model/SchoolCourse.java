package vn.edu.hcmiu.sla.school.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** One course of a term, from EduSoft's timetable. */
@Entity
@Table(name = "school_courses")
public class SchoolCourse {

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

    @Column(name = "group_code", length = 20)
    private String groupCode;

    @Column(precision = 4, scale = 1)
    private BigDecimal credits;

    @Column(length = 255)
    private String lecturer;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolClassMeeting> meetings = new ArrayList<>();

    protected SchoolCourse() {
    }

    public SchoolCourse(Integer userId, String termCode, String courseCode, String courseName, String groupCode,
            BigDecimal credits, String lecturer) {
        this.userId = userId;
        this.termCode = termCode;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.groupCode = groupCode;
        this.credits = credits;
        this.lecturer = lecturer;
    }

    /** Times are UTC. */
    public void addMeeting(LocalDateTime startAt, LocalDateTime endAt, String room) {
        meetings.add(new SchoolClassMeeting(userId, this, startAt, endAt, room));
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

    public String getGroupCode() {
        return groupCode;
    }

    public BigDecimal getCredits() {
        return credits;
    }

    public String getLecturer() {
        return lecturer;
    }

    public List<SchoolClassMeeting> getMeetings() {
        return meetings;
    }
}
