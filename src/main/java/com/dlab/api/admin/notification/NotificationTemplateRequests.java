package com.dlab.api.admin.notification;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.RecipientType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 알림 템플릿 요청 DTO. */
public final class NotificationTemplateRequests {

    private NotificationTemplateRequests() {
    }

    public record NotificationTemplateCreate(
            @NotNull(message = "이벤트는 필수입니다.") NotificationEvent event,
            @NotNull(message = "채널은 필수입니다.") NotificationChannel channel,
            RecipientType recipientType,
            /** 쉼표 구분. studentName은 자동으로 포함되므로 적지 않아도 된다. */
            String requiredVariables) {
    }

    public record UpdateContent(
            @NotNull @Size(max = 200) String titleTemplate,
            @NotNull String bodyTemplate,
            String requiredVariables,
            /** 생략하면 미확정으로 둔다 — 실수로 확정되는 쪽이 위험하다. */
            Boolean contentConfirmed) {
    }

    public record UpdateMapping(NotificationChannel channel, RecipientType recipientType) {
    }

    public record ChangeActive(@NotNull(message = "활성 여부는 필수입니다.") Boolean active) {
    }

    public record SubmitReview(
            @NotBlank(message = "카카오 템플릿 코드는 필수입니다.") @Size(max = 60)
            String kakaoTemplateCode) {
    }

    public record ReviewResult(
            @NotNull(message = "승인 여부는 필수입니다.") Boolean approved,
            @Size(max = 500) String note) {
    }
}
