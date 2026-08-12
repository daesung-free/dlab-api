package com.dlab.domain.firewall.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 해제 만료 차단 배치 (F-4.11-10).
 *
 * <p><b>1분마다 돈다.</b> 해제 시간이 분 단위(최대 300분)라 시간 단위로 돌면 학생이
 * 최대 한 시간을 더 쓴다 — 신청 시간의 의미가 없어진다.
 *
 * <p>훑는 대상이 "해제중이면서 종료 시각이 지난 것"뿐이라 평소에는 0건이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FirewallExpiryScheduler {

    private final FirewallAdminService firewallAdminService;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void run() {
        try {
            int closed = firewallAdminService.expireOverdue();
            if (closed > 0) {
                log.info("방화벽 해제 만료 처리: {}건", closed);
            }
        } catch (Exception e) {
            // 다음 분에 다시 잡는다. 여기서 멈추면 열린 채로 남는다
            log.error("방화벽 해제 만료 처리 실패", e);
        }
    }
}
