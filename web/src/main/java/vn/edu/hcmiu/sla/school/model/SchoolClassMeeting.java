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

/** One real class session (EduSoft's week pattern expanded to dates). Times are UTC. */
@Entity
@Table(name = "school_class_meetings")
public class SchoolClassMeeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private SchoolCourse course;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(length = 50)
    private String room;

    protected SchoolClassMeeting() {
    }

    SchoolClassMeeting(Integer userId, SchoolCourse course, LocalDateTime startAt, LocalDateTime endAt, String room) {
        this.userId = userId;
        this.course = course;
        this.startAt = startAt;
        this.endAt = endAt;
        this.room = room;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public SchoolCourse getCourse() {
        return course;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public String getRoom() {
        return room;
    }
}
