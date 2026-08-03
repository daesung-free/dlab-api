package com.dlab.common.security;

import com.dlab.domain.user.entity.AccountType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JWT 발급·검증.
 *
 * <p>Access Token에는 지점·역할까지 실어 요청마다 DB를 읽지 않게 한다. 대신 <b>권한을 회수해도
 * 토큰 만료 전까지는 살아 있다</b> — 그래서 Access Token 수명을 짧게 두고(기본 30분),
 * 즉시 차단이 필요한 경우(로그아웃·강제탈퇴)는 {@code TokenBlacklist}로 막는다.
 *
 * <p>토큰 종류를 클레임에 넣고 검증 시 대조한다. 안 하면 Refresh Token으로 API를 호출할 수 있다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String CLAIM_ACCOUNT_TYPE = "atype";
    private static final String CLAIM_ACADEMY_ID = "acad";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        byte[] secret = properties.secret() == null
                ? new byte[0] : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256은 최소 256비트를 요구한다. 짧은 키로 뜨면 운영에서 서명이 뚫린다.
            throw new IllegalStateException(
                    "jwt.secret이 너무 짧습니다(최소 32바이트). application-{profile}.yml을 확인하세요.");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.properties = properties;
        this.clock = clock;
    }

    public String createAccessToken(AuthPrincipal principal) {
        return create(principal, TokenType.ACCESS, properties.accessTokenTtl());
    }

    /** Refresh Token에는 역할·지점을 싣지 않는다 — 재발급 때 DB에서 다시 읽어 최신 권한을 반영한다. */
    public String createRefreshToken(Long accountId) {
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .claim(CLAIM_TOKEN_TYPE, TokenType.REFRESH.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.refreshTokenTtl())))
                .signWith(key)
                .compact();
    }

    private String create(AuthPrincipal principal, TokenType type, Duration ttl) {
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .subject(String.valueOf(principal.accountId()))
                .claim(CLAIM_TOKEN_TYPE, type.name())
                .claim(CLAIM_ACCOUNT_TYPE, principal.accountType().name())
                .claim(CLAIM_ACADEMY_ID, principal.academyId())
                .claim(CLAIM_ROLES, List.copyOf(principal.roles()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 서명·만료·토큰종류를 검증하고 주체를 복원한다.
     *
     * @return 유효하지 않으면 null (호출부가 401 처리)
     */
    public AuthPrincipal parseAccessToken(String token) {
        Claims claims = parse(token, TokenType.ACCESS);
        if (claims == null) {
            return null;
        }
        Number academyId = claims.get(CLAIM_ACADEMY_ID, Number.class);
        return new AuthPrincipal(
                Long.valueOf(claims.getSubject()),
                AccountType.valueOf(claims.get(CLAIM_ACCOUNT_TYPE, String.class)),
                academyId == null ? null : academyId.longValue(),
                readRoles(claims));
    }

    /** @return 유효하지 않으면 null */
    public Long parseRefreshTokenAccountId(String token) {
        Claims claims = parse(token, TokenType.REFRESH);
        return claims == null ? null : Long.valueOf(claims.getSubject());
    }

    private Claims parse(String token, TokenType expected) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
            if (!expected.name().equals(type)) {
                // Refresh Token으로 API를 호출하려는 시도 등
                log.debug("토큰 종류 불일치: expected={}, actual={}", expected, type);
                return null;
            }
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("토큰 검증 실패: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> readRoles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            return new LinkedHashSet<>((List<String>) list);
        }
        return Set.of();
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    public Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }
}
