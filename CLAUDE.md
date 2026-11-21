# safety-service

The fire detectors of the house (epic HAS-455): the state of every X-Sense detector from what its SBS50 base station
publishes to the broker over MQTT. The scaffold of HAS-460, from the scaffold of gate-service (itself from aircon-service,
energy-service and water-service): Spring Boot 4.1.3, cholewa-commons 1.14.0, smart-home-sdk 1.24.0, no shelly-client;
port 6017 / 8017 locally, 6200 / 8200 in the cluster, base path `/home/safety`; own database `home-automation-safety`,
pool 2; `database-password` and `rabbitmq-password` mandatory; scheduling off under `test`; the Deployment `Recreate`,
never scaled (state in memory). Org-wide rules: `organization-repository/claude/organization.md`.

## Role in the system

- Talks to: the device events on `device.prod.safety` of `/device` (user `device`, definitions 1.10.0) - the topics of
  the base in `deployment-tools/scripts/xsense/devices.md`; the same connection publishes the birth message to
  `amq.topic`; database-service for the detectors of the registry. Since HAS-461 a second connection to `/notification`
  (user `notification`, `notification-rabbitmq-password`) publishes the fire alarm to the `household` category -
  notification-service >= 0.14.0 (HAS-462) words it.
- Uses libraries: cholewa-commons, smart-home-sdk.
- Database: own database `home-automation-safety`, 2 of the 47 connections of the managed PostgreSQL.
- Integrates with hardware: the X-Sense SBS50 base and its detectors, only through the broker - the base listens on no
  port. The registry holds the eleven smoke detectors XS01-M (ids 150-160, HAS-458).

## Build & run

- Build + tests: `mvn verify` - **needs a running Docker** (`StateStoreTest`, `AlarmStoreTest`, `AlertStoreTest`,
  `CommandStoreTest`: Testcontainers PostgreSQL).
- Local run: `local` Spring profile, port `6017` (Actuator `8017`); the real values reach the production database and
  the real base.

## Specifics

- **The registry** (`registry/`): `DetectorRegistry` reads the switched-on devices of the kinds `smoke detector`,
  `co detector` and `heat detector` one after the other (`DeviceRegistryClient`, `?kind=&enabled=true`), keeps those
  with an `xsense` binding, by id and by the pair of the base serial and the detector id (`DetectorKey`, in capitals).
  Not known until the first read succeeded - the reads answer `503 REGISTRY_UNAVAILABLE` and every detector message is
  dropped; a failed read keeps the detectors before (a WARN of the pass), never an empty registry.
- **The topics** (`xsense/XsenseTopic`, seen on the real base 2026-11-16 and 2026-11-20 - firmware 1.8.7, detectors
  XS01-M v1.8.0): `homeassistant.binary_sensor.<S>_<id>.<S>_<id>_<entity>.state` (`{"status": word}`) and `.config`
  (the `device` block - model, `sw_version`), `xsense.sbs50.<S>_<id>.self_test.state` (`{"problem": boolean}`),
  `xsense.sbs50.<S>.availability` (plain `online` / `offline`) and `xsense.sbs50.<S>.network.state`. Everything else
  of the queue - about 130 messages a minute, the `cmd` topics of the base and the birth message itself included - is
  dropped first, without a word, then a detector the registry does not list; an empty body (a discovery removed) too.
- **The flags** (`detector/DetectorFlag`): the entity and the two words of each - `smokealarm` Detected / Cleared,
  `online` Online / Offline, `battery` Low / Normal, `lifeend` EOL / Normal, `smokefault` Fault / Normal, `muted`
  Muted / Normal (the `payload_on` / `payload_off` of the discovery); the self test on its own topic. A word neither of
  the two is a WARN once per flag and word and changes nothing; an entity not read (a CO or heat detector names its
  alarm otherwise - not seen yet) is an INFO once; a body that is not the model is a WARN, the message acknowledged.
