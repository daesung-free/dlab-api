package com.dlab.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.config.TimeConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 키오스크 구획 감시.
 *
 * <p><b>이 구획은 비즈니스 거절도 HTTP 200</b>이라(code 113·101 등),
 * 200이 아닌 응답은 전부 우리 잘못이다. 거절을 장애로 오인하지 않는지가 핵심이다.
 */
class KioskHealthMonitorTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-05T01:00:00Z"),
            ZoneId.of(TimeConfig.KST.getId()));

    private KioskHealthMonitor monitor() {
        return new KioskHealthMonitor(clock);
    }

    @Test
    @DisplayName("★ 비즈니스 거절(200)은 장애가 아니다 — code 113·101이 200으로 나간다")
    void businessRejectionIsNotAnError() {
        KioskHealthMonitor m = monitor();
        for (int i = 0; i < 10; i++) {
            m.record("/kiosk/setAttendStd", 200);
        }

        var snapshot = m.peek();
        assertThat(snapshot.total()).isEqualTo(10);
        assertThat(snapshot.errors()).isZero();
        assertThat(snapshot.isUnhealthy()).isFalse();
    }

    @Test
    @DisplayName("★ 500은 장애다 — 키오스크는 폴백해서 조용히 넘어간다")
    void serverErrorCounts() {
        KioskHealthMonitor m = monitor();
        for (int i = 0; i < 8; i++) {
            m.record("/kiosk/getStdInfoList", 200);
        }
        m.record("/kiosk/getReceiptInfo", 500);
        m.record("/kiosk/getReceiptInfo", 500);

        var snapshot = m.peek();
        assertThat(snapshot.errors()).isEqualTo(2);
        assertThat(snapshot.errorRate()).isEqualTo(0.2);
        assertThat(snapshot.isUnhealthy()).isTrue();
        assertThat(snapshot.errorPaths()).containsEntry("/kiosk/getReceiptInfo", 2L);
    }

    @Test
    @DisplayName("★ 표본이 적으면 경보하지 않는다 — 1건 실패로 100%가 되면 매번 울린다")
    void smallSampleDoesNotAlert() {
        KioskHealthMonitor m = monitor();
        m.record("/kiosk/getStdInfo", 500);

        var snapshot = m.peek();
        assertThat(snapshot.errorRate()).isEqualTo(1.0);
        assertThat(snapshot.isUnhealthy()).isFalse();   // 표본 1건
    }

    @Test
    @DisplayName("임계(10%) 이하면 정상")
    void belowThresholdIsHealthy() {
        KioskHealthMonitor m = monitor();
        for (int i = 0; i < 19; i++) {
            m.record("/kiosk/getStdInfoList", 200);
        }
        m.record("/kiosk/getStdInfoList", 503);

        assertThat(m.peek().isUnhealthy()).isFalse();   // 5%
    }

    @Test
    @DisplayName("점검하면 창이 비워진다 — 지나간 장애가 계속 울리면 안 된다")
    void inspectResetsWindow() {
        KioskHealthMonitor m = monitor();
        for (int i = 0; i < 10; i++) {
            m.record("/kiosk/getStdInfoList", 500);
        }

        m.inspect();

        assertThat(m.peek().total()).isZero();
        assertThat(m.peek().errors()).isZero();
    }

    @Test
    @DisplayName("★ 한 번도 안 불렸으면 lastCallAt이 null — 키오스크가 우리를 못 찾는 상태다")
    void neverCalledIsDistinguishable() {
        KioskHealthMonitor m = monitor();

        assertThat(m.peek().lastCallAt()).isNull();
        assertThat(m.peek().isUnhealthy()).isFalse();   // 에러율로는 안 잡힌다

        m.record("/kiosk/getStdInfoList", 200);
        assertThat(m.peek().lastCallAt()).isNotNull();
    }

    @Test
    @DisplayName("호출이 없으면 점검이 아무것도 하지 않는다")
    void inspectWithNoCallsIsNoop() {
        KioskHealthMonitor m = monitor();

        m.inspect();   // 예외 없이 통과해야 한다

        assertThat(m.peek().total()).isZero();
    }
}
