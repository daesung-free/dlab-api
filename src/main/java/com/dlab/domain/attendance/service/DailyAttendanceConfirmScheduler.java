package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 전날 출결을 확정하는 배치.
 *
 * <p><b>02시에 돈다.</b> 야간자습이 22시에 끝나고 늦게 하원하는 학생이 있어 자정 직후는
 * 이르다. 새벽 4시 학생 동기화(키오스크)보다는 앞서야 그날 명단 기준이 어긋나지 않는다.
 *
 * <p><b>어제만 처리한다.</b> 오늘을 확정하면 아직 등원하지 않은 학생이 결석으로 찍힌다.
 *
 * <p>재실행해도 안전하다 — 확정은 기존 행을 덮어쓴다. 사유가 뒤늦게 승인되면
 * 다시 돌려서 무단을 사유로 정정할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyAttendanceConfirmScheduler {

    private final AcademyRepository academyRepository;
    private final DailyAttendanceConfirmService confirmService;
    private final Clock clock;

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void run() {
        LocalDate yesterday = ZonedDateTime.now(clock)
                .withZoneSameInstant(TimeConfig.KST)
                .toLocalDate()
                .minusDays(1);

        for (Academy academy : academyRepository.findAll()) {
            if (academy.isDeleted() || !academy.isActive()) {
                continue;
            }
            try {
                confirmService.confirm(academy, yesterday);
            } catch (Exception e) {
                // 한 지점이 실패해도 나머지는 계속 처리한다.
                // 실패한 지점은 다시 돌리면 되므로 전체를 멈추지 않는다
                log.error("출결 확정 실패: 지점={}, 일자={}", academy.getName(), yesterday, e);
            }
        }
    }
}
