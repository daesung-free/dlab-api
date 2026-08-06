package com.dlab.domain.attendance.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceDailyStatusRepository;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 순공시간 수동 재계산 (화면 "학습시간 일괄계산").
 *
 * <p>순공시간은 배치가 매일 확정해 저장한다. 그런데 저장했기 때문에 <b>나중의 정정이
 * 자동으로 반영되지 않는다</b> — 그래서 관리자가 다시 돌릴 수 있어야 한다:
 * <ul>
 *   <li>출결을 수정했을 때</li>
 *   <li>교시(급식·쉬는시간)를 바꾸고 그날부터 다시 계산하고 싶을 때</li>
 * </ul>
 *
 * <p><b>오늘은 대상이 아니다.</b> 아직 하원 전이라 값이 계속 늘어나므로 저장할 시점이 아니다.
 * 조회 화면이 그날치만 즉석 계산한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyTimeRecalculationService {

    /** 한 번에 다시 돌릴 수 있는 최대 기간. 실수로 1년을 넣으면 운영 중에 DB가 멈춘다. */
    private static final int MAX_DAYS = 62;

    private final AcademyRepository academyRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final AttendanceDailyStatusRepository dailyStatusRepository;
    private final PeriodMasterRepository periodMasterRepository;
    private final StudyTimeCalculator studyTimeCalculator;
    private final Clock clock;

    /**
     * 기간 내 순공시간을 다시 계산해 덮어쓴다.
     *
     * <p><b>확정된 행만 갱신한다.</b> 없는 날은 만들지 않는다 — 일자 상태 확정은
     * {@link DailyAttendanceConfirmService}의 책임이고, 여기서 같이 만들면
     * 결석 판정이 두 곳에서 나온다.
     *
     * @return 갱신한 행 수
     */
    @Transactional
    public int recalculate(AuthPrincipal me, LocalDate from, LocalDate to) {
        Long academyId = academyOf(me);
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to.isBefore(today) ? to : today.minusDays(1);

        if (end.isBefore(from)) {
            return 0;   // 오늘 이후만 지정했다 — 저장할 대상이 없다
        }
        if (from.plusDays(MAX_DAYS).isBefore(end)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 %d일까지만 다시 계산할 수 있습니다.".formatted(MAX_DAYS));
        }

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        Instant now = Instant.now(clock);
        Map<DayType, List<PeriodMaster>> periodCache = new EnumMap<>(DayType.class);
        int updated = 0;

        for (LocalDate date = from; !date.isAfter(end); date = date.plusDays(1)) {
            updated += recalculateDay(academy, date, periodCache, now);
        }

        log.info("순공시간 재계산: 지점={}, {}~{}, {}건", academy.getName(), from, end, updated);
        return updated;
    }

    private int recalculateDay(Academy academy, LocalDate date,
                               Map<DayType, List<PeriodMaster>> periodCache, Instant now) {
        Map<Long, List<AttendanceTaggingLog>> logsByEnrollment = new HashMap<>();
        taggingLogRepository.findByAcademyIdAndAttendanceDate(academy.getId(), date).stream()
                .sorted(Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .forEach(l -> logsByEnrollment.computeIfAbsent(
                        l.getEnrollment().getId(), k -> new ArrayList<>()).add(l));

        List<AttendanceDailyStatus> confirmed =
                dailyStatusRepository.findByAcademyIdAndAttendanceDate(academy.getId(), date);
        if (confirmed.isEmpty()) {
            return 0;
        }

        int updated = 0;
        for (AttendanceDailyStatus status : confirmed) {
            List<PeriodMaster> periods = periodCache.computeIfAbsent(
                    DayType.of(date),
                    dt -> periodMasterRepository.findByDayType(
                            academy.getId(), status.getEnrollment().getYear(), dt));

            int minutes = (int) studyTimeCalculator.calculate(
                    logsByEnrollment.getOrDefault(status.getEnrollment().getId(), List.of()),
                    periods, LocalTime.MAX).toMinutes();

            status.recordStudyMinutes(minutes, now);
            updated++;
        }
        return updated;
    }

    private Long academyOf(AuthPrincipal me) {
        Long academyId = me.academyScopeFilter();
        if (academyId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        return academyId;
    }
}
