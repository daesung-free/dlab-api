package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 등원 기준시각에 미등원 학생을 감지하는 배치.
 *
 * <p>기준시각은 지점 공통이지만 지점마다 다를 수 있어, 매분 돌면서 "이번 분에 기준시각이 걸린
 * 지점"만 처리한다. 지점 수가 적으므로 이 방식이 지점별 스케줄을 동적으로 등록하는 것보다
 * 단순하고 안전하다.
 *
 * <p>중복 발송 방지는 알림 쪽 dedupKey가 담당하므로(학생·날짜·수신자당 1건), 이 배치가
 * 재실행되거나 인스턴스가 여러 대여도 같은 알림이 두 번 나가지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissingAttendanceScheduler {

    private final AcademyRepository academyRepository;
    private final MissingAttendanceService missingAttendanceService;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void run() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(TimeConfig.KST);
        LocalTime from = now.toLocalTime().withSecond(0).withNano(0);
        LocalTime to = from.plusMinutes(1);
        LocalDate today = now.toLocalDate();

        List<Academy> academies = academyRepository.findActiveByDeadlineBetween(from, to);
        if (academies.isEmpty()) {
            return;
        }

        for (Academy academy : academies) {
            try {
                missingAttendanceService.detectAndNotify(academy, today);
            } catch (Exception e) {
                // 한 지점이 실패해도 나머지 지점은 계속 처리한다
                log.error("미등원 감지 실패: 지점={}", academy.getName(), e);
            }
        }
    }
}
