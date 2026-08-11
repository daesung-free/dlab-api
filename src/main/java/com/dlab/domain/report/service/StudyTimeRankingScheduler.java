package com.dlab.domain.report.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.report.entity.RankingPeriod;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 순공시간 랭킹 적재 배치 (F-4.11-6).
 *
 * <p><b>03시에 돈다 — 출결 확정(02시) 다음이다.</b> 순공시간은 확정 배치가 계산해서
 * 넣는 값이라, 먼저 돌면 어제 랭킹이 통째로 비거나 반만 찬다.
 *
 * <p><b>어제를 기준으로 3종을 다 만든다.</b> 주간·월간은 어제가 속한 기간을 통째로
 * 다시 만들므로, 기간이 끝나기 전에도 진행 중인 순위가 매일 갱신된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudyTimeRankingScheduler {

    private final StudyTimeRankingService rankingService;
    private final Clock clock;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void run() {
        LocalDate yesterday = ZonedDateTime.now(clock)
                .withZoneSameInstant(TimeConfig.KST)
                .toLocalDate()
                .minusDays(1);

        for (RankingPeriod period : RankingPeriod.values()) {
            try {
                int saved = rankingService.rebuild(period, yesterday);
                log.info("순공 랭킹 적재: 기간={}, 기준일={}, 건수={}", period, yesterday, saved);
            } catch (Exception e) {
                // 한 기간이 실패해도 나머지는 만든다. 다시 돌리면 그 기간만 복구된다
                log.error("순공 랭킹 적재 실패: 기간={}, 기준일={}", period, yesterday, e);
            }
        }
    }
}
