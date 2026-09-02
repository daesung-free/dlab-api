package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyRuleEngine;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.attendance.service.AttendanceDecision;
import com.dlab.domain.attendance.service.AttendancePolicy;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 출결 처리 (DSA 3.14 {@code setAttendStd} · 3.22 {@code setReAttendProc}).
 *
 * <p>판정 규칙 자체는 {@link AttendancePolicy}에 있고, 여기서는 <b>조회·중복억제·기록</b>을 맡는다.
 *
 * <p><b>동기 응답이어야 한다.</b> 키오스크는 이 응답의 {@code att_gn}을 화면에 띄우고
 * 학생이 그걸 보고 지나간다 — 받고 202를 던진 뒤 비동기로 처리할 수 없다(CLAUDE.md §3).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KioskAttendanceService {

    private static final DateTimeFormatter TAG_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 규격서 3.14: <i>"1분내 동일한 RFID UID로 요청 시 최초 요청만 처리한다."</i>
     *
     * <p>카드가 리더에 잠깐 두 번 닿는 일이 실제로 잦다.
     */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(1);

    /**
     * 조퇴·외출 선택지를 예정 시각 <b>몇 분 전부터</b> 띄울지.
     *
     * <p>키오스크가 한때 자체 구현했던 값이다 — 주석까지 남아 있다:
     * <i>"예정 시각 30분 전부터 노출 (예: 12:10 외출은 11:40부터)"</i>.
     * 나중에 <i>"DSA setAttendStd 응답 기반으로 통합"</i>하면서 그쪽 필터를 걷어냈으므로,
     * <b>이제 이 판단은 우리 몫이다.</b>
     *
     * <p>요구사항정의서의 정기일정 인정 판정 기준도 30분이라 값이 일치한다.
     */
    private static final Duration PROMPT_LEAD_TIME = Duration.ofMinutes(30);

    private final StudentEnrollmentRepository enrollmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final AbsenceReasonRepository absenceReasonRepository;
    private final PeriodMasterRepository periodMasterRepository;
    private final AttendancePolicy attendancePolicy;
    private final PenaltyRuleEngine penaltyRuleEngine;
    private final com.dlab.domain.attendance.service.StaffAttendanceService staffAttendanceService;
    private final Clock clock;

    /**
     * 출결 태깅.
     *
     * @param conGn 학생이 고른 액션. 비어 있으면 서버가 자동 판별한다.
     *              {@code 113}·{@code 126}·{@code 128}·{@code 129} 응답 뒤
     *              키오스크가 이 값을 실어 <b>재호출</b>한다
     */
    public TagResult tag(Long academyId, String rfidNo, String tagDt, String conGn) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);
        LocalDateTime at = parseTagDt(tagDt);
        LocalDate date = at.toLocalDate();

        // ★ 직원은 여기서 갈라진다. 아래 전부가 학생 전제다 —
        //   교시로 지각·하원을 가르고, 확정 배치가 결석을 만들고, 상벌점 규칙이 붙는다.
        //   직원에겐 교시가 없고 결석·벌점도 없으므로 근태로만 남긴다
        if (enrollment.getGrade().isStaff()) {
            return TagResult.accepted(enrollment,
                    staffAttendanceService.record(enrollment, at).toDsaEvent());
        }

        List<AttendanceTaggingLog> todayLogs = todayLogs(enrollment.getId(), date);
        boolean explicit = conGn != null && !conGn.isBlank();

        // ── 중복 억제 ──
        // 창 안이면 원장에 남기지 않고 직전 결과를 그대로 되돌려준다.
        // 에러를 주면 학생이 "인식이 안 됐나" 하고 계속 태깅한다.
        //
        // ★ 학생이 화면에서 고른 요청(con_gn 있음)에는 적용하지 않는다.
        //   카드 이중 접촉으로는 con_gn이 붙을 수 없으므로 실수가 아니다.
        //   적용했다가 로컬 E2E에서 실제로 사고가 났다 — 2-phase 2차 호출이 창에 걸려
        //   삼켜지면서 키오스크 DB에는 외출이 남고 우리 DB에는 안 남았다.
        if (!explicit) {
            AttendanceTaggingLog recent = withinDedupWindow(todayLogs, at);
            if (recent != null) {
                return TagResult.accepted(enrollment, recent.getEventType());
            }
        }

        List<AttendanceEventType> history = todayLogs.stream()
                .map(AttendanceTaggingLog::getEventType)
                .toList();

        AttendanceDecision decision = explicit
                ? explicitDecide(enrollment, history, conGn, at)
                : autoDecide(enrollment, history, at, date);

        if (!decision.isAccepted()) {
            return TagResult.rejected(enrollment, decision.code(), decision.message());
        }

        // ★ 원장은 decision.event(), 응답은 decision.reportedAs()다.
        //   보통 같지만 사유지각은 갈린다 — 원장엔 지각, 화면엔 등원
        taggingLogRepository.save(new AttendanceTaggingLog(
                enrollment.getAcademy(), enrollment, decision.event(),
                AttendanceSource.KIOSK_NFC, at.atZone(clock.getZone()).toInstant(), date));

        // ★ 자동 상벌점은 원장에 남은 이벤트 기준이다(decision.event()) — 화면 표시값이
        //   아니다. 사유지각은 화면엔 등원으로 뜨지만 원장은 지각이고, 벌점은 원장을 따른다.
        //   엔진이 REQUIRES_NEW라 부여가 실패해도 태깅은 남는다 —
        //   롤백되면 학생이 등원한 사실 자체가 사라진다
        penaltyRuleEngine.apply(enrollment, PenaltyTriggerType.ATTENDANCE,
                decision.event().getCode(), date);

        return TagResult.accepted(enrollment, decision.reportedAs());
    }

    /** 서버 자동 판별. */
    private AttendanceDecision autoDecide(StudentEnrollment enrollment,
                                          List<AttendanceEventType> history,
                                          LocalDateTime at, LocalDate date) {
        // history는 아래 excusedOptions에도 넘어간다 — 오늘 이미 쓴 신청을 걸러야 한다
        List<PeriodMaster> periods = periodMasterRepository.findByDayType(
                enrollment.getAcademy().getId(), enrollment.getYear(), DayType.of(date));

        return attendancePolicy.decide(
                history,
                periods,
                at.toLocalTime(),
                enrollment.getAcademy().getAttendanceDeadline(),
                excusedOptions(enrollment.getId(), at, history));
    }

    /**
     * 학생이 명시한 액션 처리 (2-phase의 2차 호출).
     *
     * <p><b>사유조퇴({@code C})·사유외출({@code N})은 승인된 사유신청을 확인한다.</b>
     * 확인 없이 받으면 키오스크에서 아무나 눌러 사유조퇴를 만들 수 있다 —
     * 그러면 결석·조퇴 집계와 벌점이 통째로 무의미해진다.
     *
     * <p>일반 외출({@code D})은 승인이 필요 없다. 잠깐 나갔다 오는 것까지 신청을 요구하면
     * 아무도 안 찍고 그냥 나간다.
     */
    private AttendanceDecision explicitDecide(StudentEnrollment enrollment,
                                              List<AttendanceEventType> history,
                                              String conGn, LocalDateTime at) {
        AttendanceEventType chosen;
        try {
            chosen = AttendanceEventType.fromCode(conGn.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }

        // 조퇴한 학생은 어떤 선택으로도 다시 못 찍는다
        if (!history.isEmpty()
                && history.get(history.size() - 1) == AttendanceEventType.EARLY_LEAVE) {
            return AttendanceDecision.reject(DsaCode.ALREADY_LEFT_EARLY);
        }

        AttendancePolicy.ExcusedOptions excused = excusedOptions(enrollment.getId(), at, history);
        if (chosen == AttendanceEventType.EARLY_LEAVE && !excused.earlyLeave()) {
            return AttendanceDecision.reject(DsaCode.NO_APPROVAL, "승인된 조퇴 신청이 없습니다.");
        }
        if (chosen == AttendanceEventType.EXCUSED_OUTING && !excused.outing()) {
            return AttendanceDecision.reject(DsaCode.NO_APPROVAL, "승인된 외출 신청이 없습니다.");
        }

        return AttendanceDecision.of(chosen);
    }

    /**
     * 3.22 {@code setReAttendProc} — 조퇴 해제 후 재등원.
     *
     * <p>규격서: <i>"조퇴 후 재등원 시, 하원 → 외출 변경 + 복귀시간 입력"</i>.
     * 조퇴 기록을 지우지 않고 <b>외출로 정정한 뒤 복귀를 덧붙인다</b> —
     * 지우면 "조퇴했다가 돌아왔다"는 사실 자체가 사라져 정정 이력을 추적할 수 없다.
     *
     * <p>키오스크는 {@code rfid_no}만 보낸다(복귀 시각 파라미터는 규격서 표와 샘플의
     * 이름조차 어긋나 있고 실제로 안 쓴다). 복귀 시각은 서버 현재 시각이다.
     */
    public StudentEnrollment reAttend(Long academyId, String rfidNo) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);
        LocalDate date = LocalDate.now(clock);

        List<AttendanceTaggingLog> todayLogs = todayLogs(enrollment.getId(), date);
        AttendanceTaggingLog last = todayLogs.isEmpty() ? null : todayLogs.get(todayLogs.size() - 1);

        if (last == null || last.getEventType() != AttendanceEventType.EARLY_LEAVE) {
            // 조퇴 상태가 아닌데 해제를 요청했다. 되돌릴 게 없다
            throw new DsaApiException(DsaCode.INVALID_KEY, "조퇴 상태가 아닙니다.");
        }

        last.correctEventType(AttendanceEventType.OUTING);
        taggingLogRepository.save(new AttendanceTaggingLog(
                enrollment.getAcademy(), enrollment, AttendanceEventType.RETURN,
                AttendanceSource.KIOSK_NFC, Instant.now(clock), date));

        return enrollment;
    }

    /**
     * 그 시점에 <b>쓸 수 있는</b> 승인된 사유신청.
     *
     * <p>★ <b>예정 시각 30분 전부터만 노출한다</b> — 승인만 있으면 하루 종일 뜨는 게 아니다.
     * 16:30 조퇴를 승인받은 학생이 09시에 화장실 가려고 찍었을 때 "조퇴 하시겠습니까?"가
     * 뜨면, 실수로 눌러 <b>09시 조퇴가 기록된다.</b>
     *
     * <p>상한은 두지 않는다. 예정보다 늦게 나가는 건 정상이다(병원 예약이 밀리는 등).
     *
     * <p>★ <b>오늘 이미 쓴 신청은 다시 안 띄운다.</b> 신청 1건으로 하루에 여러 번 나가면
     * 승인 절차가 무의미해진다. 키오스크도 같은 규칙을 갖고 있었다
     * ({@code hadOutingToday}·{@code hadEarlyLeaveToday}, `ef2a5ce`).
     *
     * <p>⚠️ 조퇴 해제({@code setReAttendProc})는 조퇴 기록을 <b>외출로 정정</b>하므로,
     * 재등원한 학생은 그날 외출을 쓴 것으로 집계된다. DSA 규격서가 그 변환을 정의하고 있어
     * ("조퇴 후 재등원 시 하원 → 외출 변경") 원본 동작과 같다.
     *
     * <p>{@code start_time}이 없는 건은 통과시킨다 — 결석·지각처럼 종일 사유이거나
     * 이관된 과거 데이터다. 시각을 모른다고 막으면 정상 신청이 거부된다.
     */
    private AttendancePolicy.ExcusedOptions excusedOptions(Long enrollmentId, LocalDateTime at,
                                                          List<AttendanceEventType> history) {
        List<AbsenceReason> reasons = absenceReasonRepository
                .findByEnrollmentIdAndAttendanceDate(enrollmentId, at.toLocalDate());

        // 오늘 이미 썼으면 다시 안 띄운다 — 신청 1건으로 하루에 여러 번 나갈 수 없다
        boolean usedEarlyLeave = history.contains(AttendanceEventType.EARLY_LEAVE);
        boolean usedOuting = history.contains(AttendanceEventType.OUTING)
                || history.contains(AttendanceEventType.EXCUSED_OUTING);

        boolean earlyLeave = false;
        boolean outing = false;
        boolean late = false;
        for (AbsenceReason r : reasons) {
            if (!isApproved(r)) {
                continue;
            }
            // ★ 지각은 30분 창을 타지 않는다. 조퇴·외출은 "예정 시각에 나가는가"라
            //   시각 대조가 의미 있지만, 지각은 이미 늦게 온 사실이라 대조할 예정이 없다
            if (r.getReasonType() == AbsenceReasonType.LATE) {
                late = true;
                continue;
            }
            if (!isUsableAt(r, at.toLocalTime())) {
                continue;
            }
            if (r.getReasonType() == AbsenceReasonType.EARLY_LEAVE && !usedEarlyLeave) {
                earlyLeave = true;
            } else if (r.getReasonType() == AbsenceReasonType.OUTING && !usedOuting) {
                outing = true;
            }
        }
        return new AttendancePolicy.ExcusedOptions(earlyLeave, outing, late);
    }

    /** 예정 시각 {@code -30분} 이후인가. */
    private boolean isUsableAt(AbsenceReason reason, LocalTime now) {
        LocalTime startTime = reason.getStartTime();
        if (startTime == null) {
            return true;
        }
        return !now.isBefore(startTime.minus(PROMPT_LEAD_TIME));
    }

    /**
     * 승인 여부.
     *
     * <p><b>승인 요청이 없는 건은 승인으로 보지 않는다.</b> 관리자가 직접 등록한 사유는
     * 자동승인(AUTO 라우팅)으로 승인 요청이 붙으므로, 요청이 없다는 건 아직 라우팅 전이라는 뜻이다.
     */
    private boolean isApproved(AbsenceReason r) {
        return r.getApprovalRequest() != null
                && r.getApprovalRequest().getStatus() == ApprovalStatus.APPROVED;
    }

    private List<AttendanceTaggingLog> todayLogs(Long enrollmentId, LocalDate date) {
        return taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(enrollmentId, date)
                .stream()
                .sorted(Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .toList();
    }

    /**
     * 중복 억제 대상인지.
     *
     * <p><b>★ 외출 중에는 창을 적용하지 않는다.</b> 로컬 E2E에서 실제로 터진 지점이다 —
     * 외출을 찍고 25초 만에 돌아온 학생에게 직전 결과({@code D})를 그대로 되돌려주자,
     * 키오스크가 <b>복귀({@code R})를 기대하고 있다가</b> "DSA-우리 DB 상태 불일치"
     * 이상 로그를 남기고 학생에게 에러를 띄웠다({@code RETURN_CONTEXT_NOT_R}).
     *
     * <p>키오스크는 외출 중 태깅을 <b>반드시 복귀</b>로 간주한다(그쪽 {@code TagService} 1번 분기).
     * 화장실처럼 짧은 외출이 정상이라 이 경로는 창으로 막으면 안 된다.
     *
     * <p>이중 접촉 방어는 그대로 유지된다 — 등원·지각·하원 직후 재태깅이 원래 대상이고,
     * 키오스크 자체 60초 가드({@code guardRetagInterval})가 그 위를 한 겹 더 덮는다.
     */
    private AttendanceTaggingLog withinDedupWindow(List<AttendanceTaggingLog> logs,
                                                   LocalDateTime at) {
        if (logs.isEmpty()) {
            return null;
        }
        AttendanceTaggingLog last = logs.get(logs.size() - 1);

        if (last.getEventType() == AttendanceEventType.OUTING
                || last.getEventType() == AttendanceEventType.EXCUSED_OUTING) {
            return null;
        }

        Instant now = at.atZone(clock.getZone()).toInstant();
        Duration gap = Duration.between(last.getRecordedAt(), now);

        // 음수(과거 시각 태깅)도 창 안으로 본다 — 키오스크 시계가 살짝 뒤처진 경우다
        return gap.abs().compareTo(DEDUP_WINDOW) < 0 ? last : null;
    }

    /** 형식이 깨진 {@code tag_dt}는 파라미터 오류다. 서버 시각으로 덮으면 태깅 시각이 왜곡된다. */
    private LocalDateTime parseTagDt(String tagDt) {
        if (tagDt == null || tagDt.isBlank()) {
            return LocalDateTime.now(clock);
        }
        try {
            return LocalDateTime.parse(tagDt.trim(), TAG_DT);
        } catch (DateTimeParseException e) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
    }

    private StudentEnrollment requireEnrollment(Long academyId, String rfidNo) {
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .filter(e -> e.getAcademy().getId().equals(academyId))
                .orElseThrow(() -> new DsaApiException(DsaCode.INVALID_KEY, "등록되지 않은 카드입니다."));
    }

    /**
     * 태깅 결과.
     *
     * @param attGn 처리된 경우의 {@code att_gn}. 분기 코드일 때는 {@code null}
     */
    public record TagResult(
            String hakNo,
            String stdNm,
            String attGn,
            DsaCode code,
            String message
    ) {

        static TagResult accepted(StudentEnrollment e, AttendanceEventType event) {
            return new TagResult(e.getStudentNo(), e.getStudent().getName(),
                    event.getCode(), null, null);
        }

        static TagResult rejected(StudentEnrollment e, DsaCode code, String message) {
            return new TagResult(e.getStudentNo(), e.getStudent().getName(),
                    null, code, message);
        }

        public boolean isAccepted() {
            return attGn != null;
        }
    }

    /** 현재 시각 기준 오늘 날짜. 배치·테스트가 같은 {@code Clock}을 보게 한다. */
    public LocalTime nowTime() {
        return LocalTime.now(clock);
    }
}
