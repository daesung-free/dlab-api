package com.dlab.domain.admission.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.common.config.HomepageProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 홈페이지 입학예약 인증 (규격서 3.1·3.2).
 *
 * <h2>키오스크와 왜 나누나</h2>
 * 겉모습은 같지만({@code client_id} + {@code MD5(yyyyMMdd + secret)}) 성격이 다르다.
 * <ul>
 *   <li>키오스크는 <b>지점별</b> 자격증명이고 토큰이 지점을 정한다</li>
 *   <li>홈페이지는 <b>전 지점 공통</b> 하나이고 요청마다 {@code acid}로 지점을 지정한다</li>
 * </ul>
 * 그래서 Redis 접두사도 나눈다 — <b>키오스크 토큰으로 {@code /dlab/**}를 부를 수 없어야
 * 한다.</b> 같은 저장소를 쓰면 지점 하나의 자격증명이 새는 순간 전 지점 지원자 정보가
 * 열린다.
 *
 * <h2>자격증명은 설정에서 온다</h2>
 * 하나뿐이라 표를 만들지 않았다. {@code MD5} 재계산이 필요해 <b>평문으로 들고 있어야</b>
 * 하므로 DB보다 환경변수가 낫다 — 저장소 백업·복제에 안 실린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomepageTokenService {

    private static final DateTimeFormatter SECRET_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 규격서 3.1: 토큰 10일 · refresh 100일. */
    private static final Duration ACCESS_TTL = Duration.ofDays(10);
    private static final Duration REFRESH_TTL = Duration.ofDays(100);

    /** ★ 키오스크({@code dsa:})와 다른 접두사. 토큰이 서로 통하면 안 된다. */
    private static final String ACCESS_PREFIX = "hp:token:";
    private static final String REFRESH_PREFIX = "hp:refresh:";

    private final StringRedisTemplate redis;
    private final HomepageProperties properties;
    private final Clock clock;

    public record IssuedToken(String token, String refreshToken) {
    }

    /**
     * 토큰 발급.
     *
     * @param service 규격서가 {@code dlab} 고정으로 보낸다. 다른 값이면 우리 대상이 아니다
     */
    public IssuedToken issue(String clientId, String secretId, String service) {
        if (!properties.isConfigured()) {
            log.error("홈페이지 연동 자격증명이 설정되지 않았다 — 모든 인증이 거부된다");
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED, "인증에 실패했습니다.");
        }
        if (!properties.clientId().equals(clientId)
                || !matchesSecret(secretId, properties.secret())
                || (service != null && !properties.service().equals(service))) {
            log.warn("홈페이지 인증 실패: clientId={}, service={}", clientId, service);
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED, "인증에 실패했습니다.");
        }
        return newTokens();
    }

    /**
     * 토큰 갱신.
     *
     * <p><b>옛 refresh 토큰을 지운다.</b> 남겨두면 유출된 토큰으로 계속 재발급된다.
     */
    public IssuedToken refresh(String clientId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()
                || !properties.clientId().equals(clientId)
                || Boolean.FALSE.equals(redis.hasKey(REFRESH_PREFIX + refreshToken))) {
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED);
        }
        redis.delete(REFRESH_PREFIX + refreshToken);
        return newTokens();
    }

    /** 토큰 검증. 홈페이지 토큰은 지점을 담지 않는다 — 지점은 요청의 {@code acid}가 정한다. */
    public void verify(String token) {
        if (token == null || token.isBlank()
                || Boolean.FALSE.equals(redis.hasKey(ACCESS_PREFIX + token))) {
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED);
        }
    }

    // ─────────────────────────────────────────────────────────

    private IssuedToken newTokens() {
        String token = newToken();
        String refreshToken = newToken();
        redis.opsForValue().set(ACCESS_PREFIX + token, properties.clientId(), ACCESS_TTL);
        redis.opsForValue().set(REFRESH_PREFIX + refreshToken, properties.clientId(), REFRESH_TTL);
        return new IssuedToken(token, refreshToken);
    }

    /**
     * {@code secret_id == MD5(yyyyMMdd + secret)}.
     *
     * <p>키오스크와 같은 이유로 <b>어제·오늘·내일을 다 허용한다</b> — 자정 근처에 시계가
     * 조금만 어긋나도 인증이 통째로 실패한다. 날짜 성분은 하루 내내 같은 값이라
     * 재사용을 막지 못하므로(보안이 아니라 난독화다) 범위를 넓혀도 위험이 늘지 않는다.
     */
    private boolean matchesSecret(String secretId, String secret) {
        if (secretId == null) {
            return false;
        }
        LocalDate today = LocalDate.now(clock);
        return List.of(today.minusDays(1), today, today.plusDays(1)).stream()
                .map(date -> md5(date.format(SECRET_DATE) + secret))
                .anyMatch(expected -> constantTimeEquals(expected, secretId));
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String md5(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5를 사용할 수 없다", e);
        }
    }

    /** 타이밍 공격 방지. 길이가 달라도 조기 반환하지 않는다. */
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
