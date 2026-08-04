package com.dlab.api.admin.student.dto;

import com.dlab.domain.user.entity.EnrollmentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 재원 상태 변경 요청.
 *
 * @param effectiveDate 효력 발생일. 없으면 오늘. 소급 처리가 실제로 있어 받아둔다 —
 *                      환불 일할계산(I-26)이 처리일이 아니라 이 날짜를 쓴다
 * @param reason        변경 사유
 */
public record StatusChangeRequest(
        @NotNull EnrollmentStatus status,
        LocalDate effectiveDate,
        @Size(max = 500) String reason
) {
}
