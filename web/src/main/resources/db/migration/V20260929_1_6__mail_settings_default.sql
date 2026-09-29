-- Mailbox: auto-Done is on unless the student turned it off, so a settings row written without it means on, like no
-- row (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.1). A new migration: V20260928_1_3 has already
-- run on the student's database, and changing it would fail Flyway's check.
ALTER TABLE school_mail_settings ALTER COLUMN auto_done SET DEFAULT TRUE;
