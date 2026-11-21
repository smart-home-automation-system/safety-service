-- The commands sent to a detector (HAS-464), one row for each command sent: detector_id - the registry id;
-- command - mute or self test; source - who asked (dashboard, manual); request_id - the requestId of the payload the
-- base got (sh-<uuid>); sent_at - when it was sent; confirmed_at - when the base answered it (a mute by muted Muted, a
-- self test by a message of its self test), NULL while it has not or never did. A command that could not be sent is
-- not stored - its caller got the error
CREATE TABLE safety_command
(
    id           BIGSERIAL PRIMARY KEY,
    detector_id  BIGINT                   NOT NULL,
    command      VARCHAR(20)              NOT NULL CHECK (command IN ('mute', 'self test')),
    source       VARCHAR(20)              NOT NULL,
    request_id   VARCHAR(40)              NOT NULL UNIQUE,
    sent_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE
);

-- the read of GET /{id}/commands: the last commands of a detector
CREATE INDEX safety_command_detector_sent_at_idx ON safety_command (detector_id, sent_at DESC);
