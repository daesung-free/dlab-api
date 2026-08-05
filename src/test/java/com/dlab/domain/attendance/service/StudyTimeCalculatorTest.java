package com.dlab.domain.attendance.service;

import static com.dlab.domain.attendance.entity.AttendanceEventType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 순공시간 산출 (I-6).
 *
 * <p>정의: <b>재실시간 − 외출 − 급식 − 쉬는시간</b>.
 * 급식·쉬는시간은 교시 마스터에 별도 행으로 있으므로 학습 교시만 남기면 자동으로 빠진다.
 */
class StudyTimeCalculatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 5);

    private final StudyTimeCalculator calculator = new StudyTimeCalculator();

    /**
     * 09:00~12:00 자습 · 12:00~13:00 <b>급식</b> · 13:00~14:00 <b>쉬는시간</b> ·
     * 14:00~18:00 자습 → 학습 교시 합계 7시간.
     */
    private List<PeriodMaster> periods() {
        return List.of(
                period(1, PeriodType.SELF_STUDY, 9, 12),
                period(2, PeriodType.MEAL, 12, 13),
                period(3, PeriodType.BREAK, 13, 14),
                period(4, PeriodType.SELF_STUDY, 14, 18));
    }

    private PeriodMaster period(int no, PeriodType type, int fromHour, int toHour) {
        return new PeriodMaster(null, (short) 2026, (short) no, no + "교시",
                DayType.WEEKDAY, type,
                LocalTime.of(fromHour, 0), LocalTime.of(toHour, 0));
    }

    private AttendanceTaggingLog log(AttendanceEventType type, int hour, int minute) {
        return new AttendanceTaggingLog(null, null, type, AttendanceSource.KIOSK_NFC,
                DAY.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant(), DAY);
    }

    private Duration calc(List<AttendanceTaggingLog> logs) {
        return calculator.calculate(logs, periods(), LocalTime.of(23, 59));
    }

    @Test
    @DisplayName("★ 급식·쉬는시간은 빠진다 — 09:00 등원 18:00 하원이면 9시간이 아니라 7시간")
    void mealAndBreakAreExcluded() {
        Duration result = calc(List.of(log(CHECK_IN, 9, 0), log(CHECK_OUT, 18, 0)));

        assertThat(result).isEqualTo(Duration.ofHours(7));
    }

    @Test
    @DisplayName("★ 외출 구간이 빠진다")
    void outingIsExcluded() {
        // 09:00 등원 → 10:00 외출 → 11:00 복귀 → 18:00 하원
        Duration result = calc(new ArrayList<>(List.of(
                log(CHECK_IN, 9, 0), log(OUTING, 10, 0),
                log(RETURN, 11, 0), log(CHECK_OUT, 18, 0))));

        // 7시간 − 외출 1시간
        assertThat(result).isEqualTo(Duration.ofHours(6));
    }

    @Test
    @DisplayName("★ 급식시간에 나갔다 오면 이중으로 빠지지 않는다")
    void outingDuringMealIsNotDoubleCounted() {
        // 12:10~12:40 외출 — 어차피 급식시간이라 순공에 없던 구간이다
        Duration result = calc(List.of(
                log(CHECK_IN, 9, 0), log(OUTING, 12, 10),
                log(RETURN, 12, 40), log(CHECK_OUT, 18, 0)));

        assertThat(result).isEqualTo(Duration.ofHours(7));
    }

    @Test
    @DisplayName("★ 하원 전이면 지금까지만 센다 — 열어두면 랭킹 1위가 된다")
    void openPresenceIsClampedToNow() {
        Duration result = calculator.calculate(
                List.of(log(CHECK_IN, 9, 0)), periods(), LocalTime.of(11, 0));

        assertThat(result).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("하원을 안 찍었으면 마지막 교시 종료로 닫는다")
    void missingCheckOutClampsToLastPeriodEnd() {
        Duration result = calculator.calculate(
                List.of(log(CHECK_IN, 9, 0)), periods(), LocalTime.of(23, 59));

        assertThat(result).isEqualTo(Duration.ofHours(7));
    }

    @Test
    @DisplayName("지각 등원도 재실을 연다")
    void lateOpensPresence() {
        assertThat(calc(List.of(log(LATE, 14, 0), log(CHECK_OUT, 18, 0))))
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    @DisplayName("★ 조퇴는 재실을 닫는다")
    void earlyLeaveClosesPresence() {
        assertThat(calc(List.of(log(CHECK_IN, 9, 0), log(EARLY_LEAVE, 11, 0))))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("운영시간 밖 태깅은 순공에 안 들어간다")
    void timeOutsidePeriodsIsIgnored() {
        // 07:00 등원(교시 시작 전) → 09:00부터만 센다
        assertThat(calc(List.of(log(CHECK_IN, 7, 0), log(CHECK_OUT, 10, 0))))
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("★ 중복 등원 기록이 있어도 이중으로 세지 않는다")
    void duplicateOpeningDoesNotDoubleCount() {
        assertThat(calc(List.of(
                log(CHECK_IN, 9, 0), log(CHECK_IN, 9, 30), log(CHECK_OUT, 11, 0))))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("기록이 없으면 0")
    void noLogsIsZero() {
        assertThat(calc(List.of())).isZero();
        assertThat(calculator.calculate(List.of(log(CHECK_IN, 9, 0)), List.of(),
                LocalTime.of(18, 0))).isZero();
    }

    @Test
    @DisplayName("★ 표기는 0을 채운다 — 규격서 샘플이 \"06시간 31분\"")
    void formatIsZeroPadded() {
        assertThat(StudyTimeCalculator.format(Duration.ofMinutes(391))).isEqualTo("06시간 31분");
        assertThat(StudyTimeCalculator.format(Duration.ZERO)).isEqualTo("00시간 00분");
        assertThat(StudyTimeCalculator.format(Duration.ofMinutes(630))).isEqualTo("10시간 30분");
    }
}
