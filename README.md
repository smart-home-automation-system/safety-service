# safety-service

[![CI](https://github.com/smart-home-automation-system/safety-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/safety-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_safety-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_safety-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_safety-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_safety-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/safety-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/safety-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/safety-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.3-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_safety-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_safety-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_safety-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_safety-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/safety-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/safety-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/safety-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/safety-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/safety-service?style=plastic)

---

# Description

The fire detectors of the house (epic HAS-455): the state of every X-Sense detector (Link+ Pro, paired with an SBS50
base station) from what its base publishes to the broker over MQTT - the Home Assistant discovery of the base, user
`xsense` of the broker. The base has no local API; this service never talks to it except through the broker.

- **The detectors** come from the device registry of database-service - the kinds `smoke detector`, `co detector` and
  `heat detector`, switched on, each with its `xsense` binding (the serial of its base and the id the base gave it) -
  read at the start and every 5 minutes. A message of a detector the registry does not list is dropped.
- **Their state** from the topics of the base on `device.prod.safety` of the vhost `/device` (user `device`), every
  other message of the queue dropped: the alarm, online, a low battery, the end of life, a fault, muted (each its
  `{"status": ...}` word) and the self test (`{"problem": ...}`), the model and firmware from its discovery; for the
  base its availability (online / offline, also the last will of the broker) and its network (address, Wi-Fi, RSSI).
  Each value with the moment it took it.
- **The birth message of Home Assistant**: `online` on `homeassistant/status` once the registry is first read and then
  every 10 minutes (`safety.base.resync-every`, since HAS-463) - the base answers by republishing the state of every detector, so a restart
  of the service or of the base loses nothing.
- **Kept across a restart**: `detector_state` and `base_state` hold the last state of every detector and base, written
  on a change only, never on a message that repeats a value, and read back at the start as not confirmed until the
  next message of the base.
- **A fire alarm** (HAS-461): a `smokealarm` Detected opens an episode in `detector_alarm`, its Cleared closes it; a
  Detected of a detector with an alarm open is no new one, a Cleared without one is ignored (the base republishes the
  state at every resync). Each episode tells the household twice - `fire alarm` when it opens, `fire alarm over` when it
  closes, the detector of the registry the subject, its room the room - through the `household` category of the
  notification exchange (notification-service >= 0.14.0 words it in Polish, texts and pushes it to every member at
  home or not). Never a reminder: the detectors sound themselves. The household is told whatever the database says
  (it waits 5 s for the row at most); the episode records when the broker took each message.
- **The technical monitor** (HAS-463): every minute a detector offline, low on battery, at its end of life or faulty
  (the state its base reports, at once), and a base offline (its availability, or the last will the broker publishes -
  back once anything comes from it again) or silent (nothing from it nor its detectors for `safety.base.silent-after`,
  30 min; the base is asked to republish every `safety.base.resync-every`, 10 min) are told on the technical channel -
  one alert a pass naming everything, a reminder every `safety.monitor.reminder-every` (1 h), one info on the return; an
  alert that never got through is announced with the return. Never the household, never an SMS. The episodes are kept
  in `safety_alert` (V3), so a restart repeats no alert.
- **The mute and the self test of a detector** (HAS-464): one command per detector a minute, published once to the
  topic the base subscribes to for that detector - `xsense/sbs50/<station>/device/<id>/cmd` (the routing key
  `xsense.sbs50.<station>.device.<id>.cmd` of `amq.topic` in `/device`), QoS 1, never retained - with the JSON the
  base announces in the configs of its buttons, `{"requestId": "sh-<uuid>", "cmd": "mute" | "self_test", "params": {}}`.
  Never repeated. The base answers by the state of the detector: a mute is confirmed by `muted` Muted, a self test by a
  message of its self test, each within 60 s; the third unconfirmed command of a detector in a row is an alert on the
  technical channel. Every command sent is kept in `safety_command` (V4); a self test the base dates (its `time`) answers only a test sent before it.
  A mute is sent only to a detector in alarm (`409 NOTHING_TO_MUTE` otherwise): the base takes a mute of a quiet
  detector and does nothing (tried on the real base on 2026-11-21).

# API

Base path `/home/safety`. Times in the time of the house (`2026-11-20T11:00:00`); a field not known is left out, never
a default.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/` | `detectors` - every fire detector of the registry by id: `id`, `name`, `room`, `kind`, `stationSerial`, `deviceId`, then what its base said - `alarm`, `online`, `batteryLow`, `endOfLife`, `fault`, `muted`, `selfTestProblem` (each `{value, since}`), `model`, `firmware`, `lastHeardAt`, `confirmed` (false: read back at the start, not confirmed by the base since); `bases` - every base heard by serial: `stationSerial`, `online` `{value, since}`, `ipAddress`, `wifiSsid`, `wifiRssi`, `lastHeardAt`, `confirmed`. `503 REGISTRY_UNAVAILABLE` before the first read of the registry. |
| `GET` | `/alarms?from=&to=` | The fire alarm episodes that overlap the period (the time of the house, `from` included, `to` not, at most 30 days), the newest first: `id`, `detectorId`, `name` (the registry's now, left out for a detector it no longer lists), `room`, `startedAt`, `clearedAt` (left out while it lasts), `alarmToldAt` / `overToldAt` (left out when the household could not be told). `400 INVALID_RANGE`. |
| `GET` | `/status` | The technical state (HAS-463): `bases` - every base station of the registry: `stationSerial`, `reachable` (its availability, left out while not known), `lastHeardAt` (itself or any of its detectors), `ipAddress`, `wifiRssi`, `silent`; `detectorsInTrouble` - how many detectors report offline, low battery, end of life or a fault now; `alerts` - the open alerts of the monitor: `kind` (`offline`, `battery`, `end of life`, `fault`, `base offline`, `base silent`), `device`, `name`, `since`, `told`. `503 REGISTRY_UNAVAILABLE` before the first read of the registry. |
| `GET` | `/rooms/{room}` | The detectors of a room (`living room`, ...); `404 NOT_FOUND_ROOM` for a room without one or no room at all. |
| `GET` | `/{id}` | One detector by its registry id; `404 NOT_FOUND_DETECTOR`. Since HAS-464 a detector also answers `lastCommandConfirmed` - false once the base did not answer its last mute or self test within 60 s, true once it did, left out with no command since the start and while one waits. |
| `POST` | `/{id}/mute?source=` | Mutes the detector (HAS-464): the command published to its base once, `202` with `detectorId`, `command` (`mute`), `source`, `requestId`, `sentAt`. `source` - `dashboard` (the default) or `manual`, any other word `400`. `404 NOT_FOUND_DETECTOR`, `409 NOTHING_TO_MUTE` (the detector is not in alarm - by the last `alarm` of its base; checked before the minute is taken), `429 COMMAND_TOO_SOON` (one command per detector a minute), `502 COMMAND_NOT_SENT` (it could not be sent; a send cut off by its timeout may still go out and is answered `202`), `503 REGISTRY_UNAVAILABLE`. |
| `POST` | `/{id}/self-test?source=` | Starts the self test of the detector, as the mute (`command` `self test`) - of any detector, in alarm or not (no `409`). |
| `GET` | `/{id}/commands` | The last 50 commands of the detector, the newest first: `requestId`, `command`, `source`, `sentAt`, `confirmedAt` (left out while the base has not answered). `404 NOT_FOUND_DETECTOR`. |

# Run locally

- Build: `mvn verify` (JDK 21) - **needs a running Docker**: the tests of the state tables, the alarm episodes, the
  technical alerts and the commands run on PostgreSQL in Testcontainers.
- Ports: local profile `6017` (management `8017`); in the cluster `6200` / `8200`.
- Own database `home-automation-safety`, pool 2: `database-host`, `database-port`, `database-name`, `database-user`,
  `database-password` and `flyway-url`; `database-password` is mandatory (`cholewa.required-properties`), the rest
  falls back to `localhost` / dummy values that fail on Flyway.
- The broker: `rabbitmq-password` (user `device`, mandatory); the local profile consumes `device.dev.safety` on
  `localhost`. The notifications: a second connection to `/notification` as the user `notification`,
  `notification-rabbitmq-password` (mandatory); the local profile publishes with `env: dev`, to
  `notification.dev.household`. database-service: `internal.service.database` (`localhost:6005` locally). **A local run with the real
  values is the production database, and its birth message reaches the real base.**
