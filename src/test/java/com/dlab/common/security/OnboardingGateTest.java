package com.dlab.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.AntPathMatcher;

/**
 * OT 게이트의 경로 목록 (I-24).
 *
 * <h2>★★ 막아서는 안 되는 것을 지킨다</h2>
 * <b>정기일정 등록을 막으면 온보딩이 영원히 끝나지 않는다</b> — {@code SCHEDULE_SET} 이
 * 온보딩 단계라, 막으면 학생이 그 단계를 통과할 방법이 없다. 인증·앱설정도 막으면
 * <b>앱이 아예 뜨지 않는다.</b>
 *
 * <p>목록이 I-24 로 미확정이라 바뀔 텐데, 바꿀 때 이 경계를 다시 밟지 않게 고정한다.
 */
class OnboardingGateTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @SuppressWarnings("unchecked")
    private List<String> blocked() {
        return (List<String>) ReflectionTestUtils
                .getField(OnboardingGateInterceptor.class, "BLOCKED_BEFORE_OT");
    }

    private boolean isBlocked(String uri) {
        return blocked().stream().anyMatch(p -> MATCHER.match(p, uri));
    }

    @Test
    @DisplayName("★★ 정기일정은 막지 않는다 — 막으면 온보딩이 영원히 끝나지 않는다")
    void regularScheduleIsNeverBlocked() {
        assertThat(isBlocked("/api/v1/app/regular-schedules"))
                .as("SCHEDULE_SET 이 온보딩 단계다")
                .isFalse();
        assertThat(isBlocked("/api/v1/app/regular-schedules/123")).isFalse();
    }

    @Test
    @DisplayName("★★ 앱이 뜨는 데 필요한 것은 막지 않는다 — 막으면 로그인조차 못 한다")
    void bootPathsAreNeverBlocked() {
        assertThat(isBlocked("/api/v1/app/auth/login")).isFalse();
        assertThat(isBlocked("/api/v1/app/auth/logout")).isFalse();
        assertThat(isBlocked("/api/v1/app/app-config")).isFalse();
        assertThat(isBlocked("/api/v1/app/me")).isFalse();
        assertThat(isBlocked("/api/v1/app/terms")).isFalse();
        assertThat(isBlocked("/api/v1/app/settings/push-token"))
                .as("토큰을 못 올리면 푸시 대상이 생기지 않는다")
                .isFalse();
    }

    @Test
    @DisplayName("학원이 처리해야 하는 「신청」은 막는다")
    void applicationPathsAreBlocked() {
        assertThat(isBlocked("/api/v1/app/meals/apply")).isTrue();
        assertThat(isBlocked("/api/v1/app/firewall/requests")).isTrue();
        assertThat(isBlocked("/api/v1/app/qna")).isTrue();
        assertThat(isBlocked("/api/v1/app/absence-requests")).isTrue();
    }

    @Test
    @DisplayName("관리자 경로는 대상이 아니다 — 직원에게는 온보딩이 없다")
    void adminPathsAreNotBlocked() {
        assertThat(isBlocked("/api/v1/admin/meals/apply")).isFalse();
        assertThat(isBlocked("/kiosk/setAttendStd")).isFalse();
    }
}