- **The state** (`detector/SafetyStates`): per detector and per base in memory, changed atomically per entry (prefetch
  3); a flag keeps the moment it took its value through messages that repeat it. A change answers the state to store
  only when the database would hold something else - a repeat only confirms and moves `lastHeardAt` in memory; the
  RSSI of the base alone is no change. **The listener never waits for the database**: a change goes to `StateWriter`
  (one sink, the writes one after the other) and the message is acknowledged once memory changed - a database away
  must not hold the three messages in flight while an alarm behind them expires in the queue (review of HAS-460).
  **`detector_state` / `base_state` (V1)** - a row per thing written whole by `StateStore` (one upsert each,
  PostgreSQL's own, `WHERE EXCLUDED.updated_at >= ...` so an older snapshot arriving last never wins, and `COALESCE`
  so a value the snapshot does not know never replaces a stored one - a start that could not read the rows back would
  otherwise wipe the model and firmware the base does not send again), bounded at 30 s, tried twice more with a
  backoff, never for a refusal of the schema, a WARN otherwise; a write that failed for good is written again only with
  the next change (accepted - memory is what the API answers);
  read back by `StateLoader` (a lifecycle of phase 0, before the listener, bounded at 15 s, never fatal) as not
  confirmed, never over a state a message set first. `lastHeardAt` in the database is the one of the last write.
- **The birth message** (`xsense/BirthMessage`): `online`, plain text, persistent (QoS 1 for the MQTT subscriber) to
  `amq.topic` with the routing key `homeassistant.status` - sent by the pass of the registry of `SafetySchedule` once the
  registry is known (the messages before were dropped) and until the broker took one, then every
  `safety.base.resync-every` (10 min since HAS-463, 1 h before). The base
  answers with every state within seconds; on 2026-11-20 it sent no discovery with it, so the model and firmware come
  only when the base sends its discovery on its own (its reconnect). RabbitTemplate blocks, so the send runs on
  boundedElastic, bounded at 20 s (inside the 50 s of the pass after its three reads); a failure is a WARN and `false`. Every device queue gets the message (bound `#`), none reads it.
- **The API** (`api/SafetyController` -> `detector/SafetyService`, from memory alone): `GET /`, `/rooms/{room}`,
  `/{id}`; the times are `LocalDateTime` of the house, as gate-service answers them; a field not known left out
  (`@JsonInclude(NON_NULL)`), a detector never heard answered with its registry fields alone (`confirmed` left out).
  The codes of `SafetyError` are wire contract for the dashboard (`SafetyErrorTest`).
- **The fire alarm episodes** (HAS-461, `detector/AlarmEpisodes`): **the memory decides** - a set of the detectors with an
  open episode, read back at the start by `StateLoader` (`AlarmStore.openDetectors`). Every `smokealarm` message, repeated
  or not, reaches it from the router after memory changed: Detected of a detector not in the set opens, Cleared of one in
  it closes, a Cleared without one is a DEBUG (the base republishes Cleared of every detector at every resync), a
  repeated Detected nothing - decided atomically in the listener, the instant taken there. The work runs off the
  listener in one sink, one episode after the other: the row (`AlarmStore`, waited for `STORE_WITHIN` - 5 s - at most:
  the store retries for a minute and a half, and a fire alarm is told within seconds), then the household
  (`HouseholdNotifier`, bounded at 30 s, false and a WARN when not told - never an error), then the mark of what the
  broker took. **The household is told whatever the database says**; a write cut off may still land, only its mark is
  lost. **`detector_alarm` (V2)**: one row per episode, `room` as the registry had it, the partial unique index
  `detector_alarm_open_uq` - one open episode per detector; `open` answers the id of the open episode, new or already
  there (a retry after a timeout must find its own row - so the store never decides whether to tell). Accepted in the
  review of HAS-461: a Cleared that overtakes its Detected in the prefetch of 3 leaves the episode open until the next
  resync (the base sends them seconds apart); a detector switched off or removed while in alarm never closes (its
  messages are dropped); a start that cannot read the open episodes back tells an ongoing alarm again (twice beats
  never). Never a reminder - the detectors sound themselves. `NotificationRabbitConfig` is the ninth copy (owner's rule,
  organization.md), not a bean of a `ConnectionFactory` - `NotificationConnection.create`, named `<pod>/notification`.
- **`GET /alarms?from=&to=`** (`detector/AlarmService`): the episodes that overlap the period of the house (at most 30
  days, `INVALID_RANGE`), the newest first, the name the registry's now; the alarm of a detector in the reads stays its
  flag `{value, since}`.
