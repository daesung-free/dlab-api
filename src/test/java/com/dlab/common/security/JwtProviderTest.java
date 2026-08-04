package com.dlab.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT 발급·검증. Clock을 고정해 만료 동작을 실제로 확인한다.
 */
class JwtProviderTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-long";
    private static final Instant NOW = Instant.parse("2026-08-03T09:00:00Z");

    private JwtProvider providerAt(Instant instant) {
        return new JwtProvider(SECRET, Duration.ofHours(1), Duration.ofDays(7),
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("발급한 토큰에서 주체·지점·역할이 그대로 복원된다")
    void roundTrip() {
        JwtProvider provider = providerAt(NOW);
        AuthPrincipal issued = AuthPrincipal.of(
                42L, "EMPLOYEE", 7L, List.of(Role.BRANCH_ADMIN, Role.TEACHER), false);

        AuthPrincipal parsed = provider.parse(provider.issueAccessToken(issued));

        assertThat(parsed.accountId()).isEqualTo(42L);
        assertThat(parsed.accountType()).isEqualTo("EMPLOYEE");
        assertThat(parsed.academyId()).isEqualTo(7L);
        assertThat(parsed.roles()).containsExactlyInAnyOrder(Role.BRANCH_ADMIN, Role.TEACHER);
        assertThat(parsed.allAcademy()).isFalse();
    }

    @Test
    @DisplayName("전 지점 권한자는 academyId가 null이어도 복원된다")
    void allAcademyPrincipal() {
        JwtProvider provider = providerAt(NOW);
        AuthPrincipal issued = AuthPrincipal.of(
                1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);

        AuthPrincipal parsed = provider.parse(provider.issueAccessToken(issued));

        assertThat(parsed.academyId()).isNull();
        assertThat(parsed.allAcademy()).isTrue();
        assertThat(parsed.academyScopeFilter()).isNull();
    }

    @Test
    @DisplayName("만료된 토큰은 EXPIRED로 구분된다 — 클라이언트가 refresh를 시도해야 하므로")
    void expiredIsDistinguished() {
        String token = providerAt(NOW).issueAccessToken(
                AuthPrincipal.of(1L, "STUDENT", 1L, List.of(), false));

        // 발급 1시간 1분 뒤
        JwtProvider later = providerAt(NOW.plus(Duration.ofMinutes(61)));

        assertThatThrownBy(() -> later.parse(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .extracting(e -> ((JwtAuthenticationException) e).getReason())
                .isEqualTo(JwtAuthenticationException.Reason.EXPIRED);
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 INVALID — 위조는 refresh 재시도 대상이 아니다")
    void forgedIsInvalid() {
        String forged = new JwtProvider(
                "another-secret-key-also-at-least-32-bytes!!", Duration.ofHours(1),
                Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC))
                .issueAccessToken(AuthPrincipal.of(1L, "STUDENT", 1L, List.of(), false));

        assertThatThrownBy(() -> providerAt(NOW).parse(forged))
                .isInstanceOf(JwtAuthenticationException.class)
                .extracting(e -> ((JwtAuthenticationException) e).getReason())
                .isEqualTo(JwtAuthenticationException.Reason.INVALID);
    }

    @Test
    @DisplayName("32바이트 미만 시크릿은 기동 시점에 막는다")
    void shortSecretRejected() {
        assertThatThrownBy(() -> new JwtProvider("too-short", Duration.ofHours(1),
                Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }
}
