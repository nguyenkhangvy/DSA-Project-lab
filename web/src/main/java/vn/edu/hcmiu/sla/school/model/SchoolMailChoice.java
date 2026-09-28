package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * What the student chose for one email in Mailbox: Done, and Move to… (categories and "from a lecturer";
 * null means the laptop's sorting stands). Only the app changes; the real mailbox never does.
 */
@Entity
@Table(name = "school_mail_choices")
public class SchoolMailChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "mail_key", nullable = false, length = 64)
    private String mailKey;

    @Column(nullable = false)
    private boolean done;

    @Column(nullable = false)
    private boolean opened;

    @Convert(converter = CommaLists.Words.class)
    @Column(length = 100)
    private List<String> categories;

    @Column(name = "from_lecturer")
    private Boolean fromLecturer;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt; // UTC

    protected SchoolMailChoice() {
    }

    public SchoolMailChoice(Integer userId, String mailKey, LocalDateTime updatedAt) {
        this.userId = userId;
        this.mailKey = mailKey;
        this.updatedAt = updatedAt;
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

    public boolean isDone() {
        return done;
    }

    /** Whether the student opened this email from Mailbox (its subject or "Web ↗"). */
    public boolean isOpened() {
        return opened;
    }

    public List<String> getCategories() {
        return categories;
    }

    public Boolean getFromLecturer() {
        return fromLecturer;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Whether Move to… chose anything for this email. */
    public boolean isMoved() {
        return categories != null || fromLecturer != null;
    }

    public void setDone(boolean done, LocalDateTime now) {
        this.done = done;
        this.updatedAt = now;
    }

    public void open(LocalDateTime now) {
        this.opened = true;
        this.updatedAt = now;
    }

    public void move(List<String> categories, boolean fromLecturer, LocalDateTime now) {
        this.categories = categories;
        this.fromLecturer = fromLecturer;
        this.updatedAt = now;
    }

    public void backToAutomatic(LocalDateTime now) {
        this.categories = null;
        this.fromLecturer = null;
        this.updatedAt = now;
    }
}
