package com.dlab.api.admin.notification;

import com.dlab.domain.notification.entity.NotificationTemplate;

import java.time.Instant;

/**
 * 알림 템플릿 한 줄.
 *
 * @param sendable 지금 실제로 발송되는가. <b>세 축(활성·문구확정·심사통과)이 모두 통과해야 한다</b> —
 *                 화면에서 이 값 하나만 보면 "왜 알림이 안 가지"를 바로 알 수 있다
 */
public record NotificationTemplateResponse(
        Long id, String event, String channel, String recipientType,
        String titleTemplate, String bodyTemplate, String requiredVariables,
        boolean contentConfirmed, boolean active,
        String reviewStatus, String reviewNote, Instant reviewedAt,
        String kakaoTemplateCode, boolean sendable) {

    public static NotificationTemplateResponse from(NotificationTemplate t) {
        return new NotificationTemplateResponse(
                t.getId(), t.getEventCode().name(), t.getChannel().name(),
                t.getRecipientType().name(),
                t.getTitleTemplate(), t.getBodyTemplate(), t.getRequiredVariables(),
                t.isContentConfirmed(), t.isActive(),
                t.getReviewStatus().name(), t.getReviewNote(), t.getReviewedAt(),
                t.getKakaoTemplateCode(), t.isSendable());
    }
}
