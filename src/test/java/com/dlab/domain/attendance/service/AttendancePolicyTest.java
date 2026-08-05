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
    @DisplayName("★ 그날 교시가 없으면 113 — 일요일·공휴일이라 자동판별이 불가하다")
    void noTimetableAsksUser() {
        var decision = policy.decide(List.of(), List.of(), LocalTime.of(10, 0),
                LATE_AFTER, ExcusedOptions.none());

        assertThat(decision.code()).isEqualTo(DsaCode.NO_TIMETABLE);
        assertThat(decision.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("운영시간 밖이면 122")
    void outsideOperatingHoursIsRejected() {
        assertThat(decide(List.of(), LocalTime.of(7, 0)).code())
                .isEqualTo(DsaCode.OUTSIDE_STUDY_HOURS);
        assertThat(decide(List.of(), LocalTime.of(22, 30)).code())
                .isEqualTo(DsaCode.OUTSIDE_STUDY_HOURS);
    }

    @Test
    @DisplayName("★ 마지막 교시 종료가 하원과 외출을 가른다")
    void afterLastPeriodIsCheckOut() {
        // 종료 전이면 아직 돌아온다고 본다
        assertThat(decide(List.of(CHECK_IN), LocalTime.of(21, 59)).event()).isEqualTo(OUTING);

        assertThat(decide(List.of(CHECK_IN), LocalTime.of(22, 0)).event()).isEqualTo(CHECK_OUT);
        assertThat(decide(List.of(CHECK_IN), LocalTime.of(22, 30)).event()).isEqualTo(CHECK_OUT);
    }

    @Test
    @DisplayName("★ 운영 종료 시각이 하원을 막으면 안 된다 — 그러면 하원 태깅이 영영 불가능해진다")
    void closingTimeMustNotBlockCheckOut() {
        // 등원한 학생: 종료 후에도 하원이 찍혀야 한다
        assertThat(decide(List.of(CHECK_IN), LocalTime.of(22, 10)).isAccepted()).isTrue();

        // 반면 등원한 적 없는 학생의 종료 후 첫 태깅은 등원이 아니다
        assertThat(decide(List.of(), LocalTime.of(22, 10)).code())
                .isEqualTo(DsaCode.OUTSIDE_STUDY_HOURS);
    }

    @Test
    @DisplayName("★ 수업 중간 재태깅은 외출 — 평일에 되물으면 매번 버튼을 눌러야 한다")
    void midDayRetagIsOuting() {
        // 키오스크는 "마감시간까지 DSA가 판별한다"고 전제한다(그쪽 TagService 주석).
        // 113은 시간표가 없는 날에만 쓰는 코드다
        assertThat(decide(List.of(CHECK_IN), LocalTime.of(14, 0)).event()).isEqualTo(OUTING);
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
    @DisplayName("복귀 후 다시 나가면 또 외출이다")
    void afterReturnCanGoOutAgain() {
        assertThat(decide(List.of(CHECK_IN, OUTING, RETURN), LocalTime.of(15, 0)).event())
                .isEqualTo(OUTING);
    }
}
