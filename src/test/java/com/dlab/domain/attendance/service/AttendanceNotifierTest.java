package com.dlab.domain.attendance.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 출결 태깅 알림 본체.
 *
 * <p>연결(태깅 → 호출)은 {@code KioskAttendanceIntegrationTest}가 보고, 여기서는
 * <b>무엇을 어떻게 보내는가</b>를 본다 — {@code REQUIRES_NEW}라 통합 테스트에서는
 * 같은 트랜잭션의 데이터가 안 보여 결과를 확인할 수 없다(상벌점 엔진과 같은 구조).
 */
class AttendanceNotifierTest {

    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 10, 16).atTime(18, 3).atZone(TimeConfig.KST).toInstant(),
            TimeConfig.KST);

    NotificationService notificationService;
    AccountRepository accountRepository;
    AttendanceNotifier notifier;

    Academy bundang;
    StudentEnrollment minji;
    Account father;
    Account mother;

    @BeforeEach
    void setUp() {
        notificationService = Mockito.mock(NotificationService.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        notifier = new AttendanceNotifier(notificationService, accountRepository, CLOCK);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ReflectionTestUtils.setField(bundang, "id", 1L);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        ReflectionTestUtils.setField(student, "id", 11L);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", "ABC001", GradeType.HIGH3);

        father = Mockito.mock(Account.class);
        when(father.getId()).thenReturn(101L);
        mother = Mockito.mock(Account.class);
        when(mother.getId()).thenReturn(102L);
    }

    private AttendanceTaggingLog tagging(AttendanceEventType type, long id, LocalTime at) {
        AttendanceTaggingLog log = new AttendanceTaggingLog(bundang, minji, type,
                AttendanceSource.KIOSK_NFC,
                LocalDate.of(2026, 10, 16).atTime(at).atZone(TimeConfig.KST).toInstant(),
                LocalDate.of(2026, 10, 16));
        ReflectionTestUtils.setField(log, "id", id);
        return log;
    }

    private void withGuardians(Account... accounts) {
        when(accountRepository.findGuardianAccountsOfStudent(11L)).thenReturn(List.of(accounts));
    }

    private List<NotificationCommand> captured() {
        ArgumentCaptor<NotificationCommand> captor =
                ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService, Mockito.atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("등원 태깅 — 학부모 전원에게 가고 시각·학생명이 실린다")
    void checkInNotifiesEveryGuardian() {
        withGuardians(father, mother);

        notifier.notifyTagged(minji, tagging(AttendanceEventType.CHECK_IN, 7L, LocalTime.of(8, 30)));

        List<NotificationCommand> sent = captured();
        Assertions.assertThat(sent).hasSize(2);
        Assertions.assertThat(sent).allSatisfy(c -> {
            Assertions.assertThat(c.event()).isEqualTo(NotificationEvent.ATTENDANCE_CHECK_IN);
            Assertions.assertThat(c.variables())
                    .containsEntry("studentName", "김민지")
                    .containsEntry("taggedAt", "08:30");
        });
        // 부·모가 각자 받는다 — dedup_key 가 전역 유니크라 계정까지 넣지 않으면 한 명만 받는다
        Assertions.assertThat(sent).extracting(NotificationCommand::dedupKey)
                .containsExactly("ATTENDANCE_CHECK_IN:7:101", "ATTENDANCE_CHECK_IN:7:102");
    }

    @Test
    @DisplayName("★★ 지각도 등원 알림으로 나간다 — 안 보내면 미등원 알림 받은 학부모가 결석으로 안다")
    void lateIsNotifiedAsCheckIn() {
        withGuardians(father);

        notifier.notifyTagged(minji, tagging(AttendanceEventType.LATE, 8L, LocalTime.of(9, 30)));

        Assertions.assertThat(captured()).singleElement()
                .extracting(NotificationCommand::event)
                .isEqualTo(NotificationEvent.ATTENDANCE_CHECK_IN);
    }

    @Test
    @DisplayName("★ 사유 외출도 외출 알림이다 — 사유 제출은 벌점·집계의 구분이고 학부모에겐 같은 사실이다")
    void excusedOutingIsStillOuting() {
        withGuardians(father);

        notifier.notifyTagged(minji,
                tagging(AttendanceEventType.EXCUSED_OUTING, 9L, LocalTime.of(13, 0)));

        Assertions.assertThat(captured()).singleElement()
                .extracting(NotificationCommand::event)
                .isEqualTo(NotificationEvent.ATTENDANCE_OUTING);
    }

    @Test
    @DisplayName("★★ 같은 날 외출이 두 번이면 두 번 나간다 — 일자로 묶으면 둘째부터 사라진다")
    void secondOutingOfTheDayIsNotSuppressed() {
        withGuardians(father);

        notifier.notifyTagged(minji, tagging(AttendanceEventType.OUTING, 21L, LocalTime.of(10, 0)));
        notifier.notifyTagged(minji, tagging(AttendanceEventType.OUTING, 22L, LocalTime.of(15, 0)));

        // 중복 방지 키가 **원장 행 단위**다. 날짜가 들어가면 두 키가 같아져 둘째가 조용히 사라진다
        Assertions.assertThat(captured()).extracting(NotificationCommand::dedupKey)
                .containsExactly("ATTENDANCE_OUTING:21:101", "ATTENDANCE_OUTING:22:101");
    }

    @Test
    @DisplayName("★★ 발송이 터져도 호출부로 전파하지 않는다 — 알림이 등원 사실을 되돌리면 안 된다")
    void sendFailureIsSwallowed() {
        withGuardians(father);
        when(notificationService.send(any()))
                .thenThrow(new IllegalStateException("알림 템플릿이 없습니다"));

        assertThatCode(() -> notifier.notifyTagged(minji,
                tagging(AttendanceEventType.CHECK_OUT, 31L, LocalTime.of(22, 0))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("연결된 학부모가 없으면 아무것도 보내지 않는다")
    void noGuardianNoSend() {
        withGuardians();

        notifier.notifyTagged(minji, tagging(AttendanceEventType.CHECK_IN, 41L, LocalTime.of(8, 0)));

        verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("하원·복귀·조퇴가 각자 다른 이벤트로 간다")
    void remainingTypesMapOneToOne() {
        withGuardians(father);

        notifier.notifyTagged(minji, tagging(AttendanceEventType.CHECK_OUT, 51L, LocalTime.of(22, 0)));
        notifier.notifyTagged(minji, tagging(AttendanceEventType.RETURN, 52L, LocalTime.of(14, 0)));
        notifier.notifyTagged(minji, tagging(AttendanceEventType.EARLY_LEAVE, 53L, LocalTime.of(16, 0)));

        Assertions.assertThat(captured()).extracting(NotificationCommand::event)
                .containsExactly(NotificationEvent.ATTENDANCE_CHECK_OUT,
                        NotificationEvent.ATTENDANCE_RETURN,
                        NotificationEvent.ATTENDANCE_EARLY_LEAVE);
    }
}
