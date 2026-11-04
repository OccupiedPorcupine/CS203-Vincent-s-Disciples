-- Messages a linked chat gets without asking (issue #101): a reminder to enter the day's sales,
-- and tomorrow's prep list. Each is off while its time is null; times are Singapore time, on the
-- hour. *_sent_on is the last day it was sent (or skipped, e.g. because the sales were in), so a
-- repeated run of the job sends it once a day.
ALTER TABLE chat_links ADD COLUMN sales_reminder_at TIME;
ALTER TABLE chat_links ADD COLUMN prep_list_at TIME;
ALTER TABLE chat_links ADD COLUMN sales_reminder_sent_on DATE;
ALTER TABLE chat_links ADD COLUMN prep_list_sent_on DATE;
