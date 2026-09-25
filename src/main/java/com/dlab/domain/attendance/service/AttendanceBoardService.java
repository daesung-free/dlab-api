package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
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
    private final com.dlab.domain.user.service.StudentListEnricher studentListEnricher;
    private final PeriodMasterRepository periodMasterRepository;
    private final StudyTimeCalculator studyTimeCalculator;
    private final com.dlab.common.excel.ExcelExporter excelExporter;
    private final com.dlab.domain.user.service.HomeroomScopeService homeroomScopeService;
    private final Clock clock;

    /** 화면 표기 그대로. 코드값을 그대로 내리면 받는 사람이 못 읽는다. */
    /** 기간 조회 상한. 행이 학생×날짜라 넘어가면 화면이 감당하지 못한다. */
    private static final int MAX_RANGE_DAYS = 31;

    private static final Map<ScreenStatus, String> STATUS_LABELS = Map.of(
            ScreenStatus.ON_TIME, "정상 등원",
            ScreenStatus.LATE, "지각",
            ScreenStatus.ABSENT, "결석",
            ScreenStatus.OUT, "외출",
            ScreenStatus.EARLY_LEAVE, "조퇴");

    /** 화면 컬럼과 순서를 맞춘다 — 화면에서 본 대로 파일이 나와야 대조가 된다. */
    private static final com.dlab.common.excel.ColumnMapping EXPORT_MAPPING =
            com.dlab.common.excel.ColumnMapping.builder()
                    .required("studentNo", "학번")
                    .required("name", "이름")
                    .optional("className", "반")
                    .optional("seatCd", "좌석")
                    .optional("checkInAt", "등원")
                    .optional("checkOutAt", "하원")
                    .optional("status", "상태")
                    .optional("excused", "사유")
                    .optional("studyTime", "순공시간")
                    .optional("guardianPhone", "학부모 연락처")
                    .optional("note", "비고");

    /**
     * 일별 출결 현황.
     *
     * <p><b>재원생 전원이 대상이다.</b> 태깅한 학생만 보여주면 결석자가 목록에서 사라져
     * "오늘 결석 몇 명"을 셀 수 없다 — 안 온 사람을 찾는 게 이 화면의 목적이다.
     *
     * @param classId 담당 반 필터. 담임은 자기 반만 본다. {@code null}이면 전체
     * @param academyId 조회할 지점. <b>비우면 내 지점</b>이고, 전 지점 권한자는 지정해야 한다
     */
    /**
     * 기간 출결 현황.
     *
     * <p>★ <b>행이 학생×날짜로 늘어난다.</b> 하루 조회는 "재원생 1인 1행"이라 결석자를
     * 셀 수 있는데, 기간은 그 성질이 사라진다 — 화면 상단 통계의 의미도 달라진다.
     *
     * <p><b>범위를 {@value #MAX_RANGE_DAYS}일로 제한한다.</b> 300명 × 한 달이면 9천 행이고
     * 그 이상은 화면이 감당하지 못한다. 페이징이 없는 응답이라 더 크게 열면 조용히 느려진다.
     */
    public List<AttendanceRow> board(AuthPrincipal me, Long academyId,
                                     LocalDate from, LocalDate to, Long classId) {
        if (from.isAfter(to)) {
            throw new com.dlab.common.exception.BusinessException(com.dlab.common.exception.ErrorCode.INVALID_REQUEST, "조회 기간이 올바르지 않습니다.");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new com.dlab.common.exception.BusinessException(com.dlab.common.exception.ErrorCode.INVALID_REQUEST,
                    "조회 기간은 최대 %d일입니다.".formatted(MAX_RANGE_DAYS));
        }

        return from.datesUntil(to.plusDays(1))
                .flatMap(d -> board(me, academyId, d, classId).stream())
                .toList();
    }

    public List<AttendanceRow> board(AuthPrincipal me, Long academyId, LocalDate date, Long classId) {
        Long scope = me.requireAcademyScope(academyId);

        List<StudentEnrollment> targets = enrollmentRepository.findCurrentByAcademyId(scope)
                .stream()
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .toList();
        if (targets.isEmpty()) {
            return List.of();
        }

        Map<Long, List<AttendanceTaggingLog>> logsByEnrollment = logsOf(scope, date);
        Map<Long, AttendanceDailyStatus> confirmed = confirmedOf(targets, date);
        Map<Long, ClassAssignment> classes = classesOf(targets);
        Map<Long, String> seats = seatsOf(scope);
        Map<Long, String> guardianPhones = guardianPhonesOf(targets);

        var periods = periodMasterRepository.findByDayType(
                scope, targets.get(0).getYear(), DayType.of(date));
        LocalTime until = date.equals(LocalDate.now(clock))
                ? LocalTime.now(clock)
                : LocalTime.MAX;

        // ★ 담임은 맡은 학생만 본다. classId는 화면이 고르는 필터라 빼고 부르면 지점 전체가 나갔다.
        //   반이 아니라 학생으로 거른다 — 담임 예외 지정된 학생이 새 담임에게 보여야 한다
        var studentFilter = homeroomScopeService.resolveStudentFilter(
                me, targets.get(0).getYear(), classId);
        if (studentFilter.blocksEverything()) {
            return List.of();
        }

        return targets.stream()
                .filter(e -> studentFilter.matches(e.getId()))
                .map(e -> {
                    List<AttendanceTaggingLog> logs =
                            logsByEnrollment.getOrDefault(e.getId(), List.of());
                    ClassAssignment assignment = classes.get(e.getId());

                    return new AttendanceRow(
                            date,
                            e.getId(),
                            e.getStudentNo(),
                            e.getStudent().getName(),
                            assignment == null ? null : assignment.getClassMaster().getName(),
                            seats.get(e.getId()),
                            firstArrival(logs),
                            lastDeparture(logs),
                            screenStatus(date, logs, confirmed.get(e.getId())),
                            excused(confirmed.get(e.getId())),
                            studyMinutes(confirmed.get(e.getId()), logs, periods, until),
                            // ★ 연락처 원본은 상위 관리자만 본다(실행가이드 3.2). 이 화면은 담임도
                            //   매일 보는데 학부모 번호가 원본으로 나가고 있었다 — 학생 목록과 같게 가린다
                            com.dlab.common.privacy.PersonalDataPolicy.phone(me, guardianPhones.get(e.getId())),
                            unexcusedLate(logs, confirmed.get(e.getId())),
                            !com.dlab.common.privacy.PersonalDataPolicy.canViewRaw(me));
                })
                .sorted(Comparator.comparing(AttendanceRow::studentNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 순공시간.
     *
     * <p><b>확정된 값이 있으면 그걸 읽는다.</b> 배치가 저장해둔 값이라 교시를 나중에 바꿔도
     * 그날 기준이 유지된다 — 매번 다시 계산하면 과거 순공시간이 소급해서 바뀐다.
     *
     * <p>오늘은 저장하지 않는다. 아직 하원 전이라 계속 늘어나는 값이어서,
     * 저장하면 분 단위로 갱신해야 한다. 조회 시점까지 즉석 계산한다.
     */
    private int studyMinutes(AttendanceDailyStatus confirmed,
                             List<AttendanceTaggingLog> logs,
                             List<com.dlab.domain.period.entity.PeriodMaster> periods,
                             LocalTime until) {
        if (confirmed != null && confirmed.getStudyMinutes() != null) {
            return confirmed.getStudyMinutes();
        }
        return (int) studyTimeCalculator.calculate(logs, periods, until).toMinutes();
    }

    /**
     * 화면용 상태 판정.
     *
     * <p><b>확정된 일자 상태가 있으면 그걸 쓴다.</b> 없으면(오늘) 원장에서 즉석 판정한다.
     * 외출 중은 확정 상태가 아니라 <b>지금 나가 있는지</b>라, 원장의 마지막 이벤트로만 알 수 있다.
     */
    private ScreenStatus screenStatus(LocalDate date, List<AttendanceTaggingLog> logs,
                                      AttendanceDailyStatus confirmed) {
        // ★ 아직 오지 않은 날은 결석이 아니다.
        //   이 분기가 없으면 아래 "등원 태깅이 없다 → ABSENT"에 걸려 미래 날짜를
        //   조회한 순간 재원생 전원이 결석으로 나간다.
        //   확정 행이 있으면 그건 실제 판정이므로 그대로 쓴다(소급 입력 등).
        if (confirmed == null && logs.isEmpty() && date.isAfter(LocalDate.now(clock))) {
            return ScreenStatus.NOT_YET;
        }

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

    /**
     * 태깅 시각을 화면 시각으로.
     *
     * <p><b>{@code systemDefault()}를 쓰면 안 된다</b> — 운영 서버(EC2/ECS)는 UTC라
     * 등원 09:30이 00:30으로 보인다. 로컬은 KST라 통과하고 CI·운영에서만 깨진다.
     */
    private LocalTime timeOf(AttendanceTaggingLog log) {
        return log.getRecordedAt().atZone(TimeConfig.KST).toLocalTime();
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
    /** 학부모 대표 연락처. 학생 목록과 <b>같은 규칙</b>(승인자 → 관계 순서)으로 고른다. */
    private Map<Long, String> guardianPhonesOf(List<StudentEnrollment> targets) {
        return studentListEnricher.guardianPhones(targets);
    }

    /** 화면(`Attendance.tsx`)이 쓰는 상태값. 우리 {@link DailyStatus}와 축이 다르다. */
    /**
     * @see #NOT_YET 아직 오지 않은 날. <b>결석과 반드시 구분해야 한다</b>
     */
    public enum ScreenStatus {
        ON_TIME, LATE, ABSENT, OUT, EARLY_LEAVE,

        /**
         * 판정 불가 — <b>아직 오지 않은 날</b>이다.
         *
         * <p>이 값이 없을 때 미래 날짜를 조회하면 <b>재원생 전원이 결석으로 내려갔다</b>.
         * "안 온 것"과 "아직 오지 않은 것"은 다르고, 화면이 그걸 구분할 근거가 응답에
         * 없었다. 통계나 배치가 그 값을 집으면 조용히 번진다.
         */
        NOT_YET
    }

    /**
     * @param studyMinutes 순공시간(분). 확정 전이면 조회 시점까지만 센다
     * @param excused      사유 승인 여부. 상태와 <b>직교하는 축</b>이다
     */
    /** @param date 그 행의 일자. 하루 조회에서도 채워진다 — 기간 조회와 모양이 같아야 한다 */
    public record AttendanceRow(
            LocalDate date,
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
            /** 학부모 연락처. 상위 관리자가 아니면 가려져 있다({@code masked}) */
            String guardianPhone,
            boolean unexcusedLate,
            /** 연락처가 가려졌는지 — 화면이 "번호가 잘못 저장됐다"로 오인하지 않게 */
            boolean masked
    ) {

        /** 화면 표기 {@code "N시간 MM분"}. */
        public String studyTimeLabel() {
            return Duration.ofMinutes(studyMinutes).toHours() + "시간 "
                    + String.format("%02d", studyMinutes % 60) + "분";
        }
    }

    /**
     * 화면 컬럼 그대로 엑셀로 내린다.
     *
     * <p><b>연락처는 마스킹이 기본</b>이다(실행가이드 3.2). 파일은 한번 나가면 회수가 안 되고
     * 메신저로 재전달되므로 화면보다 기준을 높게 잡는다 — 화면은 토글로 볼 수 있지만
     * 파일은 상위 관리자가 명시 요청해야 원본이 나간다.
     */
    public byte[] export(AuthPrincipal me, Long academyId, LocalDate date, Long classId,
                         boolean unmask) {
        return export(me, board(me, academyId, date, classId), unmask);
    }

    /**
     * 이미 걸러 놓은 목록을 그대로 내려받는다.
     *
     * <p><b>화면이 본 것과 파일이 같아야 한다.</b> 내보내기가 조회 조건을 못 받으면
     * 화면에서 상태·검색어로 좁혀 놓고 받은 파일에 전체가 담겨 <b>엉뚱하게 넓은 파일</b>이
     * 나간다 — 개인정보가 들어 있는 파일이라 넓은 쪽이 더 위험하다.
     */
    public byte[] export(AuthPrincipal me, List<AttendanceRow> rows, boolean unmask) {
        boolean raw = unmask && com.dlab.common.privacy.PersonalDataPolicy.canViewRaw(me);

        return excelExporter.export("출결현황", EXPORT_MAPPING, rows, r -> java.util.Arrays.asList(
                r.studentNo(),
                raw ? r.name() : com.dlab.common.privacy.Masking.name(r.name()),
                r.className(),
                r.seatCd(),
                r.checkInAt() == null ? null : r.checkInAt().toString(),
                r.checkOutAt() == null ? null : r.checkOutAt().toString(),
                STATUS_LABELS.get(r.status()),
                r.excused() ? "사유 승인" : null,
                r.studyTimeLabel(),
                // 목록에서 이미 가려져 왔으면 그대로 둔다 — 가린 값을 다시 가리면 모양이 깨진다
                raw || com.dlab.common.privacy.Masking.isMasked(r.guardianPhone()) ? r.guardianPhone()
                        : com.dlab.common.privacy.Masking.phone(r.guardianPhone()),
                r.unexcusedLate() ? "무단지각" : null));
    }
}
