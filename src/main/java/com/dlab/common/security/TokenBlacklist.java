package com.dlab.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 로그아웃된 Access Token 블랙리스트 (Redis).
 *
 * <p>JWT는 스스로 만료될 때까지 유효하므로, 로그아웃해도 남은 시간 동안은 그 토큰으로
 * 계속 호출할 수 있다. 이걸 막으려면 서버가 "무효" 목록을 들고 있어야 한다.
 *
 * <p>TTL을 <b>토큰의 남은 수명만큼만</b> 잡는다 — 어차피 그 뒤엔 만료돼서 자연히 거부되므로
 * 더 들고 있을 이유가 없다.
 */
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    private static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redis;

    public void add(String accessToken, Duration remainingTtl) {
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + accessToken, "1", remainingTtl);
    }

    public boolean contains(String accessToken) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + accessToken));
    }
}
