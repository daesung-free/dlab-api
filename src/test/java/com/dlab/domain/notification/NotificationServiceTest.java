package com.dlab.domain.notification;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.notification.entity.*;
import com.dlab.domain.notification.repository.NotificationLogRepository;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationSender;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.UserAccount;
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
        public void send(NotificationLog log) {
            sent.add(log);
        }
    }

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        logRepository = mock(NotificationLogRepository.class);
        sender = new RecordingSender();
        service = new NotificationService(templateRepository, logRepository, List.of(sender));

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
        return t;
    }

    private UserAccount recipient() {
        return mock(UserAccount.class);
    }

    @Test
    @DisplayName("학생명 변수가 빠지면 발송하지 않고 실패시킨다")
    void studentNameIsAlwaysRequired() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(template("미등원", "{studentName} 학생 미등원", "studentName", true)));

        NotificationCommand command = new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null, Map.of(), null);

        assertThatThrownBy(() -> service.send(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_VARIABLE_MISSING);

        assertThat(sender.sent).isEmpty();
    }

    @Test
    @DisplayName("템플릿이 요구하는 다른 변수도 누락되면 실패시킨다")
    void otherRequiredVariablesAreChecked() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(
                        template("미등원", "{attendanceDate}", "studentName,attendanceDate", true)));

        NotificationCommand command = new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null,
                Map.of("studentName", "홍길동"), null);

        assertThatThrownBy(() -> service.send(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("attendanceDate");
    }

    @Test
    @DisplayName("문구가 확정된 템플릿은 변수를 치환해 실제로 발송한다")
    void sendsWhenContentConfirmed() {
        when(templateRepository.findByEventCode(any()))
                .thenReturn(Optional.of(template("[{studentName}] 미등원",
                        "{studentName} 학생이 {attendanceDate}에 등원하지 않았습니다.",
                        "studentName,attendanceDate", true)));

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null,
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
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null,
                Map.of("studentName", "홍길동"), null));

        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(sender.sent).isEmpty();
    }

    @Test
    @DisplayName("같은 dedupKey로 이미 보낸 알림은 다시 보내지 않는다")
    void skipsDuplicateByDedupKey() {
        when(logRepository.existsByDedupKey("MISSING_ATTENDANCE:1:2026-07-31:2")).thenReturn(true);

        NotificationLog log = service.send(new NotificationCommand(
                NotificationEvent.MISSING_ATTENDANCE, recipient(), null,
                Map.of("studentName", "홍길동"), "MISSING_ATTENDANCE:1:2026-07-31:2"));

        assertThat(log).isNull();
        assertThat(sender.sent).isEmpty();
        verify(logRepository, never()).save(any());
    }
}
