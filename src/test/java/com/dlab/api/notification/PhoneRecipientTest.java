package com.dlab.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationStatus;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 없는 수신자에게 보내는 알림.
 *
 * <h2>★ 왜 필요한가 — 계정 없는 수신자가 둘이고 둘 다 확정된 요구다</h2>
 * <ul>
 *   <li><b>입학 대기자</b>(디랩 1019, 7번 대기자 안내톡) — {@code admission_reservation} 은
 *       학생이 아니라 계정도 {@code student} 행도 없고 번호만 들고 있다</li>
 *   <li><b>급식업체</b>(0826 확정 — 환불정보를 업체에 알림톡으로 즉시 발송) — 사업자다</li>
 * </ul>
 *
 * <p>{@code recipient_account_id} 가 {@code NOT NULL} 이어서 둘 다 발송 경로를 아예 못 탔다.
 *
 * <h2>★★ 전용 경로를 따로 만들지 않는 것이 요점이다</h2>
 * 대기자용 발송기를 따로 두면 <b>발송 이력이 두 곳으로 갈라져 「왜 안 갔나」에 답할 수 없다.</b>
 * 그래서 같은 {@code notification_log} 에 남긴다 — 이 테스트가 그걸 고정한다.
 */
@SpringBootTest
@Transactional
class PhoneRecipientTest {

    @Autowired NotificationService notificationService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) java.time.LocalDate.now(clock).getYear();
        academy = new Academy("PR1", "번호수신테스트", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();
    }

    @Test
    @DisplayName("★★ 계정이 없어도 번호로 이력이 남는다 — 같은 notification_log 에 들어간다")
    void phoneRecipientIsLoggedInTheSameTable() {
        NotificationLog log = notificationService.send(NotificationCommand.toPhone(
                // 문구가 비어 있는 템플릿이라 발송은 SKIPPED 로 떨어진다 — 이력이 남는지가 요점
                NotificationEvent.MISSING_ATTENDANCE,
                "010-1234-5678", "대기자홍길동", academy, year,
                Map.of("studentName", "홍길동"), "PHONE_TEST:1"));

        assertThat(log).isNotNull();
        assertThat(log.getRecipient()).isNull();
        assertThat(log.resolvedPhone()).isEqualTo("010-1234-5678");
        assertThat(log.resolvedRecipientName()).isEqualTo("대기자홍길동");
    }

    @Test
    @DisplayName("★★ 계정도 번호도 없으면 커맨드를 만들 때 막는다 — DB 까지 가면 본 행위가 되돌아간다")
    void neitherAccountNorPhoneIsRejectedEarly() {
        assertThatThrownBy(() -> NotificationCommand.toPhone(
                NotificationEvent.MISSING_ATTENDANCE, null, "이름", academy, year,
                Map.of(), "PHONE_TEST:2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("수신자");

        assertThatThrownBy(() -> NotificationCommand.toPhone(
                NotificationEvent.MISSING_ATTENDANCE, "   ", "이름", academy, year,
                Map.of(), "PHONE_TEST:3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ 번호 수신자도 중복 방지가 돈다 — 두 번 눌러도 한 번만 남는다")
    void phoneRecipientIsDeduped() {
        NotificationLog first = notificationService.send(NotificationCommand.toPhone(
                NotificationEvent.MISSING_ATTENDANCE, "010-0000-0001", "대기자", academy, year,
                Map.of("studentName", "홍길동"), "PHONE_DEDUP:1"));
        NotificationLog second = notificationService.send(NotificationCommand.toPhone(
                NotificationEvent.MISSING_ATTENDANCE, "010-0000-0001", "대기자", academy, year,
                Map.of("studentName", "홍길동"), "PHONE_DEDUP:1"));

        assertThat(first).isNotNull();
        // 중복은 null 로 돌아온다(기존 규약) — 번호 수신자도 같다
        assertThat(second).isNull();
    }

    @Test
    @DisplayName("문구 미확정 템플릿이면 번호 수신자도 SKIPPED 로 남는다 — 안 나간 사실이 보여야 한다")
    void unconfirmedTemplateIsSkippedNotSent() {
        NotificationLog log = notificationService.send(NotificationCommand.toPhone(
                NotificationEvent.MISSING_ATTENDANCE, "010-0000-0002", "대기자", academy, year,
                // 템플릿이 요구하는 변수를 다 채운다 — 하나라도 빠지면 「변수 누락」으로
                // 먼저 걸려서 문구 미확정 분기까지 가지 않는다(그 순서가 맞다)
                Map.of("studentName", "홍길동", "attendanceDate", "2026-10-20"),
                "PHONE_SKIP:1"));

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(log.getFailReason()).contains("문구 미확정");
    }
}
