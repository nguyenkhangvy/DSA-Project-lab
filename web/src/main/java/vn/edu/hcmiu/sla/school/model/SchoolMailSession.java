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

/**
 * One time an email's event or school task takes place, as the laptop found it in the email. Day and times are
 * Vietnam time; end may be empty. Replaced with its email at every sync.
 */
@Entity
@Table(name = "school_mail_sessions")
public class SchoolMailSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_id", nullable = false)
    private SchoolMail mail;

    @Column(name = "session_day", nullable = false)
    private LocalDate day;

    @Column(name = "start_time", nullable = false)
    private LocalTime start;

    @Column(name = "end_time")
    private LocalTime end;

    protected SchoolMailSession() {
    }

    public SchoolMailSession(SchoolMail mail, LocalDate day, LocalTime start, LocalTime end) {
        this.mail = mail;
        this.day = day;
        this.start = start;
        this.end = end;
    }

    public Integer getId() {
        return id;
    }

    public SchoolMail getMail() {
        return mail;
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
}
