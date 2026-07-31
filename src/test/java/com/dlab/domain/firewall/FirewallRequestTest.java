package com.dlab.domain.firewall;

import com.dlab.domain.firewall.entity.ApproverType;
import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.ResolutionCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방화벽 승인 3케이스 판별 (CLAUDE.md §3).
 * 특히 담당선생님 승인 2케이스는 학부모 안내 문구가 서로 달라야 하므로 반드시 구분돼야 한다.
 */
class FirewallRequestTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-07-31T01:00:00Z");

    private FirewallRequest request() {
        // branch/student/staff는 판별 로직에 관여하지 않으므로 null로 둔다
        return new FirewallRequest(null, null, "인강 수강",
                FirewallRequest.DEFAULT_TIMEOUT_MINUTES, null, REQUESTED_AT);
    }

    @Test
    @DisplayName("타임아웃은 신청시각 + 10분")
    void escalationAtIsTenMinutesAfterRequest() {
        assertThat(request().getEscalationAt()).isEqualTo(REQUESTED_AT.plusSeconds(600));
        assertThat(request().getTimeoutMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("기본 승인자는 학부모")
    void defaultApproverIsParent() {
        assertThat(request().getDefaultApproverType()).isEqualTo(ApproverType.PARENT);
    }

    @Test
    @DisplayName("케이스1 - 타임아웃 전 학부모 승인")
    void parentApprovalInTime() {
        ResolutionCase result = request()
                .decideResolutionCase(ApproverType.PARENT, REQUESTED_AT.plusSeconds(60));

        assertThat(result).isEqualTo(ResolutionCase.PARENT_IN_TIME);
    }

    @Test
    @DisplayName("케이스1 - 학부모는 타임아웃이 지나서 승인해도 정상 승인으로 본다")
    void parentApprovalAfterTimeoutIsStillParentCase() {
        ResolutionCase result = request()
                .decideResolutionCase(ApproverType.PARENT, REQUESTED_AT.plusSeconds(1200));

        assertThat(result).isEqualTo(ResolutionCase.PARENT_IN_TIME);
    }

    @Test
    @DisplayName("케이스2 - 타임아웃 경과 후 담당선생님 승인 (에스컬레이션)")
    void staffApprovalAfterTimeout() {
        ResolutionCase result = request()
                .decideResolutionCase(ApproverType.STAFF, REQUESTED_AT.plusSeconds(601));

        assertThat(result).isEqualTo(ResolutionCase.STAFF_AFTER_TIMEOUT);
    }

    @Test
    @DisplayName("케이스3 - 타임아웃 전 담당선생님이 먼저 승인 (케이스2와 다른 문구가 나가야 함)")
    void staffApprovalBeforeTimeout() {
        ResolutionCase result = request()
                .decideResolutionCase(ApproverType.STAFF, REQUESTED_AT.plusSeconds(599));

        assertThat(result).isEqualTo(ResolutionCase.STAFF_BEFORE_TIMEOUT);
        assertThat(result).isNotEqualTo(ResolutionCase.STAFF_AFTER_TIMEOUT);
    }

    @Test
    @DisplayName("경계 - 타임아웃 정각 승인은 에스컬레이션으로 본다")
    void staffApprovalExactlyAtTimeout() {
        ResolutionCase result = request()
                .decideResolutionCase(ApproverType.STAFF, REQUESTED_AT.plusSeconds(600));

        assertThat(result).isEqualTo(ResolutionCase.STAFF_AFTER_TIMEOUT);
    }
}
