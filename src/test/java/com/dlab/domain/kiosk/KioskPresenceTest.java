package com.dlab.domain.kiosk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.repository.BranchConfigRepository;
import com.dlab.domain.kiosk.service.BranchConfigService;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskPresenceService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 단말 생존 표시.
 *
 * <p>지키려는 것 — <b>호출이 시각으로 남을 것</b>, <b>호출마다 UPDATE 하지 않을 것</b>,
 * <b>한 번도 안 부른 지점이 「온라인」으로 보이지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class KioskPresenceTest {

    private static final String CLIENT_ID = "kiosk-presence-client";
    private static final String SECRET = "kiosk-presence-secret";

    @Autowired EntityManager em;
    @Autowired DsaTokenService tokenService;
    @Autowired BranchConfigRepository configRepository;
    @Autowired BranchConfigService configService;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    /** ★ 엿본다 — 실제 동작은 그대로 두고 「불렸는가」만 본다. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    KioskPresenceService presence;

    Academy bundang;
    Academy ilsan;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        bundang = new Academy("31", "분당", LocalTime.of(8, 20));
        ilsan = new Academy("32", "일산", LocalTime.of(8, 20));
        em.persist(bundang);
        em.persist(ilsan);

        BranchConfig config = new BranchConfig(bundang.getId());
        config.issueKioskCredential(CLIENT_ID, SECRET);
        em.persist(config);
        em.persist(new BranchConfig(ilsan.getId()));
        em.flush();

        token = tokenService.issue("31", CLIENT_ID, md5Secret()).token();
    }

    /**
     * ⚠️ <b>값이 DB 에 떨어지는 것까지는 여기서 단정하지 않는다.</b>
     * 기록이 {@code REQUIRES_NEW} 라 <b>테스트의 미커밋 데이터를 보지 못한다</b> —
     * 운영에서는 설정 행이 이미 커밋돼 있어 정상이다. 그 분리가 일부러 둔 것이라
     * (통신 기록이 실패해도 태깅은 가야 한다) 테스트를 위해 풀지 않는다.
     * 그래서 <b>「부르면 기록을 시도한다」</b>까지를 지킨다.
     */
    @Test
    @DisplayName("키오스크가 부르면 기록된다 — 하트비트가 없어 호출 시각이 유일한 신호다")
    void 호출하면_기록을_시도한다() {
        assertThat(config(bundang).getKioskLastSeenAt())
                .as("토큰 발급만으로는 아직 아무 기록이 없다")
                .isNull();

        Long resolved = tokenService.resolveAcademyId(token);

        assertThat(resolved).isEqualTo(bundang.getId());
        verify(presence).seen(bundang.getId());
    }

    @Test
    @DisplayName("★ 호출마다 UPDATE 하지 않는다 — 등·하원 시간대에 같은 행을 계속 때린다")
    void 간격_안에서는_갱신하지_않는다() {
        Instant now = Instant.now(clock);
        BranchConfig config = config(bundang);

        assertThat(config.touchKioskSeen(now))
                .as("처음에는 기록한다")
                .isTrue();
        assertThat(config.touchKioskSeen(now.plusSeconds(5)))
                .as("5초 뒤 호출은 건너뛴다")
                .isFalse();
        assertThat(config.getKioskLastSeenAt())
                .as("건너뛴 호출이 값을 밀어내지 않는다")
                .isEqualTo(now);

        assertThat(config.touchKioskSeen(now.plusSeconds(120)))
                .as("간격을 넘기면 다시 기록한다")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 한 번도 안 부른 지점은 온라인이 아니다 — 「모른다」를 「정상」으로 보이게 하면 안 된다")
    void 기록이_없으면_오프라인이다() {
        Instant now = Instant.now(clock);

        assertThat(config(ilsan).isKioskOnline(now))
                .as("일산은 아직 한 번도 부르지 않았다")
                .isFalse();

        BranchConfig config = config(bundang);
        config.touchKioskSeen(now);

        assertThat(config.isKioskOnline(now))
                .as("방금 불렀으면 온라인이다")
                .isTrue();
        assertThat(config.isKioskOnline(now.plus(BranchConfig.ONLINE_WINDOW).plusSeconds(1)))
                .as("판정 시간을 넘기면 오프라인이다 — ⚠️ 한산한 시간대에는 그렇게 보일 수 있다")
                .isFalse();
    }

    @Test
    @DisplayName("지점 설정 조회에 두 칸이 함께 내려간다 — 판정 기준은 서버가 정한다")
    void 조회에_함께_내려간다() {
        config(bundang).touchKioskSeen(Instant.now(clock));
        em.flush();

        BranchConfigService.View view = configService.get(bundang.getId());
        assertThat(view.kioskLastSeenAt()).isNotNull();
        assertThat(view.kioskOnline()).isTrue();

        BranchConfigService.View quiet = configService.get(ilsan.getId());
        assertThat(quiet.kioskLastSeenAt()).isNull();
        assertThat(quiet.kioskOnline()).isFalse();
    }

    @Test
    @DisplayName("판정 시간이 갱신 간격보다 길다 — 반대면 막 기록한 단말도 오프라인으로 보인다")
    void 판정시간이_갱신간격보다_길다() {
        assertThat(BranchConfig.ONLINE_WINDOW)
                .isGreaterThan(Duration.ofMinutes(1));
    }

    private BranchConfig config(Academy academy) {
        return configRepository.findByAcademyIdAndDeletedFalse(academy.getId()).orElseThrow();
    }

    private String md5Secret() throws Exception {
        String raw = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + SECRET;
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
