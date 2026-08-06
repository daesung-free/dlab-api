package com.dlab.domain.user.entity;

import static com.dlab.domain.user.entity.OnboardingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 앱 온보딩 상태 머신 (A-2).
 *
 * <p><b>승인 상태는 여기 없다</b> — {@link AccountStatus}가 따로 들고 있다.
 * 두 곳에 두면 어긋났을 때 어느 쪽이 맞는지 알 수 없다.
 */
class OnboardingStatusTest {

    @Test
    @DisplayName("★ 시트 A-2의 5단계다 — 승인 상태를 끼워넣지 않는다")
    void hasExactlyFiveStagesFromSheet() {
        assertThat(OnboardingStatus.values())
                .containsExactly(REGISTERED, OT_DONE, PARENT_LINKED, SCHEDULE_SET, ACTIVE);
    }

    @Test
    @DisplayName("순서대로 전진한다")
    void advancesInOrder() {
        assertThat(REGISTERED.next()).isEqualTo(OT_DONE);
        assertThat(OT_DONE.next()).isEqualTo(PARENT_LINKED);
        assertThat(PARENT_LINKED.next()).isEqualTo(SCHEDULE_SET);
        assertThat(SCHEDULE_SET.next()).isEqualTo(ACTIVE);
    }

    @Test
    @DisplayName("완료 상태에서 또 전진해도 그대로다")
    void activeStaysActive() {
        assertThat(ACTIVE.next()).isEqualTo(ACTIVE);
        assertThat(ACTIVE.isCompleted()).isTrue();
        assertThat(SCHEDULE_SET.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("★ 어디까지 왔는지 판정 — 앱 라우팅이 이걸 본다")
    void reachedComparesProgress() {
        assertThat(PARENT_LINKED.reached(OT_DONE)).isTrue();
        assertThat(PARENT_LINKED.reached(PARENT_LINKED)).isTrue();
        assertThat(PARENT_LINKED.reached(SCHEDULE_SET)).isFalse();
    }

    @Test
    @DisplayName("★ 단계를 건너뛸 수 없다 — 기대 단계가 다르면 전진하지 않는다")
    void cannotSkipStages() {
        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        assertThat(student.getOnboardingStatus()).isEqualTo(REGISTERED);

        // OT를 안 했는데 학부모 연결 완료가 먼저 들어온 경우
        student.advanceOnboarding(OT_DONE);
        assertThat(student.getOnboardingStatus()).isEqualTo(REGISTERED);

        student.advanceOnboarding(REGISTERED);
        assertThat(student.getOnboardingStatus()).isEqualTo(OT_DONE);
    }

    @Test
    @DisplayName("★ 같은 완료 신호가 두 번 와도 한 칸만 간다")
    void duplicateSignalAdvancesOnce() {
        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");

        student.advanceOnboarding(REGISTERED);
        student.advanceOnboarding(REGISTERED);   // 학부모 연결이 두 번 들어온 상황

        assertThat(student.getOnboardingStatus()).isEqualTo(OT_DONE);
    }
}
