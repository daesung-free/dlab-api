package com.dlab.domain.access.service;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 오전 출근 확인 배치 — <b>매일 07:05 (KST)</b> (긴급출입 기능 B).
 *
 * <p>정식 개원이 07:00 이고 5분을 준 뒤 본다. 그 시각까지 출근 기록이 하나도 없는 지점을
 * 찾아 관리자에게 알린다.
 *
 * <h2>★ 휴일을 빼지 않는다</h2>
 * 요구사항의 「매일」을 글자 그대로 돌린다 — 일요일·공휴일·휴관일도 예외 없다
 * (2026-10-08 디랩 결정). 출근 태깅을 <b>전 지점에서 쓰고 있는 것이 확인</b>됐으므로
 * 기록이 늘 0인 지점은 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MorningAttendanceCheckScheduler {

    private final MorningAttendanceCheckService checkService;
    private final java.time.Clock clock;

    @Scheduled(cron = "0 5 7 * * *", zone = "Asia/Seoul")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        try {
            int needed = checkService.runAll(today);
            // 0건이어도 남긴다 — "검사가 돌았다"는 사실이 로그에 있어야
            // 「발송 안 됨」과 「검사 안 됨」이 구분된다
            log.info("오전 출근 확인: {} — 미확인 {}개 지점", today, needed);
        } catch (Exception e) {
            // 다음 날 다시 돈다. 여기서 멈추면 그날 전 지점이 검사되지 않는다
            log.error("오전 출근 확인 배치 실패: {}", today, e);
        }
    }
}
