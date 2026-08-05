package com.dlab.domain.attendance.service;

import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.period.entity.PeriodMaster;
import java.time.LocalTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 출결 태깅 판정 규칙 (DSA 3.14 {@code setAttendStd}).
 *
 * <p><b>이 클래스가 이 프로젝트 출결의 심장이다.</b> 키오스크는 카드만 읽고,
 * "등원인지 하원인지 외출인지"는 전부 여기서 결정된다.
 *
 * <h2>판정 순서</h2>
 * <ol>
 *   <li>조퇴 후 재태깅 → {@code 121}. 시간표와 무관하게 먼저 막는다</li>
 *   <li>외출 중 → 복귀({@code R}). 복귀는 시간표 밖에서도 가능해야 한다 —
 *       못 찍으면 학생이 나간 채로 기록이 남는다</li>
 *   <li>그날 교시가 없음 → {@code 113}. 주말·공휴일이라 자동판별이 불가하다</li>
 *   <li>문 열기 전 → {@code 122}</li>
 *   <li>오늘 첫 태깅 → 등원({@code S}) 또는 지각({@code A}).
 *       단 <b>운영 종료 후 첫 태깅</b>은 {@code 122}다 — 하루가 이미 끝났다</li>
 *   <li>승인된 사유신청 있음 → {@code 126}/{@code 128}/{@code 129} 선택지</li>
 *   <li>마지막 교시 종료 후 → 하원({@code T})</li>
 *   <li>그 외 → {@code 113}. <b>서버가 임의로 정하지 않고 학생에게 묻는다</b></li>
 * </ol>
 *
 * <h2>운영 종료 시각이 하원을 막으면 안 된다</h2>
 * <p>하원은 <b>마지막 교시가 끝난 뒤에</b> 찍는다. 그래서 종료 시각을 일괄로
 * "운영시간 밖"으로 잘라내면 <b>하원 태깅이 영원히 불가능해진다</b> —
 * 종료 전엔 애매해서 되묻고(8번), 종료 후엔 거부되기 때문이다.
 * 상한 판정을 <b>그날 첫 태깅에만</b> 적용하는 이유다.
 *
 * <h2>애매하면 되묻는다</h2>
 * <p>수업 중간에 찍은 재태깅은 하원인지 외출인지 알 수 없다.
 * 서버가 찍으면 <b>틀렸을 때 학생 출결이 조용히 망가지고</b> 나중에 정정 요청이 들어온다.
 * {@code 113}으로 되물으면 학생이 직접 고르므로 틀릴 일이 없다 — 화면 한 번 더 누르는
 * 비용이 훨씬 싸다.
 */
@Component
public class AttendancePolicy {

    /**
     * 자동 판별.
     *
     * @param today       그날 태깅 이력(시간순). 비어 있으면 첫 태깅이다
     * @param periods     그날 요일 구분의 교시 전체(시작시각 순). 비어 있으면 시간표가 없다
     * @param at          태깅 시각
     * @param lateAfter   지각 판정 기준 시각(지점 공통 {@code attendance_deadline})
     * @param excused     그날 승인된 사유신청 종류
     */
    public AttendanceDecision decide(List<AttendanceEventType> today,
                                     List<PeriodMaster> periods,
                                     LocalTime at,
                                     LocalTime lateAfter,
                                     ExcusedOptions excused) {

        AttendanceEventType last = today.isEmpty() ? null : today.get(today.size() - 1);

        // 1. 조퇴한 학생의 재태깅. setReAttendProc로 풀기 전에는 막는다
        if (last == AttendanceEventType.EARLY_LEAVE) {
            return AttendanceDecision.reject(DsaCode.ALREADY_LEFT_EARLY);
        }

        // 2. 외출 중이면 복귀. 시간표·운영시간 판정보다 먼저다 —
        //    늦게 돌아왔다고 복귀를 거부하면 학생이 나간 상태로 남는다
        if (last == AttendanceEventType.OUTING || last == AttendanceEventType.EXCUSED_OUTING) {
            return AttendanceDecision.of(AttendanceEventType.RETURN);
        }

        // 3. 그날 교시가 아예 없다(일요일·공휴일) → 자동판별 불가
        if (periods.isEmpty()) {
            return AttendanceDecision.reject(DsaCode.NO_TIMETABLE);
        }

        LocalTime open = periods.get(0).getStartTime();
        LocalTime close = periods.get(periods.size() - 1).getEndTime();

        // 4. 문 열기 전
        if (at.isBefore(open)) {
            return AttendanceDecision.reject(DsaCode.OUTSIDE_STUDY_HOURS);
        }

        // 5. 오늘 첫 태깅
        if (last == null) {
            //    ★ 종료 후 첫 태깅은 등원이 아니다. 하루가 이미 끝났다
            if (!at.isBefore(close)) {
                return AttendanceDecision.reject(DsaCode.OUTSIDE_STUDY_HOURS);
            }
            return AttendanceDecision.of(at.isAfter(lateAfter)
                    ? AttendanceEventType.LATE
                    : AttendanceEventType.CHECK_IN);
        }

        // 6. 승인된 사유신청이 있으면 선택지를 띄운다.
        //    자동으로 조퇴 처리해버리면 학생이 의도하지 않은 조퇴가 기록된다
        DsaCode prompt = excused.promptCode();
        if (prompt != null) {
            return AttendanceDecision.reject(prompt);
        }

        // 7. 마지막 교시가 끝났으면 하원이 확실하다
        if (!at.isBefore(lastClassEnd(periods))) {
            return AttendanceDecision.of(AttendanceEventType.CHECK_OUT);
        }

        // 8. 수업 중간 재태깅 — 하원인지 외출인지 서버가 정하지 않는다
        return AttendanceDecision.reject(DsaCode.NO_TIMETABLE);
    }

    /**
     * 하원으로 볼 수 있는 경계.
     *
     * <p>마지막 <b>교시</b>의 종료 시각이다. 운영 종료({@code close})와 같은 값이지만
     * 의미가 달라 따로 둔다 — 나중에 "야자 이후 정리시간" 같은 교시가 붙으면
     * 하원 경계는 그 앞이어야 한다.
     */
    private LocalTime lastClassEnd(List<PeriodMaster> periods) {
        return periods.get(periods.size() - 1).getEndTime();
    }

    /**
     * 그날 승인된 사유신청 조합 → 선택지 코드.
     *
     * <p>{@code 126} 조퇴+사유외출 / {@code 128} 조퇴만 / {@code 129} 사유외출만.
     */
    public record ExcusedOptions(boolean earlyLeave, boolean outing) {

        public static ExcusedOptions none() {
            return new ExcusedOptions(false, false);
        }

        public DsaCode promptCode() {
            if (earlyLeave && outing) {
                return DsaCode.CHOICE_EARLY_LEAVE_OR_EXCUSED_OUTING;
            }
            if (earlyLeave) {
                return DsaCode.CHOICE_EARLY_LEAVE;
            }
            return outing ? DsaCode.CHOICE_EXCUSED_OUTING : null;
        }
    }
}
