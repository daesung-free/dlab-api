package com.dlab.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 계정 단위 세션 컷오프 — <b>이 시각 전에 발급된 토큰을 전부 무효로 만든다.</b>
 *
 * <h2>★★ 왜 블랙리스트로는 안 되는가</h2>
 * {@link TokenBlacklist} 의 키는 <b>토큰 문자열</b>이다. 로그아웃은 본인이 그 토큰을 들고
 * 오니까 올릴 값이 있는데, <b>관리자가 남의 계정을 중지할 때는 그 사람의 토큰을 서버가
 * 모른다</b> — 올릴 값이 없다. 그래서 중지해도 그 토큰은 만료까지 살아 있었다
 * (운영 Access Token 수명은 <b>1시간</b>이다).
 *
 * <h2>★ 권한을 줄였는데 한 시간 동안 옛 권한으로 도는 문제도 같이 푼다</h2>
 * 필터가 토큰을 파싱만 하고 DB 를 보지 않으므로, 역할·메뉴를 바꿔도 <b>이미 발급된 토큰에는
 * 옛 권한이 그대로 들어 있다.</b> 담당자 눈에는 「설정했는데 안 바뀐다」로 보이고, 그건
 * 메뉴 설정이 권한을 못 늘리는 것과 <b>증상이 똑같아 구분되지 않는다</b>(2026-10-27 실제로
 * 그 혼동이 있었다). 권한이 바뀌는 자리에서 컷오프를 찍으면 다음 요청에서 바로 끊긴다.
 *
 * <h2>무효가 아니라 재로그인이다</h2>
 * Refresh Token 까지 막지 않는다 — 중지·탈퇴는 로그인 자체가 막히므로 재발급에서 걸리고,
 * 역할·메뉴 변경은 <b>다시 로그인하면 새 권한으로 들어오는 것이 맞다.</b>
 *
 * <p>TTL 은 Access Token 수명보다 넉넉히 잡는다. 그보다 짧으면 키가 사라진 뒤에 옛 토큰이
 * 되살아나는데, <b>되살아나는 창이 생기면 그게 가장 찾기 어려운 버그가 된다.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCutoff {

    private static final String KEY_PREFIX = "auth:cutoff:";
    /** Access Token 수명(1시간)의 두 배. 그 뒤엔 어차피 자연 만료라 들고 있을 이유가 없다. */
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * 지금 이후로 이 계정의 기존 토큰을 전부 끊는다.
     *
     * <p>★ <b>권한이 바뀌거나 접근을 막는 모든 자리에서 부른다</b> — 계정 중지·탈퇴·잠금,
     * 임시 비밀번호 재발급, 역할 변경, 계정별 메뉴 변경.
     */
    public void revokeAll(Long accountId) {
        if (accountId == null) {
            return;
        }
        // ★ 초 단위로 저장한다 — JWT iat 이 초로 잘려 오므로 더 정밀하게 두면 비교가 어긋난다.
        redis.opsForValue().set(KEY_PREFIX + accountId,
                String.valueOf(clock.instant().getEpochSecond()), TTL);
    }

    /**
     * 이 토큰이 컷오프 이전에 발급된 것인가.
     *
     * <h2>★★ 같은 초에 발급된 토큰은 살린다</h2>
     * JWT {@code iat} 은 <b>초 단위로 잘려 온다.</b> 밀리초로 비교하면 <b>컷오프 직후에
     * 발급된 토큰까지 끊긴다</b> — 임시 비밀번호를 재발급하고 바로 로그인하는 흐름이
     * 실제로 같은 초 안에 일어나서, 방금 받은 토큰으로 즉시 튕긴다(테스트에서 잡혔다).
     *
     * <p>그래서 <b>초로 맞추고 {@code <} 로 비교</b>한다. 대가는 <b>최대 1초</b>의 창이다 —
     * 컷오프와 같은 초에 발급된 토큰은 살아남는다. 로그인 직후 튕기는 쪽이 훨씬 나쁘다.
     *
     * @param issuedAt JWT {@code iat}
     */
    public boolean revoked(Long accountId, Instant issuedAt) {
        if (accountId == null || issuedAt == null) {
            return false;
        }
        String at = redis.opsForValue().get(KEY_PREFIX + accountId);
        if (at == null) {
            return false;
        }
        try {
            return issuedAt.getEpochSecond() < Long.parseLong(at);
        } catch (NumberFormatException e) {
            // 값이 깨졌으면 끊지 않는다 — 전 계정 로그아웃이 되는 쪽이 훨씬 나쁘다.
            log.warn("세션 컷오프 값이 깨져 있다 - accountId={} value={}", accountId, at);
            return false;
        }
    }
}
