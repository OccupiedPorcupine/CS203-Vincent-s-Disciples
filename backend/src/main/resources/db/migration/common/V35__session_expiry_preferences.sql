ALTER TABLE app_users ADD COLUMN session_idle_minutes INTEGER NOT NULL DEFAULT 60;
ALTER TABLE app_users ADD COLUMN session_absolute_minutes INTEGER NOT NULL DEFAULT 480;
ALTER TABLE app_users ADD CONSTRAINT session_timeout_bounds CHECK (
    session_idle_minutes BETWEEN 5 AND 1440
    AND session_absolute_minutes BETWEEN 60 AND 10080
    AND session_idle_minutes <= session_absolute_minutes
);
