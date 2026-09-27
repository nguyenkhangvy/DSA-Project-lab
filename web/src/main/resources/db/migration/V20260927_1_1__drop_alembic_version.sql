-- The Python site is gone, and with it Alembic, its migration tool. Flyway keeps the tables up to date now.
DROP TABLE IF EXISTS alembic_version;
