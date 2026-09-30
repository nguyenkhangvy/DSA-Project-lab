package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import vn.edu.hcmiu.sla.school.events.Details;
import vn.edu.hcmiu.sla.school.events.Occurrences;
import vn.edu.hcmiu.sla.school.events.Occurrences.Rule;

/**
 * One of the student's own events and its repeat rule (docs/superpowers/specs/2026-09-30-my-events-design.md, 3.1).
 * Days and times are Vietnam time; the days it happens on come from {@link Occurrences}.
 */
@Entity
@Table(name = "school_my_events")
public class SchoolMyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 100)
    private String place;

    @Column(length = 500)
    private String notes;

    @Column(name = "first_day", nullable = false)
    private LocalDate firstDay;

    @Column(name = "last_day", nullable = false)
    private LocalDate lastDay;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "repeat_kind", nullable = false, length = 6)
    private String repeatKind;

    @Column(name = "every_n", nullable = false)
    private int everyN;

    @Column(length = 20)
    private String weekdays; // "1,2,3" for Mon, Tue, Wed; weekly events only

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // UTC

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt; // UTC

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("day")
    private List<SchoolMyEventSkip> skips = new ArrayList<>();

    protected SchoolMyEvent() {
    }

    public SchoolMyEvent(Integer userId, LocalDateTime now) {
        this.userId = userId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Sets what the form holds. Skipped days that are no longer days of the new rule are dropped. */
    public void set(Details details, LocalDateTime now) {
        Rule rule = details.rule();
        this.title = details.title();
        this.place = details.place();
        this.notes = details.notes();
        this.firstDay = rule.first();
        this.lastDay = rule.last();
        this.repeatKind = rule.repeat();
        this.everyN = rule.every();
        this.weekdays = Occurrences.WEEKS.equals(rule.repeat()) ? Occurrences.weekdayNumbers(rule.weekdays()) : null;
        this.startTime = details.start();
        this.endTime = details.end();
        this.updatedAt = now;
        Rule stored = rule();
        skips.removeIf(skip -> !Occurrences.falls(stored, skip.getDay()));
    }

    /** The repeat rule, with the skipped days. */
    public Rule rule() {
        return new Rule(firstDay, lastDay, repeatKind, everyN, Occurrences.weekdaysOf(weekdays),
                skips.stream().map(SchoolMyEventSkip::getDay).collect(Collectors.toSet()));
    }

    public Details details() {
        return new Details(title, place, notes, rule(), startTime, endTime);
    }

    public boolean isSkipped(LocalDate day) {
        return skips.stream().anyMatch(skip -> skip.getDay().equals(day));
    }

    /** Skips this day; false when it already was. */
    public boolean skip(LocalDate day) {
        if (isSkipped(day)) {
            return false;
        }
        skips.add(new SchoolMyEventSkip(this, day));
        return true;
    }

    /** Brings this day back; false when it wasn't skipped. */
    public boolean unskip(LocalDate day) {
        return skips.removeIf(skip -> skip.getDay().equals(day));
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getPlace() {
        return place;
    }

    public String getNotes() {
        return notes;
    }

    public LocalDate getFirstDay() {
        return firstDay;
    }

    public LocalDate getLastDay() {
        return lastDay;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getRepeatKind() {
        return repeatKind;
    }

    public int getEveryN() {
        return everyN;
    }

    public String getWeekdays() {
        return weekdays;
    }

    public List<SchoolMyEventSkip> getSkips() {
        return skips;
    }
}
