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
  `amq.topic`; database-service for the detectors of the registry.
- Uses libraries: cholewa-commons, smart-home-sdk.
- Database: own database `home-automation-safety`, 2 of the 47 connections of the managed PostgreSQL.
- Integrates with hardware: the X-Sense SBS50 base and its detectors, only through the broker - the base listens on no
  port. The registry holds the eleven smoke detectors XS01-M (ids 150-160, HAS-458).

## Build & run

- Build + tests: `mvn verify` - **needs a running Docker** (`StateStoreTest`: the upserts on Testcontainers PostgreSQL).
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
  `safety.base.resync-every` (1 h). The base
  answers with every state within seconds; on 2026-11-20 it sent no discovery with it, so the model and firmware come
  only when the base sends its discovery on its own (its reconnect). RabbitTemplate blocks, so the send runs on
  boundedElastic, bounded at 20 s (inside the 50 s of the pass after its three reads); a failure is a WARN and `false`. Every device queue gets the message (bound `#`), none reads it.
- **The API** (`api/SafetyController` -> `detector/SafetyService`, from memory alone): `GET /`, `/rooms/{room}`,
  `/{id}`; the times are `LocalDateTime` of the house, as gate-service answers them; a field not known left out
  (`@JsonInclude(NON_NULL)`), a detector never heard answered with its registry fields alone (`confirmed` left out).
  The codes of `SafetyError` are wire contract for the dashboard (`SafetyErrorTest`).
- Nothing is told yet: the alarm episodes and the household messages are HAS-461 (notification-service HAS-462 reads
  `fire alarm` first), the technical monitor of the detectors and the base HAS-463.
