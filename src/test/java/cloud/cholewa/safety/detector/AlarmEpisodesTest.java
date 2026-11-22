package cloud.cholewa.safety.detector;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.automation.FireReactions;
import cloud.cholewa.safety.database.AlarmStore;
import cloud.cholewa.safety.household.HouseholdNotifier;
import cloud.cholewa.safety.registry.Detector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the episodes of HAS-461: the memory decides, the row is written before the household is told, the household is told
//whatever the database says, a mark only for what the broker took. The mocks answer at once, so the work of the sink
//has run when alarm() returns
class AlarmEpisodesTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Detector KITCHEN = new Detector(151L, "Smoke detector", RoomName.KITCHEN,
        DeviceKind.SMOKE_DETECTOR, "SBS50ABCDEF01", "00000002");

    private final AlarmStore store = mock(AlarmStore.class);
    private final HouseholdNotifier notifier = mock(HouseholdNotifier.class);
    private final FireReactions reactions = mock(FireReactions.class);
    private final AlarmEpisodes sut = new AlarmEpisodes(store, notifier, reactions,
        Clock.fixed(NOW, ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(reactions.react(any(), any())).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        sut.stop();
    }

    @Test
    void should_open_an_episode_tell_the_household_and_mark_it() {
        when(store.open(151L, "kitchen", NOW)).thenReturn(Mono.just(7L));
        when(notifier.fireAlarm(KITCHEN, NOW)).thenReturn(Mono.just(true));
        when(store.markAlarmTold(7L, NOW)).thenReturn(Mono.empty());

        sut.alarm(KITCHEN, true);

        final InOrder order = inOrder(store, notifier);
        order.verify(store).open(151L, "kitchen", NOW);
        order.verify(notifier).fireAlarm(KITCHEN, NOW);
        order.verify(store).markAlarmTold(7L, NOW);
        assertThat(sut.isOpen(151L)).isTrue();
        verify(reactions).react(7L, KITCHEN);
    }

    //HAS-468: the reactions run beside the household - a household not told yet never holds them, nor they it
    @Test
    void should_react_while_the_household_is_being_told() {
        when(store.open(151L, "kitchen", NOW)).thenReturn(Mono.just(7L));
        when(notifier.fireAlarm(KITCHEN, NOW)).thenReturn(Mono.never());

        sut.alarm(KITCHEN, true);

        verify(reactions, timeout(2000)).react(7L, KITCHEN);
    }

    //the base repeats the state: a Detected of a detector with an open episode is no new one
    @Test
    void should_tell_a_repeated_detected_nothing() {
        when(store.open(anyLong(), any(), any())).thenReturn(Mono.just(7L));
        when(notifier.fireAlarm(any(), any())).thenReturn(Mono.just(true));
        when(store.markAlarmTold(anyLong(), any())).thenReturn(Mono.empty());

        sut.alarm(KITCHEN, true);
        sut.alarm(KITCHEN, true);

        verify(notifier).fireAlarm(KITCHEN, NOW);
        verify(store).open(151L, "kitchen", NOW);
        verify(reactions).react(7L, KITCHEN);
    }

    @Test
    void should_close_the_episode_and_tell_the_end() {
        sut.restoreOpen(151L);
        when(store.close(151L, NOW)).thenReturn(Mono.just(7L));
        when(notifier.fireAlarmOver(KITCHEN, NOW)).thenReturn(Mono.just(true));
        when(store.markOverTold(7L, NOW)).thenReturn(Mono.empty());

        sut.alarm(KITCHEN, false);

        verify(store).close(151L, NOW);
        verify(notifier).fireAlarmOver(KITCHEN, NOW);
        verify(store).markOverTold(7L, NOW);
        assertThat(sut.isOpen(151L)).isFalse();
    }

    //the base republishes Cleared of every detector every hour
    @Test
    void should_ignore_a_cleared_without_an_open_episode() {
        sut.alarm(KITCHEN, false);

        verifyNoInteractions(store, notifier);
    }

    //read back at the start: the Detected the base republishes after it is no new alarm
    @Test
    void should_not_tell_again_an_alarm_open_before_the_start() {
        sut.restoreOpen(151L);

        sut.alarm(KITCHEN, true);

        verifyNoInteractions(store, notifier);
        //an episode open before the start was reacted to then: never a second time (HAS-468)
        verify(reactions, never()).react(any(), any());
    }

    //a database away never keeps the household from being told; only the mark is missing
    @Test
    void should_tell_the_household_when_the_database_is_away() {
        when(store.open(anyLong(), any(), any())).thenReturn(Mono.error(new IllegalStateException("database away")));
        when(notifier.fireAlarm(KITCHEN, NOW)).thenReturn(Mono.just(true));
        when(store.close(anyLong(), any())).thenReturn(Mono.error(new IllegalStateException("database away")));
        when(notifier.fireAlarmOver(KITCHEN, NOW)).thenReturn(Mono.just(true));

        sut.alarm(KITCHEN, true);
        sut.alarm(KITCHEN, false);

        verify(notifier).fireAlarm(KITCHEN, NOW);
        verify(notifier).fireAlarmOver(KITCHEN, NOW);
        verify(store, never()).markAlarmTold(anyLong(), any());
        verify(store, never()).markOverTold(anyLong(), any());
        //the reactions go without the episode stored, and nothing at its end
        verify(reactions).react(isNull(), eq(KITCHEN));
        verify(reactions, times(1)).react(any(), any());
    }

    //a database that does not answer holds the household for STORE_WITHIN at most, never the retries of the store
    @Test
    void should_tell_the_household_within_the_bound_when_the_database_does_not_answer() {
        when(store.open(anyLong(), any(), any())).thenReturn(Mono.never());
        when(notifier.fireAlarm(KITCHEN, NOW)).thenReturn(Mono.just(true));

        sut.alarm(KITCHEN, true);

        verify(notifier, timeout(AlarmEpisodes.STORE_WITHIN.plusSeconds(2).toMillis())).fireAlarm(KITCHEN, NOW);
        verify(store, never()).markAlarmTold(anyLong(), any());
    }

    //not told: no mark; and the next episode is still worked on
    @Test
    void should_mark_nothing_the_broker_did_not_take_and_go_on() {
        when(store.open(anyLong(), any(), any())).thenReturn(Mono.just(7L));
        when(notifier.fireAlarm(KITCHEN, NOW)).thenReturn(Mono.just(false));
        when(store.close(151L, NOW)).thenReturn(Mono.just(7L));
        when(notifier.fireAlarmOver(KITCHEN, NOW)).thenReturn(Mono.just(true));
        when(store.markOverTold(7L, NOW)).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.alarm(KITCHEN, true);
        sut.alarm(KITCHEN, false);
        sut.alarm(KITCHEN, true);

        verify(store, never()).markAlarmTold(anyLong(), any());
        verify(store).markOverTold(eq(7L), any());
        verify(store, times(2)).open(151L, "kitchen", NOW);
    }
}
