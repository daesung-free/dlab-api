package com.dlab.domain.attendance.service;

import static com.dlab.domain.attendance.entity.AttendanceEventType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.service.AttendancePolicy.ExcusedOptions;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 출결 판정 규칙 (DSA 3.14).
 *
 * <p>DB 없이 도는 순수 단위 테스트다 — 이 규칙이 출결의 전부라 빠르게 많이 돌려야 한다.
 */
class AttendancePolicyTest {

    private static final LocalTime LATE_AFTER = LocalTime.of(9, 0);

    private final AttendancePolicy policy = new AttendancePolicy();

    /** 평일 교시: 08:00~09:00, 09:00~12:00, 13:00~22:00. */
    private List<PeriodMaster> weekday() {
        return List.of(
                period((short) 0, LocalTime.of(8, 0), LocalTime.of(9, 0)),
                period((short) 1, LocalTime.of(9, 0), LocalTime.of(12, 0)),
                period((short) 2, LocalTime.of(13, 0), LocalTime.of(22, 0)));
    }

    private PeriodMaster period(short no, LocalTime start, LocalTime end) {
        return new PeriodMaster(null, (short) 2026, no, no + "교시",
                DayType.WEEKDAY, PeriodType.SELF_STUDY, start, end);
    }

    private AttendanceDecision decide(List<AttendanceEventType> today, LocalTime at) {
        return policy.decide(today, weekday(), at, LATE_AFTER, ExcusedOptions.none());
    }

