package com.dlab.common.monitoring;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health/kiosk} — 외부 모니터가 찔러볼 창구.
 *
 * <p>키오스크는 우리 장애를 알려주지 않으므로(폴백이 가린다) <b>바깥에서 주기적으로
 * 여기를 확인</b>해야 한다. 로그 경보만으로는 로그 수집기가 붙기 전까지 아무도 못 본다.
 *
 * <p><b>빈 이름이 {@code kioskCompat}인 이유</b> — 노출 경로는 health <b>그룹</b>
 * {@code kiosk}가 만든다. 인디케이터까지 {@code kiosk}로 두면 <b>이름이 그룹과 충돌해
 * 기동이 실패한다</b>(실제로 겪었다). 바깥에서 보는 경로는 {@code /actuator/health/kiosk} 그대로다.
 *
 * <p><b>{@code lastCallAt}이 특히 중요하다.</b> 에러율이 0%여도 호출 자체가 없으면
 * 키오스크가 우리를 못 찾고 있는 것이다 — 그 경우 에러율 감시는 조용하다.
 */
@Component("kioskCompat")
@RequiredArgsConstructor
public class KioskHealthIndicator implements HealthIndicator {

    private final KioskHealthMonitor monitor;

    @Override
    public Health health() {
        KioskHealthMonitor.Snapshot snapshot = monitor.peek();

        Health.Builder builder = snapshot.isUnhealthy() ? Health.down() : Health.up();
        builder.withDetail("calls", snapshot.total())
                .withDetail("errors", snapshot.errors())
                .withDetail("errorRate", Math.round(snapshot.errorRate() * 1000) / 10.0 + "%")
                .withDetail("lastCallAt",
                        snapshot.lastCallAt() == null ? "호출 없음" : snapshot.lastCallAt().toString());

        if (!snapshot.errorPaths().isEmpty()) {
            builder.withDetail("errorPaths", snapshot.errorPaths());
        }
        return builder.build();
    }
}
