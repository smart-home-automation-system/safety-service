package cloud.cholewa.safety.command;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.database.CommandStore;
import cloud.cholewa.safety.registry.Detector;
import cloud.cholewa.safety.registry.DetectorRegistry;
import cloud.cholewa.safety.xsense.CommandMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static cloud.cholewa.safety.registry.TestDevices.STATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the window of a command (HAS-464): nothing after an answer, a WARN and a count after none, the third in a row of a
//detector an alert on the technical channel - once a run, tried again when it did not get through
class CommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Detector KITCHEN = new Detector(151L, "Smoke detector", RoomName.KITCHEN,
        DeviceKind.SMOKE_DETECTOR, STATION, "00000002");

    private final DetectorCommands commands = new DetectorCommands();
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private final DetectorRegistry registry = mock(DetectorRegistry.class);
    private final CommandMessage message = mock(CommandMessage.class);
    private final CommandStore store = mock(CommandStore.class);
    private final CommandService service = new CommandService(registry, commands, message, store,
        notificationPublisher, Clock.fixed(NOW, ZoneId.of("Europe/Warsaw")));

    //the base republishes the result of its last test at every resync: a test it dates before the sending answers
    //nothing, one dated after it (or within the tolerance of its clock) does, an undated one as it is taken
    @Test
    void should_confirm_a_self_test_only_by_a_test_run_after_its_sending() {
        when(store.confirm(anyString(), any())).thenReturn(Mono.empty());
        commands.issued(KITCHEN.id(), new CommandWatch.Pending("sh-1", DetectorCommand.SELF_TEST, NOW));

        service.selfTestReported(KITCHEN.id(), NOW.minusSeconds(3600));
        assertThat(commands.lastConfirmed(KITCHEN.id())).isEmpty();
        service.selfTestReported(KITCHEN.id(), NOW.minusSeconds(20));
        assertThat(commands.lastConfirmed(KITCHEN.id())).contains(true);

        commands.issued(KITCHEN.id(), new CommandWatch.Pending("sh-2", DetectorCommand.SELF_TEST, NOW));
        service.selfTestReported(KITCHEN.id(), null);
        assertThat(commands.lastConfirmed(KITCHEN.id())).contains(true);
    }

    //the answer of the base is written once the row of its command is: a fast answer must find the row
    @Test
    void should_write_the_answer_only_after_the_row_of_its_command() {
        final Sinks.Empty<Void> insert = Sinks.empty();
        final List<String> order = new CopyOnWriteArrayList<>();
        known();
        when(message.send(anyString(), anyString(), anyString(), any())).thenReturn(Mono.empty());
        when(store.insert(anyLong(), anyString(), anyString(), anyString(), any()))
            .thenReturn(insert.asMono().doOnSuccess(done -> order.add("insert")));
        when(store.confirm(anyString(), any())).thenReturn(Mono.fromRunnable(() -> order.add("confirm")));

        service.command(KITCHEN.id(), DetectorCommand.MUTE, CommandSource.DASHBOARD).subscribe();
        service.reported(KITCHEN.id(), DetectorCommand.MUTE);
        assertThat(order).isEmpty();
        insert.tryEmitEmpty();

        assertThat(order).containsExactly("insert", "confirm");
    }

    //a send cut off by its timeout may still go out: taken like a sent one - its minute kept, its row written
    @Test
    void should_take_a_send_cut_off_by_its_timeout_like_a_sent_one() {
        known();
        when(message.send(anyString(), anyString(), anyString(), any()))
            .thenReturn(Mono.error(new TimeoutException("10 s")));
        when(store.insert(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(Mono.empty());

        service.command(KITCHEN.id(), DetectorCommand.MUTE, CommandSource.DASHBOARD).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.command()).isEqualTo("mute"))
            .verifyComplete();

        verify(store).insert(eq(KITCHEN.id()), eq("mute"), eq("dashboard"), anyString(), eq(NOW));
        assertThat(commands.reserve(KITCHEN.id(), NOW.plusSeconds(1))).isFalse();
    }

    private void known() {
        when(registry.isKnown()).thenReturn(true);
        when(registry.find(KITCHEN.id())).thenReturn(Optional.of(KITCHEN));
    }

    @Test
    void should_end_with_nothing_when_the_base_answered() {
        final CommandWatch.Pending mute = pending("sh-1");
        commands.issued(KITCHEN.id(), mute);
        commands.reported(KITCHEN.id(), DetectorCommand.MUTE, NOW.plusSeconds(4));

        StepVerifier.withVirtualTime(() -> service.confirmation(KITCHEN, mute))
            .thenAwait(CommandService.WINDOW)
            .verifyComplete();

        assertThat(commands.lastConfirmed(KITCHEN.id())).contains(true);
        verify(notificationPublisher, never()).publishAlert(anyString());
    }

    @Test
    void should_alert_once_at_the_third_unconfirmed_command_in_a_row() {
        when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.empty());

        for (int command = 1; command <= 4; command++) {
            unconfirmed(pending("sh-" + command));
        }

        assertThat(commands.lastConfirmed(KITCHEN.id())).contains(false);
        verify(notificationPublisher).publishAlert("The detector Smoke detector (kitchen) has not confirmed 3 commands "
            + "in a row: the last one, mute at 11:00, was not answered by its base within 60 s.");
    }

    @Test
    void should_alert_again_with_the_next_command_when_the_alert_did_not_get_through() {
        when(notificationPublisher.publishAlert(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("broker away")))
            .thenReturn(Mono.empty());

        for (int command = 1; command <= 5; command++) {
            unconfirmed(pending("sh-" + command));
        }

        verify(notificationPublisher, times(2)).publishAlert(anyString());
    }

    @Test
    void should_name_the_detector_its_room_and_the_last_command() {
        assertThat(CommandService.alertText(KITCHEN, pending("sh-1"), 3, LocalDateTime.of(2026, 11, 20, 22, 5)))
            .isEqualTo("The detector Smoke detector (kitchen) has not confirmed 3 commands in a row: the last one, "
                + "mute at 22:05, was not answered by its base within 60 s.");
    }

    private void unconfirmed(final CommandWatch.Pending command) {
        commands.issued(KITCHEN.id(), command);
        StepVerifier.withVirtualTime(() -> service.confirmation(KITCHEN, command))
            .thenAwait(CommandService.WINDOW)
            .verifyComplete();
    }

    private static CommandWatch.Pending pending(final String requestId) {
        return new CommandWatch.Pending(requestId, DetectorCommand.MUTE, NOW);
    }
}
