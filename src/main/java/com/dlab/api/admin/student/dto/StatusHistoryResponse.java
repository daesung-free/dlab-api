package com.dlab.api.admin.student.dto;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.EnrollmentStatusHistory;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 상태 변경 이력 한 줄.
 *
 * @param changedAt 처리 시각. {@code effectiveDate}와 다를 수 있다 — 소급 처리
 * @param changedBy 처리자 계정 id. {@code 0}은 시스템(배치)이다
 */
public record StatusHistoryResponse(
        Long id,
        EnrollmentStatus fromStatus,
        EnrollmentStatus toStatus,
        LocalDate effectiveDate,
        String reason,
        Instant changedAt,
        Long changedBy
) {

    public static StatusHistoryResponse from(EnrollmentStatusHistory h) {
        return new StatusHistoryResponse(
                h.getId(),
                h.getFromStatus(),
                h.getToStatus(),
                h.getEffectiveDate(),
                h.getReason(),
                h.getCreatedAt(),
                h.getCreatedBy());
    }
}
