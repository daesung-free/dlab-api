package com.dlab.domain.notification.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.notification.entity.*;
import com.dlab.domain.notification.repository.NotificationLogRepository;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 알림 발송 진입점.
 *
 * <p>이벤트 → 채널 매핑과 문구는 전부 {@link NotificationTemplate}(DB)에서 온다. 도메인 코드에
 * 문구를 하드코딩하지 않는다 — 문구는 아직 운영팀과 확정 전인 블로커(I-4)이기 때문이다.
 * 문구가 미확정인 템플릿은 이력만 남기고 실제 발송은 건너뛴다.
 */
@Slf4j
@Service
public class NotificationService {

    private final NotificationTemplateRepository templateRepository;
    private final NotificationLogRepository logRepository;
    private final Map<NotificationChannel, NotificationSender> senders;

    public NotificationService(NotificationTemplateRepository templateRepository,
                               NotificationLogRepository logRepository,
                               List<NotificationSender> senders) {
        this.templateRepository = templateRepository;
        this.logRepository = logRepository;
        this.senders = senders.stream()
                .collect(Collectors.toMap(NotificationSender::channel, s -> s));
    }

    /**
     * @return 발송 이력. 중복(dedupKey 충돌)으로 건너뛴 경우 null.
     */
    @Transactional
    public NotificationLog send(NotificationCommand command) {
        if (command.dedupKey() != null && logRepository.existsByDedupKey(command.dedupKey())) {
            log.debug("중복 알림 건너뜀: {}", command.dedupKey());
            return null;
        }

        NotificationTemplate template = templateRepository.findByEventCode(command.event())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND,
                        "알림 템플릿이 없습니다: " + command.event()));

        validateVariables(template, command.variables());

        NotificationLog notificationLog = new NotificationLog(
                command.academy(),
                command.year(),
                command.event(),
                template.getChannel(),
                command.recipient(),
                command.student(),
                render(template.getTitleTemplate(), command.variables()),
                render(template.getBodyTemplate(), command.variables()),
                command.variables(),
                command.dedupKey());

        try {
            logRepository.save(notificationLog);
        } catch (DataIntegrityViolationException e) {
            // dedup_key 유니크 위반 — 다른 실행이 같은 알림을 먼저 기록했다는 뜻이므로 정상 종료로 취급
            log.debug("중복 알림 동시 기록 감지: {}", command.dedupKey());
            return null;
        }

        dispatch(template, notificationLog);
        return notificationLog;
    }

    private void dispatch(NotificationTemplate template, NotificationLog notificationLog) {
        if (!template.isActive()) {
            notificationLog.markSkipped("비활성 템플릿");
            return;
        }
        if (!template.isContentConfirmed()) {
            // 문구 미확정 블로커(I-4). 확정 전에 임시 문구가 실제로 나가면 안 된다.
            notificationLog.markSkipped("문구 미확정 템플릿");
            log.info("문구 미확정으로 발송 보류: event={}", notificationLog.getEventCode());
            return;
        }
        if (!template.getReviewStatus().canSend()) {
            // 카카오 알림톡 사전심사(E-5) 미통과. 문구가 확정돼도 심사를 못 넘으면
            // 카카오가 발송을 거절한다 — 우리가 먼저 걸러야 실패 이력이 어지럽지 않다.
            notificationLog.markSkipped("알림톡 심사 미통과: " + template.getReviewStatus());
            log.info("심사 미통과로 발송 보류: event={}, status={}",
                    notificationLog.getEventCode(), template.getReviewStatus());
            return;
        }

        NotificationSender sender = senders.get(template.getChannel());
        if (sender == null) {
            notificationLog.markSkipped("발송 구현체 없음: " + template.getChannel());
            return;
        }

        try {
            sender.send(notificationLog);
            notificationLog.markSent();
        } catch (Exception e) {
            log.error("알림 발송 실패: event={}", notificationLog.getEventCode(), e);
            notificationLog.markFailed(e.getMessage());
        }
    }

    /**
     * 템플릿이 요구하는 변수가 모두 채워졌는지 확인한다.
     * 학생명은 항상 필수라서 누락되면 발송 자체를 막는다 — 다자녀 학부모가
     * "이거 누구 얘기지" 헷갈리는 알림이 나가면 안 되기 때문.
     */
    private void validateVariables(NotificationTemplate template, Map<String, String> variables) {
        Set<String> required = template.requiredVariableSet();
        List<String> missing = required.stream()
                .filter(name -> {
                    String value = variables.get(name);
                    return value == null || value.isBlank();
                })
                .toList();

        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_VARIABLE_MISSING,
                    "알림 변수 누락(%s): %s".formatted(template.getEventCode(), String.join(", ", missing)));
        }
    }

    /** {변수명} 치환. */
    private String render(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
