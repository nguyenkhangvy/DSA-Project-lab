package vn.edu.hcmiu.sla.school.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The student's Mailbox setting: whether opening an email marks it Done. No row means it does. */
@Entity
@Table(name = "school_mail_settings")
public class SchoolMailSettings {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "auto_done", nullable = false)
    private boolean autoDone;

    protected SchoolMailSettings() {
    }

    public SchoolMailSettings(Integer userId, boolean autoDone) {
        this.userId = userId;
        this.autoDone = autoDone;
    }

    public Integer getUserId() {
        return userId;
    }

    public boolean isAutoDone() {
        return autoDone;
    }

    public void setAutoDone(boolean autoDone) {
        this.autoDone = autoDone;
    }
}
