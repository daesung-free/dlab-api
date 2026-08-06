package com.dlab.domain.appconfig;

import com.dlab.domain.appconfig.entity.AppVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱 버전 비교.
 *
 * <p>지키려는 것은 하나다 — <b>사전순 비교를 쓰면 안 된다.</b>
 */
class AppVersionTest {

    @Test
    @DisplayName("★ 1.10.0은 1.9.0보다 높다 — 문자열 비교면 정반대가 되고, 그 순간 최신 사용자가 전부 막힌다")
    void twoDigitMinorIsNotLower() {
        // "1.10.0".compareTo("1.9.0") < 0 이라 사전순이면 아래가 true가 된다
        assertThat(AppVersion.isBelow("1.10.0", "1.9.0")).isFalse();
        assertThat(AppVersion.isBelow("1.9.0", "1.10.0")).isTrue();
    }

    @Test
    @DisplayName("같은 버전은 업데이트 대상이 아니다 — 최소 지원 버전 '미만'만 막는다")
    void sameVersionIsAllowed() {
        assertThat(AppVersion.isBelow("1.2.0", "1.2.0")).isFalse();
    }

    @Test
    @DisplayName("마디 수가 달라도 비교된다 — 1.2와 1.2.0은 같다")
    void differentPartCount() {
        assertThat(AppVersion.compare("1.2", "1.2.0")).isZero();
        assertThat(AppVersion.isBelow("1.2", "1.2.1")).isTrue();
        assertThat(AppVersion.isBelow("1.2.1", "1.2")).isFalse();
    }

    @Test
    @DisplayName("★ 형식이 깨진 값은 업데이트를 요구하지 않는다 — 못 읽었다고 막으면 전 사용자 차단이다")
    void malformedVersionDoesNotBlock() {
        assertThat(AppVersion.isBelow(null, "1.0.0")).isFalse();
        assertThat(AppVersion.isBelow("", "1.0.0")).isFalse();
        assertThat(AppVersion.isBelow("어쩌구", "1.0.0")).isFalse();
        assertThat(AppVersion.isBelow("1.0.0", null)).isFalse();
    }

    @Test
    @DisplayName("꼬리표가 붙어도 숫자만 본다 (1.2.0-beta)")
    void ignoresSuffix() {
        assertThat(AppVersion.isBelow("1.2.0-beta", "1.2.0")).isFalse();
        assertThat(AppVersion.isBelow("1.1.0-rc1", "1.2.0")).isTrue();
    }

    @Test
    @DisplayName("메이저가 다르면 마이너를 보지 않는다")
    void majorWins() {
        assertThat(AppVersion.isBelow("1.99.99", "2.0.0")).isTrue();
        assertThat(AppVersion.isBelow("2.0.0", "1.99.99")).isFalse();
    }
}
