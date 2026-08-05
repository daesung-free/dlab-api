package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.repository.BranchConfigRepository;
import com.dlab.domain.user.repository.AcademyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
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

    /**
     * 키오스크 백엔드가 토큰을 Redis에 <b>9일</b> 캐싱하고, 만료 여부를 따지지 않고 그냥 쓴다.
     * 우리도 9일로 두면 경계에서 어긋나 죽은 토큰이 한 번 날아오고(code 910 → refresh) 매번
     * 헛 왕복이 생긴다. 우리 쪽을 하루 길게 잡아 <b>키오스크 캐시가 먼저 만료되게</b> 한다.
     */
    private static final Duration ACCESS_TTL = Duration.ofDays(10);
    /** refresh는 키오스크가 99일 보관한다. 같은 이유로 하루 여유를 둔다. */
    private static final Duration REFRESH_TTL = Duration.ofDays(100);

    private static final String ACCESS_PREFIX = "dsa:token:";
    private static final String REFRESH_PREFIX = "dsa:refresh:";

    /**
     * 지점별 발급 토큰 역인덱스.
     *
     * <p>토큰 키가 {@code dsa:token:{토큰값}} 구조라 <b>"이 지점의 토큰들"을 찾을 방법이 없다.</b>
     * 그래서 재발급해도 이전 토큰이 만료(10일)까지 살아 있었다 — 단말을 교체·분실해도
     * 옛 토큰으로 계속 조회가 됐다.
     */
    private static final String ACCESS_INDEX_PREFIX = "dsa:tokens:";
    private static final String REFRESH_INDEX_PREFIX = "dsa:refreshes:";

    /**
     * refresh → 그 refresh로 발급한 access 토큰들.
     *
     * <p><b>지점 단위로 뭉뚱그리면 안 된다.</b> 한 지점에 키오스크가 여러 대이고 각자
     * 토큰을 받으므로, 지점당 1개만 유지하면 <b>한 대가 갱신할 때마다 다른 대가 튕긴다.</b>
     * refresh는 단말 하나에 대응하므로 이 단위가 정확하다.
     */
    private static final String ISSUED_BY_PREFIX = "dsa:issued-by:";

    private final BranchConfigRepository branchConfigRepository;
    private final AcademyRepository academyRepository;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * {@code POST /auth/token} — client_id + secret_id 검증 후 토큰 발급.
     *
     * @param acadCd   키오스크가 함께 보내는 지점코드. client_id와 같은 지점인지 교차 확인한다
     * @param secretId 키오스크가 계산해 보낸 {@code MD5(yyyyMMdd + secret)}
     */
    public IssuedToken issue(String acadCd, String clientId, String secretId) {
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

        // 키오스크는 acad_cd와 client_id를 같이 보낸다. 둘이 가리키는 지점이 다르면
        // 설정 오류이거나 남의 자격증명을 붙인 것이므로 통과시키지 않는다.
        // acad_cd를 안 보내는 호출은 기존 동작을 깨지 않게 통과시킨다.
        if (acadCd != null && !acadCd.isBlank()) {
            boolean matched = academyRepository.findById(config.getAcademyId())
                    .map(a -> acadCd.equals(a.getAcadCd()))
                    .orElse(false);
            if (!matched) {
                log.warn("acad_cd 불일치 - 요청 {} vs client_id의 지점 {}", acadCd, config.getAcademyId());
                throw new DsaApiException(DsaCode.TOKEN_EXPIRED, "인증에 실패했습니다.");
            }
        }

        String token = newToken();
        String refreshToken = newToken();
        String academyId = String.valueOf(config.getAcademyId());

        redis.opsForValue().set(ACCESS_PREFIX + token, academyId, ACCESS_TTL);
        redis.opsForValue().set(REFRESH_PREFIX + refreshToken, academyId, REFRESH_TTL);
        index(academyId, token, refreshToken);

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

        // ★ 이 refresh로 앞서 발급했던 access를 먼저 죽인다.
        //   갱신했다는 건 이전 것을 더는 안 쓴다는 뜻인데, 그대로 두면 10일간 유효하다.
        //   같은 refresh를 쓰는 단말은 하나뿐이라 다른 키오스크에는 영향이 없다.
        revokePreviouslyIssued(refreshToken);

        String token = newToken();
        redis.opsForValue().set(ACCESS_PREFIX + token, academyId, ACCESS_TTL);
        redis.opsForSet().add(ACCESS_INDEX_PREFIX + academyId, token);
        redis.opsForSet().add(ISSUED_BY_PREFIX + refreshToken, token);
        redis.expire(ISSUED_BY_PREFIX + refreshToken, REFRESH_TTL);

        return new IssuedToken(token, refreshToken);
    }

    /**
     * 지점의 <b>모든</b> 키오스크 토큰을 폐기한다.
     *
     * <p>시크릿을 재발급하거나 단말을 분실했을 때 쓴다. 이걸 부르면 그 지점 키오스크가
     * 전부 재인증해야 하므로 <b>일상적으로 부르면 안 된다</b> —
     * 갱신 시 자동 정리는 {@link #revokePreviouslyIssued}가 단말 단위로 처리한다.
     *
     * @return 폐기한 토큰 수
     */
    public long revokeAll(Long academyId) {
        String accessIndex = ACCESS_INDEX_PREFIX + academyId;
        String refreshIndex = REFRESH_INDEX_PREFIX + academyId;

        long revoked = deleteMembers(accessIndex, ACCESS_PREFIX)
                + deleteMembers(refreshIndex, REFRESH_PREFIX);
        redis.delete(List.of(accessIndex, refreshIndex));

        log.info("키오스크 토큰 전체 폐기: academyId={}, {}건", academyId, revoked);
        return revoked;
    }

    private long deleteMembers(String indexKey, String valuePrefix) {
        Set<String> members = redis.opsForSet().members(indexKey);
        if (members == null || members.isEmpty()) {
            return 0;
        }
        // refresh 인덱스는 그 refresh가 발급한 access 목록도 함께 지운다
        if (REFRESH_PREFIX.equals(valuePrefix)) {
            members.forEach(m -> redis.delete(ISSUED_BY_PREFIX + m));
        }
        Long deleted = redis.delete(members.stream().map(m -> valuePrefix + m).toList());
        return deleted == null ? 0 : deleted;
    }

    /** 이 refresh로 앞서 발급한 access 토큰을 폐기한다. */
    private void revokePreviouslyIssued(String refreshToken) {
        String key = ISSUED_BY_PREFIX + refreshToken;
        Set<String> previous = redis.opsForSet().members(key);
        if (previous == null || previous.isEmpty()) {
            return;
        }
        redis.delete(previous.stream().map(t -> ACCESS_PREFIX + t).toList());
        redis.delete(key);
    }

    /**
     * 역인덱스 등록.
     *
     * <p>인덱스에도 TTL을 건다 — 안 걸면 만료된 토큰의 흔적이 영원히 쌓인다.
     * 이미 만료된 멤버가 남아 있어도 무해하다(없는 키 삭제는 no-op).
     */
    private void index(String academyId, String token, String refreshToken) {
        String accessIndex = ACCESS_INDEX_PREFIX + academyId;
        String refreshIndex = REFRESH_INDEX_PREFIX + academyId;

        redis.opsForSet().add(accessIndex, token);
        redis.opsForSet().add(refreshIndex, refreshToken);
        redis.opsForSet().add(ISSUED_BY_PREFIX + refreshToken, token);

        redis.expire(accessIndex, REFRESH_TTL);
        redis.expire(refreshIndex, REFRESH_TTL);
        redis.expire(ISSUED_BY_PREFIX + refreshToken, REFRESH_TTL);
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
