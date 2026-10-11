-- Paging through one employee's punches orders by time and then id, and
-- resumes with (punched_at_utc, id) > (last seen). With the id in the index the
-- next page is found by position, without reading the rows before it.
DROP INDEX ix_punch_employee_time;

CREATE INDEX ix_punch_employee_time ON punch (employee_id, punched_at_utc, id);
