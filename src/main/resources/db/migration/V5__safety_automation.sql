-- The switches of the reactions to a fire alarm (HAS-468), one row: lights_on - the lights of the room of the detector
-- and of both halls switched on; blinds_up - the blinds of the room of the detector opened; aircon_off - every air
-- conditioner that is on switched off. All off until the administrator switches one on; updated_at - the last write
CREATE TABLE safety_automation
(
    id         SMALLINT PRIMARY KEY CHECK (id = 1),
    lights_on  BOOLEAN                  NOT NULL DEFAULT FALSE,
    blinds_up  BOOLEAN                  NOT NULL DEFAULT FALSE,
    aircon_off BOOLEAN                  NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE
);

INSERT INTO safety_automation (id) VALUES (1);
