package com.dlab.domain.kiosk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.repository.BranchConfigRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 키오스크 인증. MD5 자정 경계와 지점 교차 검증이 핵심이다 —
 * 전자는 틀리면 자정마다 전 지점이 동시에 인증 실패하고,
 * 후자는 틀리면 A지점 토큰으로 B지점 데이터를 가져갈 수 있다.
 */
class DsaTokenServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String CLIENT_ID = "client1_1";
    private static final String ACAD_CD = "31";
    private static final String SECRET = "test-secret";
    private static final Long ACADEMY_ID = 7L;

    private BranchConfigRepository repository;
    private AcademyRepository academyRepository;
    private Map<String, String> store;
    private Map<String, java.util.Set<String>> sets;
    private DsaTokenService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        repository = mock(BranchConfigRepository.class);
        store = new HashMap<>();
        sets = new HashMap<>();
        clock = Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), KST);

        StringRedisTemplate redis = fakeRedis();

        BranchConfig config = new BranchConfig(ACADEMY_ID);
        config.issueKioskCredential(CLIENT_ID, SECRET);
        given(repository.findByKioskClientIdAndDeletedFalse(CLIENT_ID))
                .willReturn(Optional.of(config));
        given(repository.findByKioskClientIdAndDeletedFalse("unknown"))
                .willReturn(Optional.empty());

        academyRepository = mock(AcademyRepository.class);
        Academy academy = new Academy(ACAD_CD, "분당", java.time.LocalTime.of(9, 0));
        given(academyRepository.findById(ACADEMY_ID)).willReturn(Optional.of(academy));

        service = new DsaTokenService(repository, academyRepository, redis, clock);
    }

    /**
     * 최소 기능 가짜 Redis.
     *
     * <p>값(String)과 집합(Set)을 <b>같은 맵에 담지 않는다</b> — 토큰 폐기가
     * 역인덱스 집합을 훑어 값 키를 지우는 구조라, 둘이 섞이면 테스트가
     * 실제 동작과 다르게 통과해버린다.
     */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(ops);
        given(ops.get(anyString())).willAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        org.mockito.BDDMockito.willAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(ops).set(anyString(), anyString(), any(java.time.Duration.class));

        org.springframework.data.redis.core.SetOperations<String, String> setOps =
                mock(org.springframework.data.redis.core.SetOperations.class);
        given(redis.opsForSet()).willReturn(setOps);
        given(setOps.members(anyString()))
                .willAnswer(inv -> sets.get(inv.getArgument(0, String.class)));
        org.mockito.BDDMockito.willAnswer(inv -> {
            String key = inv.getArgument(0);
            Object[] values = inv.getArguments();
            for (int i = 1; i < values.length; i++) {
                sets.computeIfAbsent(key, k -> new java.util.HashSet<>())
                        .add(String.valueOf(values[i]));
            }
            return 1L;
        }).given(setOps).add(anyString(), any(String[].class));

        given(redis.delete(anyString())).willAnswer(inv -> {
            String key = inv.getArgument(0);
            sets.remove(key);
            return store.remove(key) != null;
        });
        given(redis.delete(any(java.util.Collection.class))).willAnswer(inv -> {
            java.util.Collection<String> keys = inv.getArgument(0);
            long removed = 0;
            for (String key : keys) {
                sets.remove(key);
                if (store.remove(key) != null) {
                    removed++;
                }
            }
            return removed;
        });
        given(redis.expire(anyString(), any(java.time.Duration.class))).willReturn(true);

        return redis;
    }

    private String secretIdFor(LocalDate date) {
        String raw = date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + SECRET;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("오늘 날짜로 계산한 secret_id로 토큰이 발급된다")
    void issueWithToday() {
        var issued = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.refreshToken()).isNotBlank();
        assertThat(service.resolveAcademyId(issued.token())).isEqualTo(ACADEMY_ID);
    }

    @Test
    @DisplayName("자정 경계 — 어제·내일 날짜로 계산한 secret_id도 허용한다")
    void midnightBoundaryTolerated() {
        LocalDate today = LocalDate.now(clock);

        assertThat(service.issue(ACAD_CD, CLIENT_ID, secretIdFor(today.minusDays(1))).token()).isNotBlank();
        assertThat(service.issue(ACAD_CD, CLIENT_ID, secretIdFor(today.plusDays(1))).token()).isNotBlank();
    }

    @Test
    @DisplayName("이틀 전 secret_id는 거부한다")
    void tooOldRejected() {
        assertThatThrownBy(() -> service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock).minusDays(2))))
                .isInstanceOf(DsaApiException.class);
    }

    @Test
    @DisplayName("알 수 없는 client_id는 거부한다")
    void unknownClientRejected() {
        assertThatThrownBy(() -> service.issue(ACAD_CD, "unknown", secretIdFor(LocalDate.now(clock))))
                .isInstanceOf(DsaApiException.class);
    }

    @Test
    @DisplayName("잘못된 secret_id는 거부한다")
    void wrongSecretRejected() {
        assertThatThrownBy(() -> service.issue(ACAD_CD, CLIENT_ID, "deadbeef"))
                .isInstanceOf(DsaApiException.class);
    }

    @Test
    @DisplayName("★ acad_cd와 client_id가 다른 지점을 가리키면 거부한다")
    void acadCdMismatchRejected() {
        assertThatThrownBy(() -> service.issue("46", CLIENT_ID, secretIdFor(LocalDate.now(clock))))
                .isInstanceOf(DsaApiException.class);
    }

    @Test
    @DisplayName("acad_cd를 안 보내면 client_id만으로 통과한다 — 기존 동작을 깨지 않는다")
    void acadCdOptional() {
        assertThat(service.issue(null, CLIENT_ID, secretIdFor(LocalDate.now(clock))).token())
                .isNotBlank();
    }

    @Test
    @DisplayName("미상·만료 토큰은 code 910으로 떨어진다")
    void unknownTokenIs910() {
        assertThatThrownBy(() -> service.resolveAcademyId("no-such-token"))
                .isInstanceOf(DsaApiException.class)
                .extracting(e -> ((DsaApiException) e).getDsaCode())
                .isEqualTo(DsaCode.TOKEN_EXPIRED);

        assertThatThrownBy(() -> service.resolveAcademyId(null))
                .isInstanceOf(DsaApiException.class);
    }

    @Test
    @DisplayName("refresh로 access만 재발급되고 refresh는 유지된다")
    void refreshKeepsRefreshToken() {
        var issued = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));

        var refreshed = service.refresh(CLIENT_ID, issued.refreshToken());

        assertThat(refreshed.refreshToken()).isEqualTo(issued.refreshToken());
        assertThat(refreshed.token()).isNotEqualTo(issued.token());
        assertThat(service.resolveAcademyId(refreshed.token())).isEqualTo(ACADEMY_ID);
    }

    @Test
    @DisplayName("★ 다른 지점 client_id로는 남의 refresh를 못 쓴다")
    void refreshIsScopedToAcademy() {
        var issued = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));

        BranchConfig other = new BranchConfig(99L);
        other.issueKioskCredential("client9_1", "other-secret");
        given(repository.findByKioskClientIdAndDeletedFalse("client9_1"))
                .willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.refresh("client9_1", issued.refreshToken()))
                .isInstanceOf(DsaApiException.class);
    }

    @Test
    @DisplayName("★ 갱신하면 이전 access 토큰이 죽는다 — 안 그러면 10일간 함께 살아 있다")
    void refreshKillsPreviousAccessToken() {
        var first = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));
        assertThat(service.resolveAcademyId(first.token())).isEqualTo(ACADEMY_ID);

        var refreshed = service.refresh(CLIENT_ID, first.refreshToken());

        assertThat(service.resolveAcademyId(refreshed.token())).isEqualTo(ACADEMY_ID);
        assertThatThrownBy(() -> service.resolveAcademyId(first.token()))
                .isInstanceOf(DsaApiException.class);
    }

    @Test
    @DisplayName("★ 다른 키오스크는 영향받지 않는다 — 지점당 단말이 여러 대다")
    void refreshDoesNotAffectOtherKiosks() {
        var kioskA = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));
        var kioskB = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));

        service.refresh(CLIENT_ID, kioskA.refreshToken());

        // A만 갱신했는데 B가 튕기면 매번 서로를 쫓아낸다
        assertThat(service.resolveAcademyId(kioskB.token())).isEqualTo(ACADEMY_ID);
    }

    @Test
    @DisplayName("★ 지점 전체 폐기 — 시크릿 유출·단말 분실 시")
    void revokeAllKillsEveryTokenOfBranch() {
        var kioskA = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));
        var kioskB = service.issue(ACAD_CD, CLIENT_ID, secretIdFor(LocalDate.now(clock)));

        long revoked = service.revokeAll(ACADEMY_ID);

        assertThat(revoked).isEqualTo(4);   // access 2 + refresh 2
        assertThatThrownBy(() -> service.resolveAcademyId(kioskA.token()))
                .isInstanceOf(DsaApiException.class);
        assertThatThrownBy(() -> service.resolveAcademyId(kioskB.token()))
                .isInstanceOf(DsaApiException.class);
        // refresh도 죽어야 한다 — 살아 있으면 바로 새 access를 받아간다
        assertThatThrownBy(() -> service.refresh(CLIENT_ID, kioskA.refreshToken()))
                .isInstanceOf(DsaApiException.class);
    }
}
