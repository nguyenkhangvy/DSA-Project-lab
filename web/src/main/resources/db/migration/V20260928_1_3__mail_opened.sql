-- Mailbox, round two: the emails the student opened from Mailbox, and whether opening one marks it Done
-- (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.1 and 4.3).
ALTER TABLE school_mail_choices ADD COLUMN opened BOOLEAN NOT NULL DEFAULT FALSE;

-- No row means auto-Done is on.
CREATE TABLE school_mail_settings (
    user_id INT NOT NULL,
    auto_done BOOLEAN NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_mail_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
