-- Mailbox, round two: the times an email's event or school task takes place, as the laptop found them,
-- and no more "lose points" (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 3.3 and 4.1).
ALTER TABLE school_mail DROP COLUMN loses_points;

CREATE TABLE school_mail_sessions (
    id INT NOT NULL AUTO_INCREMENT,
    mail_id INT NOT NULL,
    session_day DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_mail_sessions_mail FOREIGN KEY (mail_id) REFERENCES school_mail (id) ON DELETE CASCADE
);
