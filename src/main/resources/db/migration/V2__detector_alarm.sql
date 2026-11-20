-- The fire alarm episodes (HAS-461): a smokealarm Detected of a detector opens one, its Cleared closes it. A Detected of a
-- detector with an open episode is no new one - the partial unique index keeps one open episode per detector, so two
-- threads of the listener can never open two. Both moments are told to the household once (fire alarm, fire alarm
-- over); the marks say whether the broker took the message.

-- detector_id - the registry id; room - the room of the registry when it opened (a detector moved later keeps the room
-- of its alarm); started_at / cleared_at - when the service took the Detected / the Cleared, cleared_at null while it
-- lasts; alarm_told_at / over_told_at - when the broker confirmed the fire alarm / the fire alarm over, null when it
-- could not be told
CREATE TABLE detector_alarm
(
    id            BIGSERIAL PRIMARY KEY,
    detector_id   BIGINT                   NOT NULL,
    room          VARCHAR(30),
    started_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    cleared_at    TIMESTAMP WITH TIME ZONE,
    alarm_told_at TIMESTAMP WITH TIME ZONE,
    over_told_at  TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX detector_alarm_open_uq ON detector_alarm (detector_id) WHERE cleared_at IS NULL;

-- the reads of GET /alarms by period
CREATE INDEX detector_alarm_started_at_idx ON detector_alarm (started_at);
