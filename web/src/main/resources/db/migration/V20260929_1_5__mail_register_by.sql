-- Mailbox: each email's registration deadline, as the laptop found it
-- (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, addendum A.2 and A.3).
ALTER TABLE school_mail ADD COLUMN register_by DATE NULL;
