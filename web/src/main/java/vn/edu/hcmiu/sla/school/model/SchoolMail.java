package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * One email in the student's Inbox, as the laptop sorted it: never its text. Each sync replaces all of a
 * user's rows; the student's choices are kept apart, in {@link SchoolMailChoice}.
 */
@Entity
@Table(name = "school_mail")
public class SchoolMail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "mail_key", nullable = false, length = 64)
    private String mailKey;

    @Column(name = "entry_id", nullable = false, length = 512)
    private String entryId;

    @Column(name = "thread_id", length = 64)
    private String threadId;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt; // UTC

    @Column(name = "sender_name", nullable = false, length = 255)
    private String senderName;

    @Column(name = "sender_address", nullable = false, length = 255)
    private String senderAddress;

    @Column(nullable = false, length = 500)
    private String subject;

    @Convert(converter = CommaLists.Words.class)
    @Column(nullable = false, length = 100)
    private List<String> categories;

    @Column(name = "from_lecturer", nullable = false)
    private boolean fromLecturer;

    @Convert(converter = CommaLists.Dates.class)
    @Column(nullable = false, length = 400)
    private List<LocalDate> dates;

    @Column(name = "is_sorted", nullable = false)
    private boolean sorted;

    @Column(name = "blackboard_title", length = 255)
    private String blackboardTitle;

    @Column(name = "register_by")
    private LocalDate registerBy; // the registration deadline (Vietnam date), or null

    @OneToMany(mappedBy = "mail", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolMailChange> changes = new ArrayList<>();

    @OneToMany(mappedBy = "mail", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("day, start")
    private List<SchoolMailSession> sessions = new ArrayList<>();

    protected SchoolMail() {
    }

    public SchoolMail(Integer userId, String mailKey, String entryId, String threadId, LocalDateTime receivedAt,
            String senderName, String senderAddress, String subject, List<String> categories, boolean fromLecturer,
            List<LocalDate> dates, boolean sorted, String blackboardTitle) {
        this.userId = userId;
        this.mailKey = mailKey;
        this.entryId = entryId;
        this.threadId = threadId;
        this.receivedAt = receivedAt;
        this.senderName = senderName;
        this.senderAddress = senderAddress;
        this.subject = subject;
        this.categories = categories;
        this.fromLecturer = fromLecturer;
        this.dates = dates;
        this.sorted = sorted;
        this.blackboardTitle = blackboardTitle;
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

    public String getEntryId() {
        return entryId;
    }

    public String getThreadId() {
        return threadId;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getSenderAddress() {
        return senderAddress;
    }

    public String getSubject() {
        return subject;
    }

    public List<String> getCategories() {
        return categories;
    }

    public boolean isFromLecturer() {
        return fromLecturer;
    }

    public List<LocalDate> getDates() {
        return dates;
    }

    public boolean isSorted() {
        return sorted;
    }

    public String getBlackboardTitle() {
        return blackboardTitle;
    }

    public LocalDate getRegisterBy() {
        return registerBy;
    }

    public void setRegisterBy(LocalDate registerBy) {
        this.registerBy = registerBy;
    }

    public List<SchoolMailChange> getChanges() {
        return changes;
    }

    public List<SchoolMailSession> getSessions() {
        return sessions;
    }
}
