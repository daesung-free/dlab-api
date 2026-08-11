package com.dlab.domain.approval.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 자동 재요청 · 직원 이양 배치 (I-20, 0803 답변서).
 *
 * <p><b>매분 돈다.</b> 타임아웃이 10분이라 분 단위면 충분하고, 더 촘촘히 돌 이유가 없다.
 *
 * <p><b>재요청과 이양을 한 번에 처리하지 않고 나눠 부른다.</b> 재요청이 실패해도 이양은
 * 돌아야 하고, 반대로 이양이 막혀도(담임 미지정 등) 재요청은 계속 나가야 한다.
 *
 * <p>중복 발송은 각 서비스가 막는다 — {@code reminderSentAt}·{@code handedOverAt}이
 * NULL인 건만 대상이라, 배치가 여러 번 돌거나 인스턴스가 여러 대여도 한 번만 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalReminderScheduler {

    private final ApprovalService approvalService;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void run() {
        try {
            approvalService.sendReminders();
        } catch (Exception e) {
            // 한쪽이 터져도 다른 쪽은 돌아야 한다
            log.error("승인 자동 재요청 실패", e);
        }

        try {
            approvalService.handOverToStaff();
        } catch (Exception e) {
            log.error("승인 직원 이양 실패", e);
        }
    }
}