    @Test
    @DisplayName("첫 태깅 — 기준 시각 전이면 등원, 후면 지각")
    void firstTagIsCheckInOrLate() {
        assertThat(decide(List.of(), LocalTime.of(8, 30)).event()).isEqualTo(CHECK_IN);
        assertThat(decide(List.of(), LocalTime.of(9, 30)).event()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("★ 외출 중이면 복귀 — 운영시간이 끝났어도 복귀는 받는다")
    void returnIsAlwaysAcceptedWhileOut() {
        assertThat(decide(List.of(CHECK_IN, OUTING), LocalTime.of(14, 0)).event())
                .isEqualTo(RETURN);
        assertThat(decide(List.of(CHECK_IN, EXCUSED_OUTING), LocalTime.of(14, 0)).event())
                .isEqualTo(RETURN);

        // 늦게 돌아왔다고 거부하면 학생이 나간 상태로 기록이 남는다
        assertThat(decide(List.of(CHECK_IN, OUTING), LocalTime.of(23, 30)).event())
                .isEqualTo(RETURN);
    }

    @Test
    @DisplayName("★ 조퇴 후 재태깅은 121 — setReAttendProc로 풀기 전엔 막힌다")
    void taggingAfterEarlyLeaveIsBlocked() {
        assertThat(decide(List.of(CHECK_IN, EARLY_LEAVE), LocalTime.of(15, 0)).code())
                .isEqualTo(DsaCode.ALREADY_LEFT_EARLY);
    }

    @Test
    @DisplayName("★ 등원 상태인데 교시가 없으면 113 — 하원인지 외출인지 못 가린다")
    void noTimetableAsksUser() {
        var decision = policy.decide(List.of(CHECK_IN), List.of(), LocalTime.of(15, 0),
                LATE_AFTER, ExcusedOptions.none());

        assertThat(decision.code()).isEqualTo(DsaCode.NO_TIMETABLE);
        assertThat(decision.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("★★ 자율등원일(주말) 첫 태깅은 등원이다 — 113을 주면 등원할 방법이 없다")
    void firstTagOnFreeDayIsCheckIn() {
        // 키오스크가 113에 띄우는 선택지는 하원·외출 둘뿐이다
        var decision = policy.decide(List.of(), List.of(), LocalTime.of(10, 0),
                LATE_AFTER, ExcusedOptions.none());

        assertThat(decision.event()).isEqualTo(CHECK_IN);
    }

    @Test
    @DisplayName("★★ 자율등원일에는 지각이 없다 — 주말은 학생이 알아서 온다")
    void noLatenessOnFreeDay() {
        // 평일이면 지각인 시각(11시)인데 교시가 없으면 그냥 등원이다
        var decision = policy.decide(List.of(), List.of(), LocalTime.of(11, 0),
                LATE_AFTER, ExcusedOptions.none());

        assertThat(decision.event()).isEqualTo(CHECK_IN);
    }

    @Test
    @DisplayName("★ 새벽 태깅은 122 — 자정 넘겨 남아 있던 학생이 새 날 등원으로 찍히면 안 된다")
    void beforeDayStartIsRejected() {
        assertThat(decide(List.of(), LocalTime.of(1, 0)).code())
                .isEqualTo(DsaCode.OUTSIDE_STUDY_HOURS);
        assertThat(decide(List.of(), LocalTime.of(4, 59)).code())
                .isEqualTo(DsaCode.OUTSIDE_STUDY_HOURS);
    }

    @Test
    @DisplayName("★★ 첫 교시 전에 일찍 와도 등원이다 — 학원 문은 첫 교시보다 일찍 연다")
    void earlyArrivalBeforeFirstPeriodIsCheckIn() {
        // 첫 교시는 08:00인데 07:00 도착. 예전엔 122로 거부했다
        assertThat(decide(List.of(), LocalTime.of(7, 0)).event()).isEqualTo(CHECK_IN);
        assertThat(decide(List.of(), LocalTime.of(5, 0)).event()).isEqualTo(CHECK_IN);
    }

    @Test
    @DisplayName("★★ 밤늦게 온 첫 태깅도 등원이다 — 마지막 교시가 끝났어도 지각으로 받는다")
    void firstTagAfterClosingIsStillLate() {
        // 동탄 사례: 21:50(마지막 교시 종료) 이후 첫 태깅.
        // 예전엔 122로 거부했는데 학원은 그날 등원으로 인정하길 원한다
        assertThat(decide(List.of(), LocalTime.of(22, 30)).event()).isEqualTo(LATE);
        assertThat(decide(List.of(), LocalTime.of(23, 50)).event()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("★ 마지막 교시 종료가 하원과 외출을 가른다")
    void afterLastPeriodIsCheckOut() {
        // 종료 전이면 조퇴·외출이라 승인이 필요하다
        assertThat(decide(List.of(CHECK_IN), LocalTime.of(21, 59)).code())
                .isEqualTo(DsaCode.NO_APPROVAL);

        assertThat(decide(List.of(CHECK_IN), LocalTime.of(22, 0)).event()).isEqualTo(CHECK_OUT);
        assertThat(decide(List.of(CHECK_IN), LocalTime.of(22, 30)).event()).isEqualTo(CHECK_OUT);
    }

    @Test
    @DisplayName("★ 운영 종료 시각이 하원을 막으면 안 된다 — 그러면 하원 태깅이 영영 불가능해진다")
    void closingTimeMustNotBlockCheckOut() {
        // 등원한 학생: 종료 후에도 하원이 찍혀야 한다
        assertThat(decide(List.of(CHECK_IN), LocalTime.of(22, 10)).isAccepted()).isTrue();

        // 등원한 적 없는 학생의 같은 시각 태깅은 하원이 아니라 지각이다 —
        // 하루가 시작도 안 됐는데 끝낼 수 없다
        assertThat(decide(List.of(), LocalTime.of(22, 10)).event()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("★★ 사유지각은 원장엔 지각, 키오스크엔 등원 — 지각 이력을 지우면 안 된다")
    void excusedLateIsRecordedAsLateButReportedAsCheckIn() {
        AttendanceDecision decision = policy.decide(
                List.of(), weekday(), LocalTime.of(22, 30), LATE_AFTER,
                new ExcusedOptions(false, false, true));

        assertThat(decision.event()).isEqualTo(LATE);          // 원장
        assertThat(decision.reportedAs()).isEqualTo(CHECK_IN); // 키오스크 화면
    }

    @Test
    @DisplayName("사유지각 신청이 없으면 그냥 지각 — 응답도 지각이다")
    void unexcusedLateIsReportedAsLate() {
        AttendanceDecision decision = decide(List.of(), LocalTime.of(22, 30));

        assertThat(decision.event()).isEqualTo(LATE);
        assertThat(decision.reportedAs()).isEqualTo(LATE);
    }

    @Test
    @DisplayName("★ 사유지각이 있어도 정시 등원이면 그냥 등원이다")
    void onTimeArrivalIsNotAffectedByExcusedLate() {
        AttendanceDecision decision = policy.decide(
                List.of(), weekday(), LocalTime.of(8, 30), LATE_AFTER,
                new ExcusedOptions(false, false, true));

        assertThat(decision.event()).isEqualTo(CHECK_IN);
        assertThat(decision.reportedAs()).isEqualTo(CHECK_IN);
    }

    @Test
    @DisplayName("★ 승인 없이 나가려 하면 130 — 자동 외출 처리하면 승인 절차가 무의미해진다")
    void leavingWithoutApprovalIsRejected() {
        var decision = decide(List.of(CHECK_IN), LocalTime.of(14, 0));

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.code()).isEqualTo(DsaCode.NO_APPROVAL);
        // 이 문구가 그대로 학생 화면에 뜬다
        assertThat(decision.message()).contains("승인 내역이 없습니다");
    }

    @Test
    @DisplayName("★ 승인된 사유신청 조합에 따라 126/128/129")
    void approvedExcusesBecomePrompts() {
        assertThat(policy.decide(List.of(CHECK_IN), weekday(), LocalTime.of(14, 0),
                LATE_AFTER, new ExcusedOptions(true, true)).code())
                .isEqualTo(DsaCode.CHOICE_EARLY_LEAVE_OR_EXCUSED_OUTING);

        assertThat(policy.decide(List.of(CHECK_IN), weekday(), LocalTime.of(14, 0),
                LATE_AFTER, new ExcusedOptions(true, false)).code())
                .isEqualTo(DsaCode.CHOICE_EARLY_LEAVE);

        assertThat(policy.decide(List.of(CHECK_IN), weekday(), LocalTime.of(14, 0),
                LATE_AFTER, new ExcusedOptions(false, true)).code())
                .isEqualTo(DsaCode.CHOICE_EXCUSED_OUTING);
    }

    @Test
    @DisplayName("★ 사유신청이 있어도 자동으로 조퇴 처리하지 않는다 — 학생이 고른다")
    void approvedExcuseNeverAutoApplies() {
        var decision = policy.decide(List.of(CHECK_IN), weekday(), LocalTime.of(14, 0),
                LATE_AFTER, new ExcusedOptions(true, false));

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.event()).isNull();
    }

    @Test
    @DisplayName("★ 조퇴 판정이 사유신청 선택지보다 먼저다 — 이미 조퇴했으면 선택지도 안 뜬다")
    void earlyLeaveBlockWinsOverPrompt() {
        assertThat(policy.decide(List.of(CHECK_IN, EARLY_LEAVE), weekday(),
                LocalTime.of(14, 0), LATE_AFTER, new ExcusedOptions(true, true)).code())
                .isEqualTo(DsaCode.ALREADY_LEFT_EARLY);
    }

    @Test
    @DisplayName("★ 복귀 판정이 운영시간 판정보다 먼저다")
    void returnWinsOverOperatingHours() {
        assertThat(policy.decide(List.of(CHECK_IN, OUTING), weekday(),
                LocalTime.of(6, 0), LATE_AFTER, ExcusedOptions.none()).event())
                .isEqualTo(RETURN);
    }

    @Test
    @DisplayName("복귀 후 또 나가려 해도 승인이 필요하다")
    void afterReturnStillNeedsApproval() {
        assertThat(decide(List.of(CHECK_IN, OUTING, RETURN), LocalTime.of(15, 0)).code())
                .isEqualTo(DsaCode.NO_APPROVAL);
    }
}
