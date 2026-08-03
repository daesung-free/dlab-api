package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.repository.BranchConfigRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * DSA 호환 토큰 발급·검증.
 *
 * <p>지금까지는 대성전산이 발급하고 키오스크가 보관했지만, 우리가 DSA를 대체하므로
 * <b>이제 우리가 발급·검증하는 쪽</b>이다.
 *
 * <p>토큰은 의미 없는 UUID다. 키오스크는 그냥 보관했다가 그대로 돌려주므로
 * JWT처럼 클레임을 실을 이유가 없고, 오히려 Redis에서 즉시 폐기할 수 있어 낫다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DsaTokenService {

    private static final DateTimeFormatter SECRET_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 키오스크 백엔드가 토큰을 9일 보관하므로 그보다 짧으면 매일 재발급이 터진다. */
    private static final Duration ACCESS_TTL = Duration.ofDays(9);
    private static final Duration REFRESH_TTL = Duration.ofDays(99);

    private static final String ACCESS_PREFIX = "dsa:token:";
    private static final String REFRESH_PREFIX = "dsa:refresh:";

    private final BranchConfigRepository branchConfigRepository;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * {@code POST /auth/token} — client_id + secret_id 검증 후 토큰 발급.
     *
     * @param secretId 키오스크가 계산해 보낸 {@code MD5(yyyyMMdd + secret)}
     */
    public IssuedToken issue(String clientId, String secretId) {
        BranchConfig config = branchConfigRepository
                .findByKioskClientIdAndDeletedFalse(clientId)
                .filter(BranchConfig::hasKioskCredential)
                .orElseThrow(() -> {
                    log.warn("알 수 없는 키오스크 client_id: {}", clientId);
                    return new DsaApiException(DsaCode.TOKEN_EXPIRED, "인증에 실패했습니다.");
                });

        if (!matchesSecret(secretId, config.getKioskSecret())) {
            log.warn("키오스크 시크릿 불일치. academyId={}", config.getAcademyId());
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED, "인증에 실패했습니다.");
        }

        String token = newToken();
        String refreshToken = newToken();
        redis.opsForValue().set(ACCESS_PREFIX + token, String.valueOf(config.getAcademyId()), ACCESS_TTL);
        redis.opsForValue().set(REFRESH_PREFIX + refreshToken, String.valueOf(config.getAcademyId()), REFRESH_TTL);

        return new IssuedToken(token, refreshToken);
    }

    /** {@code POST /auth/refreshToken} — refresh는 그대로 두고 access만 재발급한다. */
    public IssuedToken refresh(String clientId, String refreshToken) {
        String academyId = redis.opsForValue().get(REFRESH_PREFIX + refreshToken);
        if (academyId == null) {
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED);
        }

        // client_id와 refresh가 같은 지점 것인지 확인한다.
        // 안 하면 A지점 refresh로 B지점 토큰을 받아갈 수 있다.
        branchConfigRepository.findByKioskClientIdAndDeletedFalse(clientId)
                .filter(c -> String.valueOf(c.getAcademyId()).equals(academyId))
                .orElseThrow(() -> new DsaApiException(DsaCode.TOKEN_EXPIRED));

        String token = newToken();
        redis.opsForValue().set(ACCESS_PREFIX + token, academyId, ACCESS_TTL);
        return new IssuedToken(token, refreshToken);
    }

    /**
     * 요청 본문의 {@code token}을 검증하고 지점을 돌려준다.
     *
     * @throws DsaApiException 만료·미상 토큰이면 {@code code 910}
     */
    public Long resolveAcademyId(String token) {
        if (token == null || token.isBlank()) {
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED);
        }
        String academyId = redis.opsForValue().get(ACCESS_PREFIX + token);
        if (academyId == null) {
            throw new DsaApiException(DsaCode.TOKEN_EXPIRED);
        }
        return Long.valueOf(academyId);
    }

    /**
     * {@code secret_id == MD5(yyyyMMdd + secret)} 검증.
     *
     * <p><b>어제·오늘·내일 셋 다 허용한다.</b> 날짜가 일 단위라 자정 근처에 키오스크와
     * 서버 시계가 조금만 어긋나도 전 지점 인증이 동시에 실패한다.
     * 날짜 성분은 어차피 하루 내내 같은 값이라 재사용을 막지 못하므로
     * (보안이 아니라 난독화에 가깝다) 범위를 넓혀도 실질 위험이 늘지 않는다.
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

    /** 로그아웃·강제 폐기용. 지금은 쓰이는 곳이 없지만 시크릿 재발급 시 필요하다. */
    public void revoke(String token) {
        Optional.ofNullable(token).ifPresent(t -> redis.delete(ACCESS_PREFIX + t));
    }

    public record IssuedToken(String token, String refreshToken) {
    }
}
