package com.dlab.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 설정. 값은 {@code application-{profile}.yml}에서 주입한다.
 *
 * <p>비밀키는 절대 코드·커밋에 넣지 않는다(CLAUDE.md §8) — 로컬은 gitignore된
 * {@code application-local.yml}, 운영은 환경변수로만 공급한다.
 *
 * @param secret            HS256 서명 키. <b>최소 32바이트</b>여야 한다(짧으면 기동 시 실패).
 * @param accessTokenTtl    Access Token 유효기간. 짧게 두고 재발급으로 연장한다.
 * @param refreshTokenTtl   Refresh Token 유효기간.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {

    public JwtProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(30) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(14) : refreshTokenTtl;
    }
}
