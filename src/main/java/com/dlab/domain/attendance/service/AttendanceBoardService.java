package com.dlab.domain.attendance.service;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.repository.AttendanceDailyStatusRepository;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentGuardianLinkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 웹 출결 현황 (F-4.3-1).
 *
 * <p>화면(`Attendance.tsx`)은 <b>조회 전용</b>이다 — 승인·반려는 사유신청 화면(F-4.1-6)이 맡는다.
 *
 * <h2>★ 화면 상태 5종과 우리 4종은 다르다</h2>
 * 화면은 {@code ON_TIME · LATE · ABSENT · OUT · EXCUSED}인데 우리 {@link DailyStatus}는
 * {@code PRESENT · LATE · ABSENT · EARLY_LEAVE}다. 요구사항정의서의 5종 표기는
 * <b>하원·복귀가 빠져 순공시간을 계산할 수 없어</b> 우리가 7종 원장 + 4종 일자상태로 바꿨다
 * (CLAUDE.md §3). 화면을 고치지 않고 여기서 매핑해 내린다:
 * <ul>
 *   <li>{@code EXCUSED} — 상태가 아니라 <b>{@code is_excused} 플래그</b>다. 사유가 승인된 건이
 *       결석이든 지각이든 이 값으로 표시된다. 상태와 직교하므로 우리는 합치지 않는다</li>
 *   <li>{@code OUT} — 그 시점에 <b>외출 중</b>인 상태다. 일자 상태가 아니라
 *       원장의 마지막 이벤트에서 나온다</li>
 * </ul>
 *
 * <h2>일자 확정 전에는 원장에서 계산한다</h2>
 * 배치는 새벽 2시에 전날을 확정한다. <b>오늘 화면을 열면 확정된 행이 아직 없다</b> —
 * 그때는 원장을 훑어 즉석에서 판정한다. 안 그러면 오늘 출결이 통째로 비어 보인다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceBoardService {

    private final StudentEnrollmentRepository enrollmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final AttendanceDailyStatusRepository dailyStatusRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final StudentGuardianLinkRepository guardianLinkRepository;
    private final PeriodMasterRepository periodMasterRepository;
    private final StudyTimeCalculator studyTimeCalculator;
    private final Clock clock;

    /**
     * 일별 출결 현황.
     *
     * <p><b>재원생 전원이 대상이다.</b> 태깅한 학생만 보여주면 결석자가 목록에서 사라져
     * "오늘 결석 몇 명"을 셀 수 없다 — 안 온 사람을 찾는 게 이 화면의 목적이다.
     *
     * @param classId 담당 반 필터. 담임은 자기 반만 본다. {@code null}이면 전체
     */
    public List<AttendanceRow> board(AuthPrincipal me, LocalDate date, Long classId) {
        Long academyId = academyOf(me);

        List<StudentEnrollment> targets = enrollmentRepository.findCurrentByAcademyId(academyId)
                .stream()
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .toList();
        if (targets.isEmpty()) {
            return List.of();
        }

        Map<Long, List<AttendanceTaggingLog>> logsByEnrollment = logsOf(academyId, date);
        Map<Long, AttendanceDailyStatus> confirmed = confirmedOf(targets, date);
        Map<Long, ClassAssignment> classes = classesOf(targets);
        Map<Long, String> seats = seatsOf(academyId);
        Map<Long, String> guardianPhones = guardianPhonesOf(targets);

        var periods = periodMasterRepository.findByDayType(
                academyId, targets.get(0).getYear(), DayType.of(date));
        LocalTime until = date.equals(LocalDate.now(clock))
                ? LocalTime.now(clock)
                : LocalTime.MAX;

        return targets.stream()
                .filter(e -> matchesClass(classes.get(e.getId()), classId))
                .map(e -> {
                    List<AttendanceTaggingLog> logs =
                            logsByEnrollment.getOrDefault(e.getId(), List.of());
                    ClassAssignment assignment = classes.get(e.getId());

                    return new AttendanceRow(
                            e.getId(),
                            e.getStudentNo(),
                            e.getStudent().getName(),
                            assignment == null ? null : assignment.getClassMaster().getName(),
                            seats.get(e.getId()),
                            firstArrival(logs),
                            lastDeparture(logs),
                            screenStatus(logs, confirmed.get(e.getId())),
                            excused(confirmed.get(e.getId())),
                            (int) studyTimeCalculator.calculate(logs, periods, until).toMinutes(),
                            guardianPhones.get(e.getId()),
                            unexcusedLate(logs, confirmed.get(e.getId())));
                })
                .sorted(Comparator.comparing(AttendanceRow::studentNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 화면용 상태 판정.
     *
     * <p><b>확정된 일자 상태가 있으면 그걸 쓴다.</b> 없으면(오늘) 원장에서 즉석 판정한다.
     * 외출 중은 확정 상태가 아니라 <b>지금 나가 있는지</b>라, 원장의 마지막 이벤트로만 알 수 있다.
     */
    private ScreenStatus screenStatus(List<AttendanceTaggingLog> logs, AttendanceDailyStatus confirmed) {
        AttendanceEventType last = logs.isEmpty() ? null : logs.get(logs.size() - 1).getEventType();
        if (last == AttendanceEventType.OUTING || last == AttendanceEventType.EXCUSED_OUTING) {
            return ScreenStatus.OUT;
        }

        if (confirmed != null) {
            return switch (confirmed.getFinalStatus()) {
                case PRESENT -> ScreenStatus.ON_TIME;
                case LATE -> ScreenStatus.LATE;
                case ABSENT -> ScreenStatus.ABSENT;
                case EARLY_LEAVE -> ScreenStatus.EARLY_LEAVE;
            };
        }

        // 아직 확정 전 — 원장으로 판정한다
        if (logs.stream().noneMatch(l -> l.getEventType().isArrival())) {
            return ScreenStatus.ABSENT;
        }
        if (logs.stream().anyMatch(l -> l.getEventType() == AttendanceEventType.LATE)) {
            return ScreenStatus.LATE;
        }
        if (last == AttendanceEventType.EARLY_LEAVE) {
            return ScreenStatus.EARLY_LEAVE;
        }
        return ScreenStatus.ON_TIME;
    }

    /**
     * 무단 지각 표시 (0723 요구사항).
     *
     * <p>사유 없이 지각한 건만 화면에 별도 배지가 붙는다 — 사유를 낸 지각과 섞으면
     * 담임이 누구에게 연락해야 하는지 알 수 없다.
     */
    private boolean unexcusedLate(List<AttendanceTaggingLog> logs, AttendanceDailyStatus confirmed) {
        boolean late = logs.stream().anyMatch(l -> l.getEventType() == AttendanceEventType.LATE);
        return late && !excused(confirmed);
    }

    private boolean excused(AttendanceDailyStatus confirmed) {
        return confirmed != null && confirmed.isExcused();
    }

    private LocalTime firstArrival(List<AttendanceTaggingLog> logs) {
        return logs.stream()
                .filter(l -> l.getEventType().isArrival())
                .findFirst()
                .map(this::timeOf)
                .orElse(null);
    }

    /** 하원·조퇴 중 마지막. 외출은 하원이 아니다. */
    private LocalTime lastDeparture(List<AttendanceTaggingLog> logs) {
        return logs.stream()
                .filter(l -> l.getEventType() == AttendanceEventType.CHECK_OUT
                        || l.getEventType() == AttendanceEventType.EARLY_LEAVE)
                .reduce((a, b) -> b)
                .map(this::timeOf)
                .orElse(null);
    }

    private LocalTime timeOf(AttendanceTaggingLog log) {
        return log.getRecordedAt().atZone(ZoneId.systemDefault()).toLocalTime();
    }

    private boolean matchesClass(ClassAssignment assignment, Long classId) {
        if (classId == null) {
            return true;
        }
        return assignment != null && assignment.getClassMaster().getId().equals(classId);
    }

    private Map<Long, List<AttendanceTaggingLog>> logsOf(Long academyId, LocalDate date) {
        Map<Long, List<AttendanceTaggingLog>> result = new HashMap<>();
        taggingLogRepository.findByAcademyIdAndAttendanceDate(academyId, date).stream()
                .sorted(Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .forEach(l -> result.computeIfAbsent(
                        l.getEnrollment().getId(), k -> new java.util.ArrayList<>()).add(l));
        return result;
    }

    private Map<Long, AttendanceDailyStatus> confirmedOf(List<StudentEnrollment> targets,
                                                         LocalDate date) {
        Map<Long, AttendanceDailyStatus> result = new HashMap<>();
        targets.forEach(e -> dailyStatusRepository
                .findByEnrollmentIdAndAttendanceDate(e.getId(), date)
                .ifPresent(s -> result.put(e.getId(), s)));
        return result;
    }

    private Map<Long, ClassAssignment> classesOf(List<StudentEnrollment> targets) {
        Map<Long, ClassAssignment> result = new HashMap<>();
        targets.forEach(e -> classAssignmentRepository
                .findActiveFixedByEnrollmentId(e.getId())
                .ifPresent(a -> result.put(e.getId(), a)));
        return result;
    }

    private Map<Long, String> seatsOf(Long academyId) {
        Map<Long, String> result = new HashMap<>();
        seatAssignmentRepository.findActiveByAcademyId(academyId)
                .forEach(a -> result.put(a.getEnrollment().getId(), a.getSeat().getSeatCd()));
        return result;
    }

    /** 화면 컬럼이 "학부모 연락처"다. 승인자 1인 기준으로 첫 번째만 내린다. */
    private Map<Long, String> guardianPhonesOf(List<StudentEnrollment> targets) {
        Map<Long, String> result = new HashMap<>();
        targets.forEach(e -> guardianLinkRepository
                .findByStudentId(e.getStudent().getId()).stream()
                .map(link -> link.getGuardian().getPhone())
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .ifPresent(p -> result.put(e.getId(), p)));
        return result;
    }

    private Long academyOf(AuthPrincipal me) {
        Long academyId = me.academyScopeFilter();
        if (academyId == null) {
            throw new com.dlab.common.exception.BusinessException(
                    com.dlab.common.exception.ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        return academyId;
    }

    /** 화면(`Attendance.tsx`)이 쓰는 상태값. 우리 {@link DailyStatus}와 축이 다르다. */
    public enum ScreenStatus {
        ON_TIME, LATE, ABSENT, OUT, EARLY_LEAVE
    }

    /**
     * @param studyMinutes 순공시간(분). 확정 전이면 조회 시점까지만 센다
     * @param excused      사유 승인 여부. 상태와 <b>직교하는 축</b>이다
     */
    public record AttendanceRow(
            Long enrollmentId,
            String studentNo,
            String name,
            String className,
            String seatCd,
            LocalTime checkInAt,
            LocalTime checkOutAt,
            ScreenStatus status,
            boolean excused,
            int studyMinutes,
            String guardianPhone,
            boolean unexcusedLate
    ) {

        /** 화면 표기 {@code "N시간 MM분"}. */
        public String studyTimeLabel() {
            return Duration.ofMinutes(studyMinutes).toHours() + "시간 "
                    + String.format("%02d", studyMinutes % 60) + "분";
        }
    }
}
