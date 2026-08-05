package com.dlab.api.admin.student;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentStatusLog;

import java.time.Instant;

/** 재적 상태 변경 이력 한 건. */
public record StatusLogResponse(
        Long id,
        EnrollmentStatus fromStatus,
        EnrollmentStatus toStatus,
        String reason,
        Instant changedAt,
        Long changedBy) {

    public static StatusLogResponse from(StudentStatusLog log) {
        return new StatusLogResponse(log.getId(), log.getFromStatus(), log.getToStatus(),
                log.getReason(), log.getChangedAt(), log.getCreatedBy());
    }
}
