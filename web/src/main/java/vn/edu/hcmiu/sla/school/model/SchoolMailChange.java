package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A class change a lecturer's email announces: online, cancelled or make-up. Day and times are Vietnam time. */
@Entity
@Table(name = "school_mail_changes")
public class SchoolMailChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_id", nullable = false)
    private SchoolMail mail;

    @Column(name = "course_code", nullable = false, length = 20)
    private String courseCode;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "change_day", nullable = false)
    private LocalDate day;

    @Column(name = "start_time")
    private LocalTime start;

    @Column(name = "end_time")
    private LocalTime end;

    @Column(length = 50)
    private String room;

    protected SchoolMailChange() {
    }

    public SchoolMailChange(SchoolMail mail, String courseCode, String kind, LocalDate day, LocalTime start,
            LocalTime end, String room) {
        this.mail = mail;
        this.courseCode = courseCode;
        this.kind = kind;
        this.day = day;
        this.start = start;
        this.end = end;
        this.room = room;
    }

    public Integer getId() {
        return id;
    }

    public SchoolMail getMail() {
        return mail;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getKind() {
        return kind;
    }

    public LocalDate getDay() {
        return day;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public String getRoom() {
        return room;
    }
}
