package com.dlab.domain.user.entity;

import static com.dlab.domain.user.entity.EnrollmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EnrollmentStatusTransitionTest {

    @Test
    @DisplayName("재원 ↔ 휴원은 자유롭다")
    void enrolledAndOnLeaveAreFreelyInterchangeable() {
        assertThat(EnrollmentStatusTransition.isAllowed(ENROLLED, ON_LEAVE)).isTrue();
        assertThat(EnrollmentStatusTransition.isAllowed(ON_LEAVE, ENROLLED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = EnrollmentStatus.class, names = {"WITHDRAWN", "EXPELLED", "GRADUATED"})
    @DisplayName("재원·휴원에서는 어느 종료 상태로든 갈 수 있다")
    void canTerminateFromActiveStates(EnrollmentStatus terminal) {
        assertThat(EnrollmentStatusTransition.isAllowed(ENROLLED, terminal)).isTrue();
        assertThat(EnrollmentStatusTransition.isAllowed(ON_LEAVE, terminal)).isTrue();
    }

    @Test
    @DisplayName("★ 종료끼리는 못 넘어간다 — 퇴원을 제적으로 고치려면 재원을 거쳐야 한다")
    void terminalToTerminalIsBlocked() {
        assertThat(EnrollmentStatusTransition.isAllowed(WITHDRAWN, EXPELLED)).isFalse();
        assertThat(EnrollmentStatusTransition.isAllowed(EXPELLED, WITHDRAWN)).isFalse();
        assertThat(EnrollmentStatusTransition.isAllowed(GRADUATED, WITHDRAWN)).isFalse();

        // 재원을 거치면 "퇴원 취소 → 제적" 두 줄이 남아 정정임이 드러난다
        assertThat(EnrollmentStatusTransition.isAllowed(WITHDRAWN, ENROLLED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = EnrollmentStatus.class, names = {"WITHDRAWN", "EXPELLED", "GRADUATED"})
    @DisplayName("종료 상태에서 휴원으로는 못 간다")
    void terminalCannotGoOnLeave(EnrollmentStatus terminal) {
        assertThat(EnrollmentStatusTransition.isAllowed(terminal, ON_LEAVE)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(EnrollmentStatus.class)
    @DisplayName("같은 상태로의 전이는 허용하지 않는다 — 이력에 노이즈만 쌓인다")
    void selfTransitionIsBlocked(EnrollmentStatus status) {
        assertThat(EnrollmentStatusTransition.isAllowed(status, status)).isFalse();
    }

    @Test
    @DisplayName("★ 휴원은 종료가 아니다 — 후속처리 기준이 갈린다")
    void onLeaveIsNotTerminal() {
        assertThat(ON_LEAVE.isTerminal()).isFalse();
        assertThat(ENROLLED.isTerminal()).isFalse();
        assertThat(WITHDRAWN.isTerminal()).isTrue();
        assertThat(EXPELLED.isTerminal()).isTrue();
        assertThat(GRADUATED.isTerminal()).isTrue();
    }
}
