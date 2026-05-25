-- Create schema if not exists
CREATE SCHEMA IF NOT EXISTS emp_test;

-- Create meeting table
CREATE TABLE emp_test.meeting (
  meetingid         BIGSERIAL PRIMARY KEY,
  meetingname       VARCHAR(32) NOT NULL,
  host_id           BIGINT NOT NULL,
  status            VARCHAR(20) NOT NULL,
  starttime         TIMESTAMP WITHOUT TIME ZONE,
  endtime           TIMESTAMP WITHOUT TIME ZONE,
  meetinglink       VARCHAR(500),
  createtime        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  lastmodifiedtime  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Add unique constraint on meetingname
ALTER TABLE emp_test.meeting ADD CONSTRAINT uk_meeting_meetingname UNIQUE (meetingname);

-- Create trigger function to update lastmodifiedtime
CREATE OR REPLACE FUNCTION emp_test.trigger_set_lastmodifiedtime()
RETURNS TRIGGER AS $$
BEGIN
  NEW.lastmodifiedtime = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger on meeting table
CREATE TRIGGER trigger_meeting_set_lastmodifiedtime
  BEFORE UPDATE ON emp_test.meeting
  FOR EACH ROW
  EXECUTE FUNCTION emp_test.trigger_set_lastmodifiedtime();

-- Create index for query scenario: host_id + status + starttime DESC
CREATE INDEX idx_meeting_host_status_starttime ON emp_test.meeting (host_id, status, starttime DESC);