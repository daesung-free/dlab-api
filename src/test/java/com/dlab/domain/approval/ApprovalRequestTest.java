package com.dlab.domain.approval;

import com.dlab.domain.approval.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승인 3케이스 판별.
 * 담당선생님 승인 2케이스는 학부모 안내 문구가 서로 달라야 하므로 반드시 구분돼야 한다.
 */
class ApprovalRequestTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-07-31T01:00:00Z");
    private static final short TIMEOUT_MINUTES = 10;

    /** 판별 로직은 시각과 승인 주체만 쓰므로 연관 엔티티는 채우지 않는다. */
    private ApprovalRequest request() {
        ApprovalRequest request = BeanUtils.instantiateClass(ApprovalRequest.class);
        ReflectionTestUtils.setField(request, "requestedAt", REQUESTED_AT);
        ReflectionTestUtils.setField(request, "timeoutMinutes", TIMEOUT_MINUTES);
        ReflectionTestUtils.setField(request, "escalationAt", REQUESTED_AT.plusSeconds(TIMEOUT_MINUTES * 60L));
        ReflectionTestUtils.setField(request, "status", ApprovalStatus.PENDING);
        return request;
    }

    @Test
    @DisplayName("타임아웃 기준선은 신청시각 + 10분")
    void escalationAtIsTenMinutesAfterRequest() {
        assertThat(request().getEscalationAt()).isEqualTo(REQUESTED_AT.plusSeconds(600));
    }

    @Test
    @DisplayName("케이스1 - 타임아웃 전 학부모 승인")
    void parentApprovalInTime() {
        assertThat(request().decideResolutionCase(ApproverType.PARENT, REQUESTED_AT.plusSeconds(60)))
                .isEqualTo(ResolutionCase.PARENT_IN_TIME);
    }

    @Test
    @DisplayName("케이스1 - 학부모는 타임아웃이 지나서 승인해도 정상 승인으로 본다")
    void parentApprovalAfterTimeoutIsStillParentCase() {
        assertThat(request().decideResolutionCase(ApproverType.PARENT, REQUESTED_AT.plusSeconds(1200)))
                .isEqualTo(ResolutionCase.PARENT_IN_TIME);
    }

    @Test
    @DisplayName("케이스2 - 타임아웃 경과 후 담당선생님 승인 (에스컬레이션)")
    void staffApprovalAfterTimeout() {
        assertThat(request().decideResolutionCase(ApproverType.TEACHER, REQUESTED_AT.plusSeconds(601)))
                .isEqualTo(ResolutionCase.STAFF_AFTER_TIMEOUT);
    }

    @Test
    @DisplayName("케이스3 - 타임아웃 전 담당선생님이 먼저 승인 (케이스2와 다른 문구가 나가야 함)")
    void staffApprovalBeforeTimeout() {
        ResolutionCase result = request()
                .decideResolutionCase(ApproverType.TEACHER, REQUESTED_AT.plusSeconds(599));

        assertThat(result).isEqualTo(ResolutionCase.STAFF_BEFORE_TIMEOUT);
        assertThat(result).isNotEqualTo(ResolutionCase.STAFF_AFTER_TIMEOUT);
    }

    @Test
    @DisplayName("경계 - 타임아웃 정각 승인은 에스컬레이션으로 본다")
    void staffApprovalExactlyAtTimeout() {
        assertThat(request().decideResolutionCase(ApproverType.TEACHER, REQUESTED_AT.plusSeconds(600)))
                .isEqualTo(ResolutionCase.STAFF_AFTER_TIMEOUT);
    }
}