- **The technical monitor** (HAS-463, `monitor/`): `TechnicalMonitor` every minute (2 min after the start, bounded at
  55 s) builds what is watched - per detector of the registry its four problems (`AlertKind`: offline, battery,
  end of life, fault - out while the flag says so, since the flag took that value, not known while the base never
  reported it, at once), per base of them offline and silent - and `AlertEvaluator` (pure, a copy of the evaluator of
  gate-service HAS-453; aircon-service and light-service carry the same rules - the fourth copy, kept by the owner's
  choice like `NotificationRabbitConfig`) decides against the open episodes of `safety_alert` (V3, one open per item -
  the partial unique index, the closed ones kept): one return, one alert and one reminder a pass at most, each naming
  everything, on the technical channel (`publishInfo` / `publishAlert` / `publishReminder`) - never the household, never
  an SMS. A row is written when due and marked told once the broker took it; a publish is cut off after 15 s and tried
  again with the next pass. No pass before the registry is known (every detector would look removed). Settled in the
  review of HAS-463: **a base is offline while its last word is offline and nothing came from it since** (it need not say
  online again after its last will); **the silence of a base known offline is not told apart**; **the silence counts from
  the start of the service at the earliest** (a state read back holds the last heard of its last write). **The base is
  asked to republish every 10 min** (`safety.base.resync-every`, owner 2026-11-20 - it publishes on a change only, and
  answered the hourly resync alone before), so `safety.base.silent-after` (30 min) is a real silence; `SafetyProperties`
  refuses a silence not longer than the resync. **`GET /status`** (`StatusService`) judges by the same rules
  (`TechnicalMonitor.watched`).
- **The commands of a detector** (HAS-464, `command/`): `POST /{id}/mute`, `/{id}/self-test` (`?source=` -
  `CommandSource`: `dashboard` by default, `manual`; `CommandSourceConverter`, a Converter bean Boot adds to WebFlux)
  -> `CommandService`: the registry known (`503`), the detector (`404`), **one command per detector a minute**
  (`DetectorCommands.reserve`, a mute and a self test alike, `429 COMMAND_TOO_SOON`; given back when the broker did not
  take it - `502 COMMAND_NOT_SENT`), then `xsense/CommandMessage` - **the topic** `xsense/sbs50/<S>/device/<id>/cmd`
  (the routing key `xsense.sbs50.<S>.device.<id>.cmd` of `amq.topic`; the base binds one per detector and
  `station.cmd` - 13 bindings, seen 2026-11-16 and 2026-11-20) and **the payload** the base announces in the configs of
  its buttons, `{"requestId":"sh-<uuid>","cmd":"mute"|"self_test","params":{}}`, persistent (QoS 1), never retained,
  over the connection of `/device` (RabbitTemplate on boundedElastic, bounded at 10 s). **Sent once, never repeated.** The
  command comes back on `device.prod.safety` (bound `#`) and is dropped as every `cmd` topic. **Confirmation**
  (`CommandWatch`, pure, the rule of water-service HAS-321): a mute by `muted` Muted taken at or after the sending, a
  self test by a message of its self test - dated by the base (its `time`, epoch seconds) it answers only a test sent
  before it (`BASE_CLOCK_TOLERANCE` 30 s for a base clock behind ours; the base republishes the last result at every
  resync), undated as it is taken - within `CommandService.WINDOW` (60 s); the router hands them to `CommandService`
  after memory changed. A Muted of a resync also confirms a mute (accepted in the review of HAS-464: it says the detector
  is muted). **A send that returns proves only that the connection wrote it** - no publisher confirms, no mandatory
  returns, an unrouted command vanishes in `amq.topic`: the answer of the base is the proof. A send cut off by its 10 s
  may still go out, so it is taken like a sent one (202, its minute kept, watched); a send that failed gives its minute
  back (`502 COMMAND_NOT_SENT` - no text in the dashboard yet, its generic failure). `lastCommandConfirmed` of the reads: left out with no command and while one waits, false after the window,
  true once answered. The third unconfirmed in a row of a detector is an alert on the technical channel, once a run (not
  announced when the publish failed - the next tries again). In memory: a restart forgets the minute and the judgement.
  **`safety_command` (V4)**: a row per command sent (bounded at 5 s, a WARN otherwise - the command stands),
  `confirmed_at` marked by the request id once the insert of that row ended (a fast answer would update no row); `GET /{id}/commands` the last 50. What the base did with the first real
  commands: to be written here after the try with the owner.
- **Boot 4.2 is not taken yet** (checked 2026-11-20): Boot 4.2.0 moved RabbitMQ to `spring-boot-starter-rabbitmq`
  (`org.springframework.boot.rabbitmq.autoconfigure`) and `spring-boot-starter-amqp` no longer brings `spring-rabbit` -
  the listener, `BirthMessage` and `NotificationRabbitConfig` do not compile, and cholewa-commons 1.14.0 reaches the
  classes of Boot 4.1; cholewa-commons moves first.
