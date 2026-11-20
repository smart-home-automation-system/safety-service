-- The alerts of the technical monitor (HAS-463): a detector offline, low on battery, at its end of life or faulty, a base
-- station offline or silent - one row per episode, kept here, not in memory, so that a restart neither repeats an alert
-- nor forgets to announce a return (the shape of gate_availability_alert of gate-service, with the closed episodes kept).
-- item is the key of what is watched (offline:151, battery:151, base-silent:SBS50...), kind and device its two parts,
-- name how the messages name it; opened_at since when it is out; told - whether the alert got through: a row is written
-- when the alert is due and marked told once the broker took it, so that an alert that never got through is still
-- announced with the return; last_reminded_at the alert or the last reminder told; closed_at the return, or the device
-- left the registry. One open episode per item (the partial unique index)
CREATE TABLE safety_alert
(
    id               BIGSERIAL PRIMARY KEY,
    item             VARCHAR(60)              NOT NULL,
    kind             VARCHAR(20)              NOT NULL,
    device           VARCHAR(30)              NOT NULL,
    name             VARCHAR(200)             NOT NULL,
    opened_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    told             BOOLEAN                  NOT NULL,
    last_reminded_at TIMESTAMP WITH TIME ZONE,
    closed_at        TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX safety_alert_open_uq ON safety_alert (item) WHERE closed_at IS NULL;
