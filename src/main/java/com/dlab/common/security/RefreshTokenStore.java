package com.dlab.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Refresh Token 저장소 (Redis).
 *
 * <p>DB가 아니라 Redis에 두는 이유는 만료를 TTL로 맡길 수 있고, 로그아웃 시 즉시 지울 수
 * 있기 때문이다(CLAUDE.md §2 결정로그).
 *
 * <p><b>계정당 1개만 유지한다</b> — 재발급할 때 이전 토큰을 덮어써서, 탈취된 토큰이 계속
 * 쓰이는 것을 막는다. 다중 기기 로그인을 허용하려면 키에 기기 식별자를 붙여야 한다(현재 요구사항 없음).
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redis;

    public void save(Long accountId, String refreshToken, Duration ttl) {
        redis.opsForValue().set(key(accountId), refreshToken, ttl);
    }

    /** 저장된 것과 일치하는지 확인한다. 불일치면 이미 재발급됐거나 탈취된 토큰이다. */
    public boolean matches(Long accountId, String refreshToken) {
        String saved = redis.opsForValue().get(key(accountId));
        return saved != null && Objects.equals(saved, refreshToken);
    }

    public void delete(Long accountId) {
        redis.delete(key(accountId));
    }

    private String key(Long accountId) {
        return KEY_PREFIX + accountId;
    }
}
