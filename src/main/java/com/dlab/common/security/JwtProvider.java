package com.dlab.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access / Refresh 토큰 발급·검증.
 *
 * <p>앱 요구사항 A-1 기준: Access 1시간 / Refresh 7일.
 * Refresh 토큰 저장과 로그아웃 블랙리스트는 Redis가 담당한다(CLAUDE.md §2) —
 * 여기서는 서명·클레임만 다루고 저장소는 건드리지 않는다.
 *
 * <p><b>DSA 호환 구획({@code /auth/**}, {@code /kiosk/**})은 이 토큰을 쓰지 않는다.</b>
 * 거기는 본문 {@code token} 필드 + {@code MD5(yyyyMMdd+secret)} 체계라 완전히 별개다.
 */
@Component
public class JwtProvider {

    private static final String CLAIM_ACCOUNT_TYPE = "typ";
    private static final String CLAIM_ACADEMY_ID = "aid";
    private static final String CLAIM_ROLES = "rol";
    private static final String CLAIM_ALL_ACADEMY = "all";
    /** 토큰 종류. 대조하지 않으면 Refresh로 API를 호출할 수 있다. */
    private static final String CLAIM_TOKEN_TYPE = "tkn";
    /**
     * 임시 비밀번호 상태(A-1).
     *
     * <p>클레임에 싣는 이유는 요청마다 계정을 다시 조회하지 않기 위해서다. 대신
     * <b>비밀번호를 바꾸면 토큰을 새로 받아야 이 값이 풀린다</b> — 변경 API가 새 토큰을 함께 돌려준다.
     */
    private static final String CLAIM_MUST_CHANGE_PASSWORD = "pcr";
    private static final String TYPE_ACCESS = "A";
    private static final String TYPE_REFRESH = "R";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-ttl:PT1H}") Duration accessTtl,
            @Value("${jwt.refresh-ttl:P7D}") Duration refreshTtl,
            Clock clock
    ) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256은 최소 256비트를 요구한다. 짧으면 런타임에야 터지므로 기동 시점에 막는다.
            throw new IllegalStateException("jwt.secret은 최소 32바이트여야 한다 (현재 " + bytes.length + ")");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.clock = clock;
    }

    public String issueAccessToken(AuthPrincipal principal) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                // 같은 초에 두 번 발급해도 서로 다른 토큰이 되도록
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(principal.accountId()))
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .claim(CLAIM_ACCOUNT_TYPE, principal.accountType())
                .claim(CLAIM_ACADEMY_ID, principal.academyId())
                .claim(CLAIM_ROLES, principal.roles().stream().map(Enum::name).toList())
                .claim(CLAIM_ALL_ACADEMY, principal.allAcademy())
                .claim(CLAIM_MUST_CHANGE_PASSWORD, principal.mustChangePassword())
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * Refresh는 식별자만 담는다 — 권한이 바뀌어도 재발급 시 최신 값을 다시 싣기 위해서다.
     *
     * <p><b>{@code jti}가 필요한 이유</b>: 없으면 같은 초 안에 두 번 발급했을 때 클레임이
     * 전부 같아 <b>바이트 단위로 동일한 토큰</b>이 나온다. 그러면 회전(재발급 시 이전 토큰
     * 무효화)이 성립하지 않는다 — 새 토큰이 옛 토큰과 같으니 무효화할 대상이 없다.
     */
    public String issueRefreshToken(Long accountId) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(accountId))
                .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(refreshTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * 서명·만료를 검증하고 주체를 복원한다.
     *
     * @throws JwtAuthenticationException 서명 불일치·만료·형식 오류
     */
    @SuppressWarnings("unchecked")
    public AuthPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Refresh를 Access 자리에 쓰면 역할이 비어 "인증됐지만 권한 없는 사용자"가 되어
            // authenticated()만 요구하는 경로를 통과한다. 종류를 먼저 막는다.
            requireType(claims, TYPE_ACCESS);

            List<String> roleNames = claims.get(CLAIM_ROLES, List.class);
            List<Role> roles = roleNames == null ? List.of() : roleNames.stream().map(Role::from).toList();

            Number academyId = claims.get(CLAIM_ACADEMY_ID, Number.class);

            return AuthPrincipal.of(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_ACCOUNT_TYPE, String.class),
                    academyId == null ? null : academyId.longValue(),
                    roles,
                    Boolean.TRUE.equals(claims.get(CLAIM_ALL_ACADEMY, Boolean.class)),
                    Boolean.TRUE.equals(claims.get(CLAIM_MUST_CHANGE_PASSWORD, Boolean.class))
            );
        } catch (ExpiredJwtException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.EXPIRED, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID, e);
        }
    }

    public Long parseRefreshSubject(String token) {
        return Long.valueOf(parseSubjectOnly(token));
    }

    private void requireType(Claims claims, String expected) {
        if (!expected.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID, null);
        }
    }

    private String parseSubjectOnly(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // Access Token으로 재발급받는 것도 막는다
            requireType(claims, TYPE_REFRESH);
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.EXPIRED, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID, e);
        }
    }
}
