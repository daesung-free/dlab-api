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
 *   <li>새벽(하루 시작 전) → {@code 122}</li>
 *   <li>오늘 첫 태깅 → 등원({@code S}) 또는 지각({@code A}).
 *       <b>상한이 없다</b> — 밤늦게 와도 그날 등원이다(아래 참고).
 *       <b>교시 확인보다 앞</b>이라 자율등원일에도 등원이 찍힌다</li>
 *   <li>등원 상태인데 그날 교시가 없음 → {@code 113}. 하원·외출을 못 가려 되묻는다</li>
 *   <li>승인된 사유신청 있음 → {@code 126}/{@code 128}/{@code 129} 선택지</li>
 *   <li>마지막 교시 종료 후 → 하원({@code T})</li>
 *   <li>그 외 → {@code 130} <b>"승인 내역이 없습니다"</b></li>
 * </ol>
 *
 * <h2>★ 첫 태깅에 상한이 없다</h2>
 * <p>한때 "마지막 교시 종료 후 첫 태깅"을 {@code 122}로 막았으나, 학원 운영상
 * <b>밤늦게 온 학생도 그날 등원으로 인정</b>해야 한다(동탄 21:50 이후 사례).
 * 자정을 넘기면 {@code attendance_date}가 다음 날로 바뀌므로 <b>자정이 자연 경계</b>이고,
 * 별도 마감 시각을 둘 필요가 없다.
 *
 * <p>하한은 {@link #DAY_START}(새벽)만 남는다. 한때 "첫 교시 시작 전"으로 잡았으나
 * <b>학원 문은 첫 교시보다 일찍 열어서</b> 일찍 온 학생이 거부됐다.
 *
 * <p>상한은 원래도 첫 태깅에만 걸려 있었다. 전체에 걸면 <b>하원 태깅이
 * 영원히 불가능해지기</b> 때문이다 — 하원은 마지막 교시가 끝난 뒤에 찍는다.
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
     * 하루의 시작. 이 시각 전 태깅은 {@code 122}로 거부한다.
     *
     * <p><b>막으려는 건 하나다</b> — 자정을 넘겨 남아 있던 학생이 새벽에 찍었을 때
     * 그게 <b>새 날의 등원</b>으로 기록되는 것. 실제로는 어제를 끝내는 태깅인데
     * {@code attendance_date}가 이미 다음 날이라 "오늘 첫 태깅 = 등원"이 돼버린다.
     * 그대로 두면 순공시간이 새벽부터 계산돼 랭킹이 어긋난다.
     *
     * <p><b>첫 교시 시작을 경계로 쓰지 않는 이유</b>: 학원 문은 첫 교시보다 일찍 연다.
     * 첫 교시가 08:00인데 07:30에 온 학생이 거부되면, 늦게 온 학생을 막던 것과
     * 대칭인 문제가 생긴다.
     *
     * <p>지점별 설정으로 두지 않는다 — 어느 지점이든 새벽 3시 태깅은 정상이 아니고
     * 아침 6시 태깅은 정상이다. 지점마다 다를 이유가 없다.
     */
    private static final LocalTime DAY_START = LocalTime.of(5, 0);

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

        // 3. 하루가 시작되기 전(새벽)
        if (at.isBefore(DAY_START)) {
            return AttendanceDecision.reject(DsaCode.OUTSIDE_STUDY_HOURS);
        }

        // 4. 오늘 첫 태깅 — ★ 교시 확인보다 먼저다.
        //    등원 기록이 없으면 등원인 게 명백해서 시간표를 볼 필요가 없다.
        //    뒤에 두면 자율등원일(주말)에 온 학생이 113을 받는데,
        //    키오스크가 띄우는 선택지는 하원·외출 둘뿐이라 등원할 방법이 사라진다
        if (last == null) {
            return firstTag(at, lateAfter, excused, periods.isEmpty());
        }

        // 5. 등원 상태인데 그날 교시가 없다(자율등원일) → 하원인지 외출인지 못 가린다
        if (periods.isEmpty()) {
            return AttendanceDecision.reject(DsaCode.NO_TIMETABLE);
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

        // 8. 승인 없이 나가려 한다 — 거절한다.
        //    등원 상태에서 재태깅은 조퇴 아니면 외출인데 둘 다 사전 승인이 필요하다.
        return AttendanceDecision.reject(DsaCode.NO_APPROVAL,
                "사전조퇴/사전외출 승인 내역이 없습니다. 데스크로 문의해주세요.");
    }

    /**
     * 오늘 첫 태깅.
     *
     * <h2>★ 늦게 와도 등원이다 — 상한을 두지 않는다</h2>
     * 한때 "마지막 교시 종료 후 첫 태깅"을 {@code 122}로 막았으나, 학원 운영상
     * <b>밤늦게 온 학생도 그날 등원으로 인정</b>해야 한다(동탄 21:50 이후 사례).
     * 자정을 넘기면 {@code attendance_date}가 다음 날로 바뀌어 <b>자정이 자연 경계</b>가
     * 되므로 별도 마감 시각을 둘 필요가 없다.
     *
     * <p>잃는 것: 밤늦게 카드만 찍고 가는 출석 인정을 막을 수 없다. 학원이 이를 감수한다.
     *
     * <h2>사유지각은 등원으로 응답한다</h2>
     * <b>원장에는 지각({@code A})으로 남기고 키오스크에만 등원({@code S})으로 내린다</b> —
     * 사유가 있어도 늦게 온 건 사실이라 지각 이력을 지우면 안 되고, 화면에는 학원 요청대로
     * 등원으로 떠야 한다. 무단/사유 구분은 일자 확정 배치가 {@code excused} 플래그로 이미 한다.
     */
    private AttendanceDecision firstTag(LocalTime at, LocalTime lateAfter,
                                        ExcusedOptions excused, boolean freeDay) {
        // ★ 자율등원일에는 지각이 없다. 교시가 없다는 건 운영일이 아니라는 뜻이고,
        //   평일 기준 등원시각을 그대로 적용하면 주말에 11시에 온 학생이 지각으로 남는다.
        //   결석 확정 배치도 교시 없는 날을 건너뛰므로(자율등원일엔 결석이 없다) 규칙이 일관된다
        if (freeDay || !at.isAfter(lateAfter)) {
            return AttendanceDecision.of(AttendanceEventType.CHECK_IN);
        }
        // 지각 시각이다. 승인된 사유지각이 있으면 화면에만 등원으로 보인다
        return excused.late()
                ? AttendanceDecision.of(AttendanceEventType.LATE, AttendanceEventType.CHECK_IN)
                : AttendanceDecision.of(AttendanceEventType.LATE);
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
     *
     * <p><b>{@code late}는 선택지가 아니다.</b> 조퇴·외출은 "지금 나가는가"를 학생에게
     * 되묻지만, 지각은 이미 늦게 온 사실이라 물어볼 것이 없다 — 첫 태깅 판정에서만 쓰인다.
     */
    public record ExcusedOptions(boolean earlyLeave, boolean outing, boolean late) {

        public ExcusedOptions(boolean earlyLeave, boolean outing) {
            this(earlyLeave, outing, false);
        }

        public static ExcusedOptions none() {
            return new ExcusedOptions(false, false, false);
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
