-- Mailbox, round two: the event sessions the student joined. A sync never touches them, so they stay even when
-- their email is gone (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.1 and 4.6).
CREATE TABLE school_mail_joined (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    mail_key VARCHAR(64) NOT NULL,
    session_day DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NULL,
    title VARCHAR(500) NOT NULL,
    place VARCHAR(100) NULL,
    training_points BOOLEAN NOT NULL,
    by_hand BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, mail_key, session_day, start_time),
    CONSTRAINT fk_school_mail_joined_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
