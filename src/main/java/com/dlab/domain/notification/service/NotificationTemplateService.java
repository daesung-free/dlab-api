package com.dlab.domain.notification.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.notification.entity.*;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 알림 템플릿 관리 (실행가이드 P1-12 "CRUD · 심사상태 · 채널 매핑표").
 *
 * <p><b>이 서비스는 문구를 "관리"만 한다.</b> 실제 발송 판정은 {@link NotificationService}가
 * 하고, 여기서 만든 상태값을 그쪽이 읽는다.
 *
 * <p><b>발송 가능 여부는 세 축이 모두 통과해야 한다</b> — 헷갈리기 쉬운 지점이다:
 * <ul>
 *   <li>{@code active} — 관리자가 켰는가</li>
 *   <li>{@code contentConfirmed} — 운영팀이 문구를 확정했는가 (I-4)</li>
 *   <li>{@code reviewStatus} — 카카오 심사를 통과했는가 (E-5, 알림톡만)</li>
 * </ul>
 * 하나로 합치면 "문구는 정해졌는데 심사 대기 중" 같은 실제 상태를 표현할 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    private final NotificationTemplateRepository templateRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<NotificationTemplate> findAll() {
        return templateRepository.findAllByDeletedFalseOrderByEventCode();
    }

    /** 심사 진행 중인 것만 — 카카오 심사는 리드타임이 길어 따로 본다(E-5). */
    @Transactional(readOnly = true)
    public List<NotificationTemplate> findInReview() {
        return templateRepository.findByReviewStatusInAndDeletedFalseOrderByEventCode(
                List.of(ReviewStatus.DRAFT, ReviewStatus.SUBMITTED, ReviewStatus.REJECTED));
    }

    /**
     * 템플릿 생성.
     *
     * <p>이벤트당 하나만 둔다(스키마 UNIQUE) — 같은 이벤트에 템플릿이 둘이면
     * 발송 시 무엇을 쓸지 정할 수 없다. <b>알림톡·FCM 병행 발송은 시트가 아직 TBD</b>라
     * (A-C3 *"Daily Report … 알림톡 병행 TBD"*) 지금 열지 않는다.
     */
    @Transactional
    public NotificationTemplate create(NotificationEvent event, NotificationChannel channel,
                                       RecipientType recipientType, String requiredVariables) {
        if (templateRepository.existsByEventCode(event)) {
            throw new BusinessException(ErrorCode.NOTIFICATION_TEMPLATE_DUPLICATED);
        }
        NotificationTemplate template = templateRepository.save(new NotificationTemplate(
                event, channel,
                recipientType == null ? RecipientType.PARENT : recipientType,
                requiredVariables == null ? "" : requiredVariables));
        log.info("알림 템플릿 생성: event={}, channel={}", event, channel);
        return template;
    }

    /**
     * 문구 수정.
     *
     * <p><b>확정하려면 문구가 비어 있으면 안 된다.</b> 빈 문구로 확정 처리되면
     * 발송 조건을 통과해 <b>내용 없는 알림이 실제로 나간다.</b>
     *
     * <p>학생명 변수는 강제하지 않는다 — {@code requiredVariableSet()}이 항상 포함시키므로
     * 발송 시점에 {@link NotificationService}가 검증한다.
     */
    @Transactional
    public NotificationTemplate updateContent(Long id, String title, String body,
                                              String requiredVariables, boolean contentConfirmed) {
        NotificationTemplate template = require(id);
        if (contentConfirmed && (isBlank(title) || isBlank(body))) {
            throw new BusinessException(ErrorCode.NOTIFICATION_TEMPLATE_CONTENT_EMPTY);
        }
        ReviewStatus before = template.getReviewStatus();
        template.updateContent(title, body, requiredVariables, contentConfirmed);
        if (before == ReviewStatus.APPROVED
                && template.getReviewStatus() == ReviewStatus.DRAFT) {
            // 승인된 문구를 고치면 재심사 대상이 된다. 조용히 넘어가면
            // "승인됨"으로 보이는데 카카오가 발송을 거절하는 상태가 된다.
            log.warn("승인된 알림톡 문구가 변경돼 재심사 대상이 됐습니다: event={}",
                    template.getEventCode());
        }
        return template;
    }

    /** 채널·수신자 변경 (A-C3 매핑표). */
    @Transactional
    public NotificationTemplate updateMapping(Long id, NotificationChannel channel,
                                              RecipientType recipientType) {
        NotificationTemplate template = require(id);
        template.changeChannel(channel, recipientType);
        return template;
    }

    @Transactional
    public NotificationTemplate changeActive(Long id, boolean active) {
        NotificationTemplate template = require(id);
        template.changeActive(active);
        return template;
    }

    // ── 카카오 사전심사 (E-5) ────────────────────────────────────

    /**
     * 카카오에 제출.
     *
     * <p><b>FCM 템플릿은 제출 대상이 아니다</b> — 심사 자체가 없다. 막지 않으면
     * 심사 목록에 FCM이 섞여 "왜 안 넘어가지"를 한참 들여다보게 된다.
     */
    @Transactional
    public NotificationTemplate submitForReview(Long id, String kakaoTemplateCode) {
        NotificationTemplate template = require(id);
        if (template.getChannel() != NotificationChannel.KAKAO_ALIMTALK) {
            throw new BusinessException(ErrorCode.NOTIFICATION_REVIEW_NOT_APPLICABLE);
        }
        if (isBlank(template.getTitleTemplate()) || isBlank(template.getBodyTemplate())) {
            throw new BusinessException(ErrorCode.NOTIFICATION_TEMPLATE_CONTENT_EMPTY,
                    "문구가 비어 있어 심사를 제출할 수 없습니다.");
        }
        template.submitForReview(kakaoTemplateCode, Instant.now(clock));
        log.info("알림톡 심사 제출: event={}, code={}", template.getEventCode(), kakaoTemplateCode);
        return template;
    }

    /**
     * 심사 결과 반영.
     *
     * <p>카카오 심사 결과는 <b>사람이 보고 입력</b>한다 — 자동 연동 창구가 없다(E-5 미해소).
     */
    @Transactional
    public NotificationTemplate applyReviewResult(Long id, boolean approved, String note) {
        NotificationTemplate template = require(id);
        if (template.getChannel() != NotificationChannel.KAKAO_ALIMTALK) {
            throw new BusinessException(ErrorCode.NOTIFICATION_REVIEW_NOT_APPLICABLE);
        }
        Instant now = Instant.now(clock);
        if (approved) {
            template.approveReview(now);
        } else {
            template.rejectReview(note, now);
        }
        log.info("알림톡 심사 결과: event={}, approved={}", template.getEventCode(), approved);
        return template;
    }

    private NotificationTemplate require(Long id) {
        return templateRepository.findById(id)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
