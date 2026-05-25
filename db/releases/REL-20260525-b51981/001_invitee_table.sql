CREATE TABLE emp_test.invitee (
    inviteeid BIGSERIAL PRIMARY KEY,
    meetingid BIGINT NOT NULL,
    inviteename VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    createtime TIMESTAMPTZ NOT NULL DEFAULT now(),
    lastmodifiedtime TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION emp_test.trigger_set_lastmodifiedtime()
RETURNS TRIGGER AS $$
BEGIN
    NEW.lastmodifiedtime = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_invitee_set_lastmodifiedtime
    BEFORE UPDATE ON emp_test.invitee
    FOR EACH ROW
    EXECUTE FUNCTION emp_test.trigger_set_lastmodifiedtime();

CREATE INDEX idx_invitee_meetingid ON emp_test.invitee (meetingid);