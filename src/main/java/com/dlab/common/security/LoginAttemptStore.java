package com.dlab.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 횟수 카운터 (Redis).
 *
 * <p>DB 컬럼이 아니라 Redis인 이유는 <b>실패할 때마다 UPDATE가 나가기 때문</b>이다.
 * 무차별 대입 공격을 받으면 그 자체가 DB 쓰기 부하가 되고, 잠금 로직이 오히려
 * 공격 수단이 된다. 카운터는 휘발돼도 되는 값이고 <b>잠금 결과는 DB에 남으므로</b>
 * (account.locked_at) Redis가 비워져도 이미 잠긴 계정이 풀리지는 않는다.
 *
 * <p>키를 <b>login_id 기준</b>으로 잡는다 — 존재하지 않는 계정에 대한 시도도 세야
 * "계정이 있는지 없는지"를 응답 속도나 잠금 여부로 알아내는 것을 막을 수 있다.
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptStore {

    private static final String KEY_PREFIX = "auth:fail:";

    private final StringRedisTemplate redis;

    /** 실패를 1 올리고 누적 횟수를 돌려준다. 첫 실패에 TTL을 건다. */
    public long recordFailure(String loginId) {
        String key = key(loginId);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, PasswordPolicy.FAILURE_WINDOW);
        }
        return count == null ? 0L : count;
    }

    public long currentFailures(String loginId) {
        String value = redis.opsForValue().get(key(loginId));
        return value == null ? 0L : Long.parseLong(value);
    }

    /** 로그인 성공·관리자 잠금 해제 시 초기화. 지우지 않으면 다음 실패 한 번에 다시 잠긴다. */
    public void clear(String loginId) {
        redis.delete(key(loginId));
    }

    private String key(String loginId) {
        return KEY_PREFIX + loginId;
    }
}
