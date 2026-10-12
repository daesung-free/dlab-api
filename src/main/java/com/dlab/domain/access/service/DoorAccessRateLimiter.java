package com.dlab.domain.access.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 긴급 출입 요청 시도 제한.
 *
 * <h2>★★ 요구사항에 없지만 반드시 필요하다</h2>
 * 이 경로는 <b>무인증</b>이고 입력이 <b>휴대폰 번호 하나</b>다. 제한이 없으면 번호를
 * 바꿔 가며 두드려 <b>그 지점의 도어락 번호를 뽑아낼 수 있다</b> — 재원생 번호 하나만
 * 맞히면 되고, 지역번호·통신사 패턴을 알면 공간이 좁다.
 *
 * <h2>두 축으로 센다</h2>
 * <ul>
 *   <li><b>IP</b> — 엄격하게. <b>여러 번호를 두드리는 것이 공격</b>이고 그건 한 IP 에서 온다</li>
 *   <li><b>번호</b> — 관대하게. 같은 학생이 몇 번 누르는 것은 <b>정상</b>이다
 *       (문이 안 열려서 다급한 상황이다)</li>
 * </ul>
 *
 * <p>★ <b>제공 시간이 한 시간뿐</b>이라는 점이 제한을 쉽게 만든다 — 창 길이를 그 한 시간에
 * 맞추면 하루치 시도를 한 번에 묶을 수 있다.
 *
 * <p><b>Redis 가 없으면 통과시킨다.</b> 제한 장치가 죽어서 <b>문을 못 여는 쪽</b>이
 * 더 나쁘다 — 아침 7시에 학생이 밖에 서 있는 상황이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoorAccessRateLimiter {

    private static final String IP_KEY = "door:try:ip:";
    private static final String PHONE_KEY = "door:try:phone:";

    /** 창 길이. 제공 시간(06:59~08:00)을 덮는다 — 그 밖의 시도는 어차피 거절된다 */
    private static final Duration WINDOW = Duration.ofMinutes(75);

    /**
     * IP 당 허용 횟수.
     *
     * <p>한 가족이 같은 와이파이로 둘 다 눌러 볼 수 있어 1~2 는 너무 좁다. 반면
     * <b>번호를 훑으려면 수백 번이 필요</b>하므로 10 이면 정상 사용은 막지 않고 대입은 막는다.
     */
    private static final int IP_LIMIT = 10;

    /** 번호당 허용 횟수. 같은 학생이 다급해서 여러 번 누르는 것은 정상이다 */
    private static final int PHONE_LIMIT = 20;

    private final StringRedisTemplate redis;

    /**
     * 시도 1회를 세고 허용 여부를 돌려준다.
     *
     * @return 허용하면 {@code true}
     */
    public boolean tryAcquire(String ip, String phoneDigits) {
        return count(IP_KEY + safe(ip), IP_LIMIT)
                && count(PHONE_KEY + safe(phoneDigits), PHONE_LIMIT);
    }

    private boolean count(String key, int limit) {
        try {
            Long tries = redis.opsForValue().increment(key);
            if (tries != null && tries == 1L) {
                redis.expire(key, WINDOW);
            }
            return tries == null || tries <= limit;
        } catch (RuntimeException e) {
            // ★ 제한 장치가 죽어서 문을 못 여는 쪽이 더 나쁘다 — 아침 7시에 학생이 밖에 있다
            log.error("긴급 출입 시도 제한을 확인할 수 없다 — 통과시킨다. key={}", key, e);
            return true;
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
