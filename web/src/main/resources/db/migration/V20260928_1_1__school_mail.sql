-- Mailbox: what the laptop found in each email, never its text
-- (docs/superpowers/specs/2026-09-28-outlook-mailbox-design.md, section 6.1).
CREATE TABLE school_mail (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    mail_key VARCHAR(64) NOT NULL,
    entry_id VARCHAR(512) NOT NULL,
    thread_id VARCHAR(64) NULL,
    received_at DATETIME NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    sender_address VARCHAR(255) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    categories VARCHAR(100) NOT NULL,
    from_lecturer BOOLEAN NOT NULL,
    dates VARCHAR(400) NOT NULL,
    loses_points BOOLEAN NOT NULL,
    is_sorted BOOLEAN NOT NULL,
    blackboard_title VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, mail_key),
    CONSTRAINT fk_school_mail_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE school_mail_changes (
    id INT NOT NULL AUTO_INCREMENT,
    mail_id INT NOT NULL,
    course_code VARCHAR(20) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    change_day DATE NOT NULL,
    start_time TIME NULL,
    end_time TIME NULL,
    room VARCHAR(50) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_mail_changes_mail FOREIGN KEY (mail_id) REFERENCES school_mail (id) ON DELETE CASCADE
);

-- The student's Done and Move to... choices. They outlive a sync, which replaces school_mail.
CREATE TABLE school_mail_choices (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    mail_key VARCHAR(64) NOT NULL,
    done BOOLEAN NOT NULL,
    categories VARCHAR(100) NULL,
    from_lecturer BOOLEAN NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, mail_key),
    CONSTRAINT fk_school_mail_choices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE school_mail_status (
    user_id INT NOT NULL,
    since DATE NOT NULL,
    connected BOOLEAN NOT NULL,
    synced_at DATETIME NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_mail_status_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
