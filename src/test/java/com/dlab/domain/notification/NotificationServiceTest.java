package com.dlab.domain.notification;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.notification.entity.*;
import com.dlab.domain.notification.repository.NotificationLogRepository;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationSender;
import com.dlab.domain.appconfig.repository.NotificationPreferenceRepository;
import com.dlab.domain.notification.service.NotificationOptOutPolicy;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private NotificationTemplateRepository templateRepository;
    private NotificationLogRepository logRepository;
    private NotificationPreferenceRepository preferenceRepository;
    private RecordingSender sender;
    private NotificationService service;

    /** 실제 발송 대신 호출 내역만 모아두는 테스트용 구현. */
    static class RecordingSender implements NotificationSender {
        final List<NotificationLog> sent = new ArrayList<>();

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.KAKAO_ALIMTALK;
        }

        @Override
        public void send(NotificationLog log, NotificationTemplate template) {
            sent.add(log);
        }
    }

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        logRepository = mock(NotificationLogRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        sender = new RecordingSender();
        // ★ 수신거부 판정은 실물 정책을 쓴다 — 필수 알림 목록까지 같이 고정된다
        // ★ 허용목록도 실물을 쓴다. 설정이 비어 있으면 전면 개방이라 기존 단정이 그대로 선다 —
        //   모킹하면 「비었을 때 막지 않는다」는 규약이 테스트에서 빠진다
        service = new NotificationService(templateRepository, logRepository,
                new NotificationOptOutPolicy(preferenceRepository),
                new com.dlab.domain.notification.service.RecipientAllowlist(""),
                List.of(sender));

        when(logRepository.save(any(NotificationLog.class))).thenAnswer(i -> i.getArgument(0));
    }

    private NotificationTemplate template(String title, String body, String requiredVariables,
                                          boolean contentConfirmed) {
        NotificationTemplate t = BeanUtils.instantiateClass(NotificationTemplate.class);
        ReflectionTestUtils.setField(t, "eventCode", NotificationEvent.MISSING_ATTENDANCE);
        ReflectionTestUtils.setField(t, "channel", NotificationChannel.KAKAO_ALIMTALK);
        ReflectionTestUtils.setField(t, "titleTemplate", title);
        ReflectionTestUtils.setField(t, "bodyTemplate", body);
        ReflectionTestUtils.setField(t, "requiredVariables", requiredVariables);
        ReflectionTestUtils.setField(t, "contentConfirmed", contentConfirmed);
        ReflectionTestUtils.setField(t, "active", true);
        // ★ 알림톡은 승인된 템플릿 코드가 있어야 발송된다 — 없으면 건너뛴다
        ReflectionTestUtils.setField(t, "kakaoTemplateCode", "UM_8230");
        return t;
    }

    private Account recipient() {
        return mock(Account.class);
    }

    /** id 가 있는 수신 계정. 수신거부는 계정 단위라 id 가 없으면 판정 자체가 돌지 않는다. */
    private Account recipient(long accountId) {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(accountId);
        return account;
    }

    /** 수신 거부가 가능한(= 필수가 아닌) 이벤트 템플릿. */
    private NotificationTemplate optionalTemplate() {
        NotificationTemplate t = template("[{studentName}] 등원", "{studentName} 학생 등원",
                "studentName", true);
        ReflectionTestUtils.setField(t, "eventCode", NotificationEvent.ATTENDANCE_CHECK_IN);
        return t;
    }

    @Test
    @DisplayName("★ 학생명이 빠지면 보내지 않는다 — 다만 예외를 던지지는 않는다")
    void studentNameIsAlwaysRequired() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(template("미등원", "{studentName} 학생 미등원", "studentName", true)));

        NotificationCommand command = new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null, null, null, Map.of(), null);

        // ★ 예외를 던지면 알림 하나 때문에 본 행위가 실패한다. 실제로 템플릿 설정이
        //   어긋나 방화벽 해제 신청이 통째로 500 이 됐다 — 화면에는 원인이 안 보인다.
        NotificationLog log = service.send(command);

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(log.getFailReason()).contains("studentName");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    @DisplayName("템플릿이 요구하는 다른 변수도 누락되면 실패시킨다")
    void otherRequiredVariablesAreChecked() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(
                        template("미등원", "{attendanceDate}", "studentName,attendanceDate", true)));

        NotificationCommand command = new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null, null, null,
                Map.of("studentName", "홍길동"), null);

        NotificationLog log = service.send(command);

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(log.getFailReason()).contains("attendanceDate");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    @DisplayName("문구가 확정된 템플릿은 변수를 치환해 실제로 발송한다")
    void sendsWhenContentConfirmed() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(template("[{studentName}] 미등원",
                        "{studentName} 학생이 {attendanceDate}에 등원하지 않았습니다.",
                        "studentName,attendanceDate", true)));

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null, null, null,
                Map.of("studentName", "홍길동", "attendanceDate", "7월 31일"), null));

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(log.getTitle()).isEqualTo("[홍길동] 미등원");
        assertThat(log.getBody()).isEqualTo("홍길동 학생이 7월 31일에 등원하지 않았습니다.");
        assertThat(sender.sent).hasSize(1);
    }

    @Test
    @DisplayName("문구 미확정 템플릿은 이력만 남기고 실제 발송은 하지 않는다")
    void skipsWhenContentNotConfirmed() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(template("", "", "studentName", false)));

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null, null, null,
                Map.of("studentName", "홍길동"), null));

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(sender.sent).isEmpty();
    }

    @Test
    @DisplayName("같은 dedupKey로 이미 보낸 알림은 다시 보내지 않는다")
    void skipsDuplicateByDedupKey() {
        when(logRepository.existsByDedupKey("MISSING_ATTENDANCE:1:2026-07-31:2")).thenReturn(true);

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null, null, null,
                Map.of("studentName", "홍길동"), "MISSING_ATTENDANCE:1:2026-07-31:2"));

        assertThat(log).isNull();
        assertThat(sender.sent).isEmpty();
        verify(logRepository, never()).save(any());
    }

    @Test
    @DisplayName("★★ 알림톡 템플릿 코드가 없으면 건너뛴다 — 문자로 떨어뜨리지 않는다")
    void skipsAlimtalkWithoutTemplateCode() {
        NotificationTemplate t = template("[{studentName}] 미등원", "{studentName} 학생 미등원",
                "studentName", true);
        ReflectionTestUtils.setField(t, "kakaoTemplateCode", null);
        when(templateRepository.findByEventCode(any())).thenReturn(Optional.of(t));

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null, null, null,
                Map.of("studentName", "홍길동"), null));

        // 카카오는 승인된 tpl_code 만 받는다. 문자로 대신 보내면 학부모가 받는 문구가
        // 심사받은 것과 달라지고, SMS 폴백은 0803 에 폐기됐다(E-5).
        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(log.getFailReason()).contains("템플릿 코드");
        assertThat(sender.sent).isEmpty();
    }

    // ── 수신 거부 (자동 발송 경로) ────────────────────────────────────────

    @Test
    @DisplayName("★★ 수신 거부한 사람에게는 보내지 않고 SKIPPED 로 남긴다")
    void skipsOptedOutRecipient() {
        when(templateRepository.findByEventCode(any())).thenReturn(Optional.of(optionalTemplate()));
        when(preferenceRepository.existsByAccountIdAndEventCodeAndEnabledFalse(
                7L, NotificationEvent.ATTENDANCE_CHECK_IN)).thenReturn(true);

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.ATTENDANCE_CHECK_IN, recipient(7L), null, null, null,
                Map.of("studentName", "홍길동"), null));

        // ★ 조용히 버리지 않는다 — 「보냈는데 안 왔다」 문의에 이 이력으로 답한다
        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(log.getFailReason()).contains("수신 거부");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    @DisplayName("거부하지 않은 사람에게는 그대로 간다 — 행이 없으면 수신이다")
    void sendsWhenNotOptedOut() {
        when(templateRepository.findByEventCode(any())).thenReturn(Optional.of(optionalTemplate()));
        when(preferenceRepository.existsByAccountIdAndEventCodeAndEnabledFalse(
                7L, NotificationEvent.ATTENDANCE_CHECK_IN)).thenReturn(false);

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.ATTENDANCE_CHECK_IN, recipient(7L), null, null, null,
                Map.of("studentName", "홍길동"), null));

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sender.sent).hasSize(1);
    }

    @Test
    @DisplayName("★★ 미등원은 꺼진 행이 있어도 나간다 — 거부 불가 알림이다")
    void mandatoryEventIgnoresPreference() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(template("[{studentName}] 미등원", "{studentName} 미등원",
                        "studentName", true)));
        // 앱 API 는 끄는 것 자체를 거절하지만, 과거 데이터·직접 UPDATE 로 남아 있을 수 있다
        when(preferenceRepository.existsByAccountIdAndEventCodeAndEnabledFalse(
                7L, NotificationEvent.MISSING_ATTENDANCE)).thenReturn(true);

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(7L), null, null, null,
                Map.of("studentName", "홍길동"), null));

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sender.sent).hasSize(1);
    }

    @Test
    @DisplayName("계정 없는 수신자(대기자·급식업체)는 설정을 가질 수 없어 그대로 간다")
    void phoneRecipientHasNoPreference() {
        when(templateRepository.findByEventCode(any())).thenReturn(Optional.of(optionalTemplate()));

        NotificationLog log = service.send(NotificationCommand.toPhone(
                NotificationEvent.ATTENDANCE_CHECK_IN, "010-1234-5678", "대기자",
                null, null, Map.of("studentName", "홍길동"), null));

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(preferenceRepository, never())
                .existsByAccountIdAndEventCodeAndEnabledFalse(any(), any());
    }
}
