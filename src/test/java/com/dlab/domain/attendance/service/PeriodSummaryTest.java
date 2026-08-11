package com.dlab.domain.attendance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.service.AttendanceQueryService.DailySummary;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기간 집계 — 순공시간 합계 · 출석률.
 *
 * <p><b>앱 홈과 관리자 대시보드가 같은 값을 봐야 한다.</b> 계산이 표현 계층에 있으면
 * 화면마다 갈리는데, 출석률은 학생·학부모가 직접 보는 숫자다.
 *
 * <p>DB 없이 도는 순수 단위 테스트다 — 규칙만 검증한다.
 */
class PeriodSummaryTest {

    private final AttendanceQueryService service =
            new AttendanceQueryService(null, null, null, null);

    private DailySummary day(int dayOfMonth, DailyStatus status, Integer studyMinutes) {
        return new DailySummary(LocalDate.of(2026, 8, dayOfMonth), status, false,
                studyMinutes, List.of(), List.of());
    }

    @Test
    @DisplayName("★ 확정 전인 날은 순공에서 빠진다 — 0으로 채우면 어제까지의 합보다 작아 보인다")
    void unconfirmedDayIsExcludedFromStudyTime() {
        var result = service.summarize(List.of(
                day(1, DailyStatus.PRESENT, 480),
                day(2, DailyStatus.PRESENT, 420),
                day(3, null, null)));          // 오늘 — 아직 확정 전

        assertThat(result.studyMinutes()).isEqualTo(900);
        assertThat(result.confirmedDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 지각·조퇴는 출석으로 센다 — 빼면 사실상 '무지각률'이 된다")
    void lateAndEarlyLeaveCountAsPresent() {
        var result = service.summarize(List.of(
                day(1, DailyStatus.PRESENT, 480),
                day(2, DailyStatus.LATE, 400),
                day(3, DailyStatus.EARLY_LEAVE, 300),
                day(4, DailyStatus.ABSENT, 0)));

        assertThat(result.attendanceRate()).isEqualTo(75);   // 4일 중 3일 출석
    }

    @Test
    @DisplayName("★ 미확정인 오늘은 분모에서 빠진다 — 매일 아침 떨어졌다 회복되는 것처럼 보인다")
    void unconfirmedDayIsExcludedFromRate() {
        var result = service.summarize(List.of(
                day(1, DailyStatus.PRESENT, 480),
                day(2, DailyStatus.PRESENT, 480),
                day(3, null, null)));

        assertThat(result.attendanceRate()).isEqualTo(100);
    }

    @Test
    @DisplayName("★★ 확정된 날이 없으면 0%가 아니라 null — 아무 일도 없었는데 결석처럼 보인다")
    void noConfirmedDayIsNullNotZero() {
        var result = service.summarize(List.of(day(1, null, null)));

        assertThat(result.attendanceRate()).isNull();
        assertThat(result.confirmedDays()).isZero();
        assertThat(result.studyMinutes()).isZero();
    }

    @Test
    @DisplayName("기록이 아예 없어도 null이다")
    void emptyIsNull() {
        var result = service.summarize(List.of());

        assertThat(result.attendanceRate()).isNull();
        assertThat(result.studyMinutes()).isZero();
    }

    @Test
    @DisplayName("반올림한다 — 3일 중 2일이면 67%")
    void rateIsRounded() {
        var result = service.summarize(List.of(
                day(1, DailyStatus.PRESENT, 0),
                day(2, DailyStatus.PRESENT, 0),
                day(3, DailyStatus.ABSENT, 0)));

        assertThat(result.attendanceRate()).isEqualTo(67);
    }

    @Test
    @DisplayName("확정됐는데 순공이 없는 날도 있다 — 결석이면 null이다")
    void confirmedButNoStudyTime() {
        var result = service.summarize(List.of(
                day(1, DailyStatus.ABSENT, null),
                day(2, DailyStatus.PRESENT, 300)));

        assertThat(result.studyMinutes()).isEqualTo(300);
        assertThat(result.confirmedDays()).isEqualTo(2);
        assertThat(result.attendanceRate()).isEqualTo(50);
    }
}
