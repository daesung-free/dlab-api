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
                .subject(String.valueOf(principal.accountId()))
                .claim(CLAIM_ACCOUNT_TYPE, principal.accountType())
                .claim(CLAIM_ACADEMY_ID, principal.academyId())
                .claim(CLAIM_ROLES, principal.roles().stream().map(Enum::name).toList())
                .claim(CLAIM_ALL_ACADEMY, principal.allAcademy())
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** Refresh는 식별자만 담는다 — 권한이 바뀌어도 재발급 시 최신 값을 다시 싣기 위해서다. */
    public String issueRefreshToken(Long accountId) {
        return Jwts.builder()
                .subject(String.valueOf(accountId))
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

            List<String> roleNames = claims.get(CLAIM_ROLES, List.class);
            List<Role> roles = roleNames == null ? List.of() : roleNames.stream().map(Role::from).toList();

            Number academyId = claims.get(CLAIM_ACADEMY_ID, Number.class);

            return AuthPrincipal.of(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_ACCOUNT_TYPE, String.class),
                    academyId == null ? null : academyId.longValue(),
                    roles,
                    Boolean.TRUE.equals(claims.get(CLAIM_ALL_ACADEMY, Boolean.class))
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

    private String parseSubjectOnly(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (ExpiredJwtException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.EXPIRED, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID, e);
        }
    }
}
