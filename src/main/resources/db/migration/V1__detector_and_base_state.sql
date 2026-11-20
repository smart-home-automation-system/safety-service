-- The state of the fire detectors and of their base station (HAS-460), so that a restart keeps what the service knew.
-- The base publishes a value on a change only (and when asked to republish), so a restart without these rows would know
-- nothing until the next birth message. Both tables hold one row per thing, replaced on a change of a value only, never
-- on a message that repeats it; read back at the start as not confirmed - the next message of the base confirms them.
-- An older snapshot never replaces a newer one: updated_at is the time the service changed it in memory, and the
-- listener writes with a prefetch of 3, so two writes of one detector may arrive out of order.

-- detector_id - the registry id; per flag its value and when it took it: alarm (smokealarm Detected), online (online
-- Online), battery_low (battery Low), end_of_life (lifeend EOL), fault (smokefault Fault), muted (muted Muted),
-- self_test_problem (self_test problem); model and firmware from the device block of its discovery; last_heard_at - the
-- last message of the detector the service took; updated_at - when the snapshot was taken. A null is not known
CREATE TABLE detector_state
(
    detector_id                  BIGINT PRIMARY KEY       NOT NULL,
    alarm                        BOOLEAN,
    alarm_changed_at             TIMESTAMP WITH TIME ZONE,
    online                       BOOLEAN,
    online_changed_at            TIMESTAMP WITH TIME ZONE,
    battery_low                  BOOLEAN,
    battery_low_changed_at       TIMESTAMP WITH TIME ZONE,
    end_of_life                  BOOLEAN,
    end_of_life_changed_at       TIMESTAMP WITH TIME ZONE,
    fault                        BOOLEAN,
    fault_changed_at             TIMESTAMP WITH TIME ZONE,
    muted                        BOOLEAN,
    muted_changed_at             TIMESTAMP WITH TIME ZONE,
    self_test_problem            BOOLEAN,
    self_test_problem_changed_at TIMESTAMP WITH TIME ZONE,
    model                        VARCHAR(50),
    firmware                     VARCHAR(50),
    last_heard_at                TIMESTAMP WITH TIME ZONE,
    updated_at                   TIMESTAMP WITH TIME ZONE NOT NULL
);

-- station_serial - the serial of the base as its topics give it; online - its availability (online / offline, offline
-- also the last will the broker publishes when it drops); ip_address, wifi_ssid, wifi_rssi from its network state
CREATE TABLE base_state
(
    station_serial    VARCHAR(20) PRIMARY KEY  NOT NULL,
    online            BOOLEAN,
    online_changed_at TIMESTAMP WITH TIME ZONE,
    ip_address        VARCHAR(45),
    wifi_ssid         VARCHAR(64),
    wifi_rssi         INTEGER,
    last_heard_at     TIMESTAMP WITH TIME ZONE,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
