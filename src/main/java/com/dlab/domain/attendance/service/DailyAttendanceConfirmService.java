package com.dlab.domain.attendance.service;

import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.domain.attendance.repository.AttendanceDailyStatusRepository;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일자 출결 확정 배치.
 *
 * <h2>결석은 태깅 이벤트가 아니다</h2>
 * "안 찍은 것"이라 원장에 남을 수가 없다. 그래서 <b>하루가 끝난 뒤 원장을 훑어
 * 없는 것을 찾아내는</b> 방식이어야 한다 — 지금까지 {@code absence_cnt}가
 * 항상 0이었던 이유다(셀 대상이 원장에 없었다).
 *
 * <h2>당일에는 판정할 수 없다</h2>
 * 등원시각이 지났다고 결석으로 찍으면 <b>늦게 온 학생이 결석으로 남는다.</b>
 * 이미 있는 {@link MissingAttendanceScheduler}는 같은 "안 왔음"을 보지만 목적이 다르다:
 * <ul>
 *   <li>미등원 알림 — 등원시각 정각. <b>재촉</b>이다</li>
 *   <li>이 배치 — 하루 마감 후. <b>확정</b>이다. 통계·벌점의 근거가 된다</li>
 * </ul>
 *
 * <h2>사유는 결석을 없애지 않는다</h2>
 * 승인된 사유가 있어도 <b>결석은 결석</b>이고 {@code excused} 플래그만 붙는다.
 * 규격서·키오스크가 {@code absence_cnt}를 그냥 "결석 횟수"로 정의하고 필드도 하나뿐이라,
 * 무단만 세면 사유결석한 학생이 화면에서 <b>결석 0회</b>로 보인다.
 * 무단만 필요한 쪽(벌점·미등원 알림)이 {@code excused}로 거른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyAttendanceConfirmService {

    /** 등원으로 인정하는 이벤트. 이게 하나도 없으면 결석이다. */
    private static final Set<AttendanceEventType> ARRIVAL = Set.of(
            AttendanceEventType.CHECK_IN, AttendanceEventType.LATE);

    private final StudentEnrollmentRepository enrollmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final AttendanceDailyStatusRepository dailyStatusRepository;
    private final AbsenceReasonRepository absenceReasonRepository;
    private final PeriodMasterRepository periodMasterRepository;
    private final StudyTimeCalculator studyTimeCalculator;
    private final Clock clock;

    /**
     * 그 지점의 하루를 확정한다.
     *
     * <p><b>운영하지 않는 날은 건너뛴다.</b> 교시가 없는 날(일요일·공휴일)은 결석이 아니라
     * "대상 아님"이다 — 행을 만들면 출결률 분모가 늘어 통계가 왜곡된다.
     *
     * @return 확정한 학생 수. 운영일이 아니면 {@code 0}
     */
    @Transactional
    public int confirm(Academy academy, LocalDate date) {
        List<StudentEnrollment> targets = enrollmentRepository.findCurrentByAcademyId(academy.getId())
                .stream()
                // 휴원·퇴원·제적·수료는 대상이 아니다. 매일 결석이 쌓이면 의미가 없다
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .toList();
        if (targets.isEmpty()) {
            return 0;
        }

        boolean operating = !periodMasterRepository.findByDayType(
                academy.getId(), targets.get(0).getYear(), DayType.of(date)).isEmpty();
        if (!operating) {
            log.debug("운영일 아님 — 출결 확정 건너뜀: 지점={}, 일자={}", academy.getName(), date);
            return 0;
        }

        Map<Long, List<AttendanceTaggingLog>> logsByEnrollment = logsOf(academy, date);
        Map<Long, List<AttendanceEventType>> eventsByEnrollment = new HashMap<>();
        logsByEnrollment.forEach((id, logs) -> eventsByEnrollment.put(id,
                logs.stream().map(AttendanceTaggingLog::getEventType).toList()));

        List<com.dlab.domain.period.entity.PeriodMaster> periods =
                periodMasterRepository.findByDayType(
                        academy.getId(), targets.get(0).getYear(), DayType.of(date));
        Set<Long> excusedEnrollments = excusedOf(targets, date);
        Instant now = Instant.now(clock);

        for (StudentEnrollment enrollment : targets) {
            List<AttendanceEventType> events =
                    eventsByEnrollment.getOrDefault(enrollment.getId(), List.of());
            DailyStatus status = statusOf(events);
            boolean excused = excusedEnrollments.contains(enrollment.getId());

            // 지난 날이라 하원이 끝났다 — 마지막 교시 종료로 닫고 계산한다
            int studyMinutes = (int) studyTimeCalculator.calculate(
                    logsByEnrollment.getOrDefault(enrollment.getId(), List.of()),
                    periods, LocalTime.MAX).toMinutes();

            // 이미 확정된 날을 다시 돌 수 있다 — 사유가 뒤늦게 승인되면 무단이 사유로 바뀐다
            AttendanceDailyStatus confirmed = dailyStatusRepository
                    .findByEnrollmentIdAndAttendanceDate(enrollment.getId(), date)
                    .orElseGet(() -> dailyStatusRepository.save(new AttendanceDailyStatus(
                            academy, enrollment, date, status, excused)));
            confirmed.reconfirm(status, excused, now);
            confirmed.recordStudyMinutes(studyMinutes, now);
        }

        log.info("출결 확정: 지점={}, 일자={}, 대상={}명", academy.getName(), date, targets.size());
        return targets.size();
    }

    /**
     * 일자 상태 판정.
     *
     * <p><b>지각이 조퇴보다 앞선다.</b> 지각하고 조퇴한 학생은 둘 다 해당하는데, 상태 필드가
     * 하나뿐이라 하나를 골라야 한다. 지각은 <b>등원 방식</b>이고 조퇴는 <b>퇴실 방식</b>이라
     * 그날을 대표하는 건 지각 쪽이다(벌점도 지각에 붙는다).
     */
    private DailyStatus statusOf(List<AttendanceEventType> events) {
        if (events.stream().noneMatch(ARRIVAL::contains)) {
            return DailyStatus.ABSENT;
        }
        if (events.contains(AttendanceEventType.LATE)) {
            return DailyStatus.LATE;
        }
        if (events.contains(AttendanceEventType.EARLY_LEAVE)) {
            return DailyStatus.EARLY_LEAVE;
        }
        return DailyStatus.PRESENT;
    }

    /** 학생별 그날 원장(시간순). 상태 판정과 순공시간 계산이 함께 쓴다. */
    private Map<Long, List<AttendanceTaggingLog>> logsOf(Academy academy, LocalDate date) {
        Map<Long, List<AttendanceTaggingLog>> result = new HashMap<>();
        taggingLogRepository.findByAcademyIdAndAttendanceDate(academy.getId(), date).stream()
                .sorted(java.util.Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .forEach(log -> result.computeIfAbsent(
                        log.getEnrollment().getId(), k -> new java.util.ArrayList<>()).add(log));
        return result;
    }

    /**
     * 그날 <b>승인된</b> 사유가 있는 학생.
     *
     * <p>제출만 하고 승인 전인 건은 사유로 치지 않는다 — 그러면 신청만 넣어두고
     * 안 나오는 것이 무단결석을 피하는 수단이 된다.
     */
    private Set<Long> excusedOf(List<StudentEnrollment> targets, LocalDate date) {
        Set<Long> targetIds = targets.stream()
                .map(StudentEnrollment::getId)
                .collect(Collectors.toSet());

        return targets.stream()
                .flatMap(e -> absenceReasonRepository
                        .findByEnrollmentIdAndAttendanceDate(e.getId(), date).stream())
                .filter(this::isApproved)
                .map(r -> r.getEnrollment().getId())
                .filter(targetIds::contains)
                .collect(Collectors.toSet());
    }

    private boolean isApproved(AbsenceReason reason) {
        return reason.getApprovalRequest() != null
                && reason.getApprovalRequest().getStatus() == ApprovalStatus.APPROVED;
    }
}
