package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A day the student took out of one of their events (a Vietnam date). */
@Entity
@Table(name = "school_my_event_skips")
public class SchoolMyEventSkip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private SchoolMyEvent event;

    @Column(name = "skip_day", nullable = false)
    private LocalDate day;

    protected SchoolMyEventSkip() {
    }

    SchoolMyEventSkip(SchoolMyEvent event, LocalDate day) {
        this.event = event;
        this.day = day;
    }

    public Integer getId() {
        return id;
    }

    public LocalDate getDay() {
        return day;
    }
}
