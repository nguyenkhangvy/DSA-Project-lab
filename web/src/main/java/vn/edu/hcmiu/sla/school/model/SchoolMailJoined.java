package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An event session the student joined from Mailbox. Day and times are Vietnam time; end may be empty. The
 * email's subject is copied in as the title, so it stays in the Timetable even when the email is gone.
 */
@Entity
@Table(name = "school_mail_joined")
public class SchoolMailJoined {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "mail_key", nullable = false, length = 64)
    private String mailKey;

    @Column(name = "session_day", nullable = false)
    private LocalDate day;

    @Column(name = "start_time", nullable = false)
    private LocalTime start;

    @Column(name = "end_time")
    private LocalTime end;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 100)
    private String place;

    @Column(name = "training_points", nullable = false)
    private boolean trainingPoints;

    @Column(name = "by_hand", nullable = false)
    private boolean byHand;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // UTC

    protected SchoolMailJoined() {
    }

    public SchoolMailJoined(Integer userId, String mailKey, LocalDate day, LocalTime start, LocalTime end, String title,
            String place, boolean trainingPoints, boolean byHand, LocalDateTime createdAt) {
        this.userId = userId;
        this.mailKey = mailKey;
        this.day = day;
        this.start = start;
        this.end = end;
        this.title = title;
        this.place = place;
        this.trainingPoints = trainingPoints;
        this.byHand = byHand;
        this.createdAt = createdAt;
    }

    /** A new, unsaved row with the same values, to save again after a try that failed. */
    public SchoolMailJoined copy() {
        return new SchoolMailJoined(userId, mailKey, day, start, end, title, place, trainingPoints, byHand, createdAt);
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getMailKey() {
        return mailKey;
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

    public String getTitle() {
        return title;
    }

    public String getPlace() {
        return place;
    }

    public boolean isTrainingPoints() {
        return trainingPoints;
    }

    public boolean isByHand() {
        return byHand;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
