package com.dlab.common.security;

import com.dlab.domain.user.entity.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");
    private static final String SECRET = "test-only-secret-value-at-least-32-bytes";

    private JwtTokenProvider provider(Clock clock) {
        return new JwtTokenProvider(
                new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)), clock);
    }

    private JwtTokenProvider provider() {
        return provider(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(1L, AccountType.TEACHER, 10L, Set.of("TEACHER"));
    }

    @Test
    @DisplayName("Access Token은 계정·지점·역할을 그대로 복원한다")
    void accessTokenRoundTrip() {
        JwtTokenProvider provider = provider();

        AuthPrincipal parsed = provider.parseAccessToken(provider.createAccessToken(principal()));

        assertThat(parsed).isNotNull();
        assertThat(parsed.accountId()).isEqualTo(1L);
        assertThat(parsed.accountType()).isEqualTo(AccountType.TEACHER);
        assertThat(parsed.academyId()).isEqualTo(10L);
        assertThat(parsed.roles()).containsExactly("TEACHER");
    }

    @Test
    @DisplayName("★ Refresh Token으로는 API를 호출할 수 없다 (토큰 종류 대조)")
    void refreshTokenIsRejectedAsAccessToken() {
        JwtTokenProvider provider = provider();

        assertThat(provider.parseAccessToken(provider.createRefreshToken(1L))).isNull();
    }

    @Test
    @DisplayName("★ Access Token으로는 재발급할 수 없다")
    void accessTokenIsRejectedAsRefreshToken() {
        JwtTokenProvider provider = provider();

        assertThat(provider.parseRefreshTokenAccountId(provider.createAccessToken(principal()))).isNull();
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    void expiredTokenIsRejected() {
        String token = provider().createAccessToken(principal());

        // 31분 뒤 — TTL 30분을 넘겼다
        JwtTokenProvider later = provider(Clock.fixed(NOW.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));

        assertThat(later.parseAccessToken(token)).isNull();
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 거부한다")
    void tokenSignedWithOtherKeyIsRejected() {
        String token = provider().createAccessToken(principal());

        JwtTokenProvider other = new JwtTokenProvider(
                new JwtProperties("completely-different-secret-value-32bytes",
                        Duration.ofMinutes(30), Duration.ofDays(14)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(other.parseAccessToken(token)).isNull();
    }

    @Test
    @DisplayName("★ 비밀키가 32바이트 미만이면 기동 시점에 실패한다")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtTokenProvider(
                new JwtProperties("too-short", Duration.ofMinutes(30), Duration.ofDays(14)),
                Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("★ 같은 시각에 두 번 발급해도 서로 다른 토큰이다")
    void tokensIssuedAtSameInstantDiffer() {
        // 고정 시계라 iat/exp가 동일하다. jti가 없으면 바이트 단위로 같은 토큰이 나오고,
        // 그러면 Refresh 회전(재발급 시 이전 토큰 무효화)이 성립하지 않는다.
        JwtTokenProvider provider = provider();

        assertThat(provider.createRefreshToken(1L))
                .isNotEqualTo(provider.createRefreshToken(1L));
        assertThat(provider.createAccessToken(principal()))
                .isNotEqualTo(provider.createAccessToken(principal()));
    }

    @Test
    @DisplayName("망가진 토큰은 예외 대신 null을 준다")
    void malformedTokenReturnsNull() {
        assertThat(provider().parseAccessToken("not-a-jwt")).isNull();
    }
}
