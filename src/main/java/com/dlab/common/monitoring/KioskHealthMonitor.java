package com.dlab.common.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 키오스크 구획({@code /auth/**}·{@code /kiosk/**}) 건강 감시.
 *
 * <h2>왜 별도로 감시해야 하나</h2>
 * <b>우리 장애가 키오스크 화면에서는 안 보인다.</b> 키오스크 백엔드는 DSA 장애에 대비해
 * 관대한 폴백을 갖고 있다 — 급식 조회가 실패하면 <b>전부 허용</b>하고, 출결 응답에
 * {@code att_gn}이 없으면 로컬 판별로 넘어간다. 키오스크를 손대지 않기로 했으므로
 * 이 폴백은 제거할 수 없다(CLAUDE.md §3).
 *
 * <p>로컬 E2E에서 실제로 확인됐다:
 * <ul>
 *   <li>{@code getMealApplyYN} 미구현 → 신청 안 한 학생이 전원 통과. <b>에러 화면 없음</b></li>
 *   <li>{@code getReceiptInfo} 500 → 빈 배열로 폴백. <b>화면만 비고 경고 없음</b></li>
 * </ul>
 * 둘 다 키오스크 쪽에서는 아무 이상이 안 보였다. 우리가 안 보면 아무도 모른다.
 *
 * <h2>HTTP 상태만 봐도 충분하다</h2>
 * 이 구획은 <b>비즈니스 거절도 HTTP 200</b>으로 나간다({@code code 113}·{@code 101} 등).
 * 그래서 <b>200이 아닌 응답은 전부 우리 잘못</b>이다 — 거절을 장애로 오인할 일이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KioskHealthMonitor {

    /** 이 창 안의 호출만 본다. 지나간 장애가 계속 경보를 울리지 않게 한다. */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** 이 비율을 넘으면 경보. */
    private static final double ERROR_RATE_THRESHOLD = 0.1;

    /** 표본이 적으면 비율이 요동친다 — 1건 실패로 100%가 되는 걸 막는다. */
    private static final int MIN_SAMPLES = 5;

    private final Clock clock;

    private final Map<String, LongAdder> totalByPath = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errorByPath = new ConcurrentHashMap<>();
    private volatile Instant windowStartedAt;
    private volatile Instant lastCallAt;

    /**
     * 호출 1건 기록. 로깅 필터에서 부른다.
     *
     * <p><b>실패해도 요청을 막지 않는다</b> — 감시 장치가 서비스를 죽이면 안 된다.
     */
    public void record(String path, int status) {
        lastCallAt = Instant.now(clock);
        if (windowStartedAt == null) {
            windowStartedAt = lastCallAt;
        }
        totalByPath.computeIfAbsent(path, k -> new LongAdder()).increment();
        if (status >= 400) {
            errorByPath.computeIfAbsent(path, k -> new LongAdder()).increment();
        }
    }

    /**
     * 창을 닫고 점검한다.
     *
     * <p><b>경보는 로그로 낸다.</b> 알림 채널(알림톡·FCM)은 문구가 미확정이고(§4),
     * 애초에 운영 경보를 학부모용 채널로 보낼 수는 없다. 로그 수집기가 붙으면
     * {@code ERROR} 한 줄만 잡으면 된다.
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void inspect() {
        Snapshot snapshot = drain();
        if (snapshot.total() == 0) {
            return;
        }

        if (snapshot.isUnhealthy()) {
            log.error("★ 키오스크 구획 에러율 경보 — 최근 {}분간 {}건 중 {}건 실패 ({}%). 경로별: {}",
                    WINDOW.toMinutes(), snapshot.total(), snapshot.errors(),
                    Math.round(snapshot.errorRate() * 100), snapshot.errorPaths());
        } else if (snapshot.errors() > 0) {
            log.warn("키오스크 구획 실패 {}건/{}건. 경로별: {}",
                    snapshot.errors(), snapshot.total(), snapshot.errorPaths());
        }
    }

    /** 현재 창 상태. {@link KioskHealthIndicator}가 외부 모니터에 노출한다. */
    public Snapshot peek() {
        return snapshot(false);
    }

    private Snapshot drain() {
        return snapshot(true);
    }

    private synchronized Snapshot snapshot(boolean reset) {
        long total = totalByPath.values().stream().mapToLong(LongAdder::sum).sum();
        long errors = errorByPath.values().stream().mapToLong(LongAdder::sum).sum();
        Map<String, Long> errorPaths = new java.util.LinkedHashMap<>();
        errorByPath.forEach((path, adder) -> {
            if (adder.sum() > 0) {
                errorPaths.put(path, adder.sum());
            }
        });

        Snapshot snapshot = new Snapshot(total, errors, errorPaths, lastCallAt);
        if (reset) {
            totalByPath.clear();
            errorByPath.clear();
            windowStartedAt = Instant.now(clock);
        }
        return snapshot;
    }

    /**
     * @param lastCallAt 마지막 호출 시각. <b>{@code null}이면 아직 한 번도 안 불렸다</b> —
     *                   영업시간 중이라면 키오스크가 우리를 아예 못 찾고 있다는 뜻이다
     */
    public record Snapshot(long total, long errors, Map<String, Long> errorPaths,
                           Instant lastCallAt) {

        public double errorRate() {
            return total == 0 ? 0 : (double) errors / total;
        }

        public boolean isUnhealthy() {
            return total >= MIN_SAMPLES && errorRate() > ERROR_RATE_THRESHOLD;
        }
    }
}
