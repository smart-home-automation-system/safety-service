-- The reactions sent at a fire alarm (HAS-468), a row for each call: episode - the id of the detector_alarm it reacted
-- to, null when the episode was not stored; reaction - lightsOn, blindsUp or airconOff (the words of the switches);
-- target - the room or the id of the air conditioner, null when the units could not be read; sent_at - when it was
-- sent; outcome - sent (the service took it), none (a room without lights or blinds, a 404) or failed
CREATE TABLE safety_reaction_run
(
    id       BIGSERIAL PRIMARY KEY,
    episode  BIGINT,
    reaction VARCHAR(20)              NOT NULL CHECK (reaction IN ('lightsOn', 'blindsUp', 'airconOff')),
    target   VARCHAR(40),
    sent_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome  VARCHAR(10)              NOT NULL CHECK (outcome IN ('sent', 'none', 'failed'))
);

-- the read of GET /automation/runs by period
CREATE INDEX safety_reaction_run_sent_at_idx ON safety_reaction_run (sent_at);
