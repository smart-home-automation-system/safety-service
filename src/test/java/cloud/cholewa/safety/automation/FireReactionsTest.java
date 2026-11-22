package cloud.cholewa.safety.automation;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.database.AutomationStore;
import cloud.cholewa.safety.registry.Detector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the reactions of HAS-468: each behind its switch, the lights of the room and both halls, the blinds of the room, every
//unit that is on off; a row for each call; nothing sent with the switches off or not known; never an error
class FireReactionsTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Detector KITCHEN = new Detector(151L, "Smoke detector", RoomName.KITCHEN,
        DeviceKind.SMOKE_DETECTOR, "SBS50ABCDEF01", "00000002");
    private static final Detector NO_ROOM = new Detector(152L, "Smoke detector", null, DeviceKind.SMOKE_DETECTOR,
        "SBS50ABCDEF01", "00000003");
    private static final AutomationSwitches ALL_ON = new AutomationSwitches(true, true, true);

    private final AutomationService automation = mock(AutomationService.class);
    private final HouseServicesClient client = mock(HouseServicesClient.class);
    private final AutomationStore store = mock(AutomationStore.class);
    private final FireReactions sut = new FireReactions(automation, client, store,
        Clock.fixed(NOW, ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(store.insertRun(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(client.lightsOn(any())).thenReturn(Mono.just(ReactionOutcome.SENT));
        when(client.blindsUp(any())).thenReturn(Mono.just(ReactionOutcome.SENT));
        when(client.airconOff(anyLong())).thenReturn(Mono.just(ReactionOutcome.SENT));
        when(client.airconUnitsOn()).thenReturn(Flux.just(140L, 143L));
    }

    @Test
    void should_send_every_reaction_switched_on_and_store_each() {
        when(automation.forAlarm()).thenReturn(Mono.just(ALL_ON));
        when(client.blindsUp(RoomName.KITCHEN)).thenReturn(Mono.just(ReactionOutcome.NONE));

        sut.react(7L, KITCHEN).as(StepVerifier::create).verifyComplete();

        verify(client).lightsOn(RoomName.KITCHEN);
        verify(client).lightsOn(RoomName.HALL_DOWN);
        verify(client).lightsOn(RoomName.HALL_UP);
        verify(client).airconOff(140L);
        verify(client).airconOff(143L);
        verify(store).insertRun(7L, Reaction.LIGHTS_ON, "kitchen", NOW, ReactionOutcome.SENT);
        verify(store).insertRun(7L, Reaction.LIGHTS_ON, "hall down", NOW, ReactionOutcome.SENT);
        verify(store).insertRun(7L, Reaction.LIGHTS_ON, "hall up", NOW, ReactionOutcome.SENT);
        verify(store).insertRun(7L, Reaction.BLINDS_UP, "kitchen", NOW, ReactionOutcome.NONE);
        verify(store).insertRun(7L, Reaction.AIRCON_OFF, "140", NOW, ReactionOutcome.SENT);
        verify(store).insertRun(7L, Reaction.AIRCON_OFF, "143", NOW, ReactionOutcome.SENT);
    }

    //the decision is logged, nothing is sent
    @Test
    void should_send_nothing_with_the_switches_off() {
        when(automation.forAlarm()).thenReturn(Mono.just(new AutomationSwitches(false, false, false)));

        sut.react(7L, KITCHEN).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client, store);
    }

    @Test
    void should_send_only_what_is_switched_on() {
        when(automation.forAlarm()).thenReturn(Mono.just(new AutomationSwitches(false, true, false)));

        sut.react(7L, KITCHEN).as(StepVerifier::create).verifyComplete();

        verify(client).blindsUp(RoomName.KITCHEN);
        verify(client, never()).lightsOn(any());
        verify(client, never()).airconUnitsOn();
    }

    //a reaction is never sent on a guess
    @Test
    void should_send_nothing_when_the_switches_are_not_known() {
        when(automation.forAlarm()).thenReturn(Mono.empty());

        sut.react(7L, KITCHEN).as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client, store);
    }

    //a detector without a room lights the way out alone - the halls; no blinds to open
    @Test
    void should_light_the_halls_alone_for_a_detector_without_a_room() {
        when(automation.forAlarm()).thenReturn(Mono.just(ALL_ON));

        sut.react(null, NO_ROOM).as(StepVerifier::create).verifyComplete();

        verify(client).lightsOn(RoomName.HALL_DOWN);
        verify(client).lightsOn(RoomName.HALL_UP);
        verify(client, never()).blindsUp(any());
        verify(store, times(4)).insertRun(isNull(), any(), any(), any(), any());
    }

    @Test
    void should_store_a_failed_read_of_the_air_conditioners_and_go_on() {
        when(automation.forAlarm()).thenReturn(Mono.just(ALL_ON));
        when(client.airconUnitsOn()).thenReturn(Flux.error(new IllegalStateException("aircon-service away")));

        sut.react(7L, KITCHEN).as(StepVerifier::create).verifyComplete();

        verify(store).insertRun(7L, Reaction.AIRCON_OFF, null, NOW, ReactionOutcome.FAILED);
        verify(client).lightsOn(RoomName.KITCHEN);
        verify(client).blindsUp(RoomName.KITCHEN);
    }

    //a row that could not be stored changes nothing of the reactions
    @Test
    void should_complete_when_a_row_is_not_stored() {
        when(automation.forAlarm()).thenReturn(Mono.just(ALL_ON));
        when(store.insertRun(any(), any(), any(), any(), any()))
            .thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.react(7L, KITCHEN).as(StepVerifier::create).verifyComplete();

        verify(client).airconOff(143L);
    }

    //a call that never answers is cut off with the rest at BOUND, never an error
    @Test
    void should_be_bounded() {
        when(automation.forAlarm()).thenReturn(Mono.just(ALL_ON));
        when(client.blindsUp(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> sut.react(7L, KITCHEN))
            .thenAwait(FireReactions.BOUND)
            .verifyComplete();
    }

    @Test
    void should_light_the_room_first_and_each_hall_once() {
        assertThat(FireReactions.lightRooms(RoomName.KITCHEN))
            .containsExactly(RoomName.KITCHEN, RoomName.HALL_DOWN, RoomName.HALL_UP);
        assertThat(FireReactions.lightRooms(RoomName.HALL_UP)).containsExactly(RoomName.HALL_UP, RoomName.HALL_DOWN);
        assertThat(FireReactions.lightRooms(null)).containsExactly(RoomName.HALL_DOWN, RoomName.HALL_UP);
    }
}
