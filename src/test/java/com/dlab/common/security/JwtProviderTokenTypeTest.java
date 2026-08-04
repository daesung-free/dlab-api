package com.dlab.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토큰 종류 대조와 발급 고유성.
 *
 * <p>둘 다 깨져도 로그인은 멀쩡히 되기 때문에 테스트가 없으면 운영에서야 드러난다.
 */
class JwtProviderTokenTypeTest {

    private static final Instant NOW = Instant.parse("2026-08-04T01:00:00Z");
    private static final String SECRET = "test-only-secret-value-at-least-32-bytes";

    private JwtProvider provider() {
        return new JwtProvider(SECRET, Duration.ofHours(1), Duration.ofDays(7),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthPrincipal principal() {
        return AuthPrincipal.of(1L, "TEACHER", 10L, List.of(Role.TEACHER), false);
    }

    @Test
    @DisplayName("★ Refresh Token으로는 API를 호출할 수 없다")
    void refreshCannotBeUsedAsAccess() {
        JwtProvider p = provider();
        String refresh = p.issueRefreshToken(1L);

        // 막지 않으면 역할이 빈 주체로 인증돼 authenticated()만 요구하는 경로를 통과한다
        assertThatThrownBy(() -> p.parse(refresh))
                .isInstanceOf(JwtAuthenticationException.class);
    }

    @Test
    @DisplayName("★ Access Token으로는 재발급할 수 없다")
    void accessCannotBeUsedAsRefresh() {
        JwtProvider p = provider();
        String access = p.issueAccessToken(principal());

        assertThatThrownBy(() -> p.parseRefreshSubject(access))
                .isInstanceOf(JwtAuthenticationException.class);
    }

    @Test
    @DisplayName("★ 같은 시각에 두 번 발급해도 서로 다른 토큰이다")
    void tokensIssuedAtSameInstantDiffer() {
        // 고정 시계라 iat/exp가 동일하다. jti가 없으면 바이트 단위로 같은 토큰이 나오고,
        // 그러면 Refresh 회전(재발급 시 이전 토큰 무효화)이 성립하지 않는다.
        JwtProvider p = provider();

        assertThat(p.issueRefreshToken(1L)).isNotEqualTo(p.issueRefreshToken(1L));
        assertThat(p.issueAccessToken(principal())).isNotEqualTo(p.issueAccessToken(principal()));
    }

    @Test
    @DisplayName("정상 Access Token은 주체를 복원한다")
    void accessRoundTrip() {
        JwtProvider p = provider();

        AuthPrincipal parsed = p.parse(p.issueAccessToken(principal()));

        assertThat(parsed.accountId()).isEqualTo(1L);
        assertThat(parsed.academyId()).isEqualTo(10L);
        assertThat(parsed.roles()).containsExactly(Role.TEACHER);
    }
}
