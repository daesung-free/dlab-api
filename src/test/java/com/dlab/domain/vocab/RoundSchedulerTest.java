package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.vocab.service.RoundScheduler;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D:VOCA 회차 일정 (v1 5장).
 *
 * <h2>★ 업체 예시를 그대로 넣는다</h2>
 * v1 5.2 가 3주치 예시를 숫자로 줬다. 그게 유일한 외부 검증이고, 규칙을 말로 옮기다
 * 틀리는 것을 여기서 잡는다.
 *
 * <pre>
 * 1주차  월1 화2 수3 목4   금 1-4
 * 2주차  월5 화6 수7 목공휴일   금 5-7      ← ★ 달력이면 5-8 이다
 * 3주차  월8 화9 수공휴일 목모의고사   금 8-9
 * </pre>
 */
class RoundSchedulerTest {

    /** 2026-03-02 는 월요일이다. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);

    private List<RoundScheduler.Round> run(LocalDate until, Set<LocalDate> noExam) {
        return RoundScheduler.calculate(MONDAY, until, noExam);
    }

    private String describe(RoundScheduler.Round r) {
        return r.date().getDayOfWeek().getValue() + ":"
                + (r.weekly() ? "누적" : "") + r.unitFrom() + "-" + r.unitTo();
    }

    @Test
    @DisplayName("★ 1주차 — 월1 화2 수3 목4, 금 1-4 누적")
    void firstWeekMatchesVendorExample() {
        var rounds = run(MONDAY.plusDays(4), Set.of());

        assertThat(rounds).extracting(this::describe)
                .containsExactly("1:1-1", "2:2-2", "3:3-3", "4:4-4", "5:누적1-4");
    }

    @Test
    @DisplayName("★★ 2주차 — 목요일이 공휴일이면 금요일 누적이 5-7 이다 (달력이면 5-8)")
    void holidayShrinksWeeklyRange() {
        var thursday = MONDAY.plusDays(10);    // 2주차 목요일
        var rounds = run(MONDAY.plusDays(11), Set.of(thursday));

        // ⚠️ 달력으로 계산하면 5-8 이 되어 학생이 안 본 유닛이 시험에 나온다
        assertThat(rounds).extracting(this::describe)
                .endsWith("1:5-5", "2:6-6", "3:7-7", "5:누적5-7");
    }

    @Test
    @DisplayName("★★ 업체 예시 3주치를 그대로 재현한다 — 규칙을 말로 옮기다 틀리는 것을 잡는다")
    void reproducesVendorExample() {
        // v1 5.2: 2주차 목 공휴일 · 3주차 수 공휴일 · 3주차 목 모의고사
        var noExam = Set.of(
                MONDAY.plusDays(10),   // 2주차 목
                MONDAY.plusDays(16),   // 3주차 수
                MONDAY.plusDays(17));  // 3주차 목
        var rounds = run(MONDAY.plusDays(18), noExam);

        assertThat(rounds).extracting(this::describe).containsExactly(
                "1:1-1", "2:2-2", "3:3-3", "4:4-4", "5:누적1-4",
                // ★ 목요일이 빠져 누적이 5-7 이다. 달력이면 5-8 이 되어
                //   학생이 안 본 8유닛이 시험에 나온다
                "1:5-5", "2:6-6", "3:7-7", "5:누적5-7",
                // ★ 수·목이 빠져 8-9 만 공개됐다
                "1:8-8", "2:9-9", "5:누적8-9");
    }

    @Test
    @DisplayName("★★ 시험 없는 날에는 진도가 밀린다 — 유닛을 건너뛰지 않는다")
    void progressIsPushedNotSkipped() {
        var tuesday = MONDAY.plusDays(1);
        var rounds = run(MONDAY.plusDays(2), Set.of(tuesday));

        // 화요일을 쉬었으면 수요일이 2유닛이다. 3유닛으로 뛰면 2유닛을 아무도 안 본다
        assertThat(rounds).extracting(this::describe)
                .containsExactly("1:1-1", "3:2-2");
    }

    @Test
    @DisplayName("★ 그 주에 공개된 유닛이 없으면 금요일 누적도 없다 — 빈 시험을 내지 않는다")
    void noWeeklyWhenNothingOpened() {
        var rounds = run(MONDAY.plusDays(4),
                Set.of(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2), MONDAY.plusDays(3)));

        assertThat(rounds).isEmpty();
    }

    @Test
    @DisplayName("★ 토·일에는 공개되지 않는다")
    void weekendHasNoRounds() {
        var rounds = run(MONDAY.plusDays(6), Set.of());

        assertThat(rounds).noneMatch(r ->
                r.date().getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                        || r.date().getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("★★ 50유닛이 끝나면 2회독이 이어서 시작된다 — 하루 2유닛이다")
    void secondCycleStartsAfterFifty() {
        // 1회독은 월~목만 세면 50회차다. 넉넉히 돌린다
        var rounds = run(MONDAY.plusYears(1), Set.of());

        var firstOfCycle2 = rounds.stream()
                .filter(r -> r.cycle() == 2 && !r.weekly())
                .findFirst().orElseThrow();
        assertThat(firstOfCycle2.unitFrom()).isEqualTo(1);
        assertThat(firstOfCycle2.unitTo()).isEqualTo(2);

        var firstOfCycle3 = rounds.stream()
                .filter(r -> r.cycle() == 3 && !r.weekly())
                .findFirst().orElseThrow();
        assertThat(firstOfCycle3.unitTo()).isEqualTo(5);
    }

    @Test
    @DisplayName("★ 회독별 회차 수 — 1회독 50 · 2회독 25 · 3회독 10 (v1 5.3)")
    void roundCountPerCycle() {
        var rounds = run(MONDAY.plusYears(2), Set.of());

        for (int cycle = 1; cycle <= 3; cycle++) {
            int expected = switch (cycle) {
                case 1 -> 50;
                case 2 -> 25;
                default -> 10;
            };
            final int c = cycle;
            assertThat(rounds.stream().filter(r -> r.cycle() == c && !r.weekly()).count())
                    .as("%d회독 평일 회차 수", cycle)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("★★ 3회독이 끝나면 멈춘다 — 4회독을 만들지 않는다 (v1 Q4 미확정)")
    void stopsAfterThirdCycle() {
        var rounds = run(MONDAY.plusYears(3), Set.of());

        assertThat(rounds).noneMatch(r -> r.cycle() > 3);
    }

    @Test
    @DisplayName("★ 유닛 범위가 50을 넘지 않는다")
    void unitsStayInRange() {
        var rounds = run(MONDAY.plusYears(2), Set.of());

        assertThat(rounds).allSatisfy(r -> {
            assertThat(r.unitFrom()).isBetween(1, RoundScheduler.TOTAL_UNITS);
            assertThat(r.unitTo()).isBetween(1, RoundScheduler.TOTAL_UNITS);
            assertThat(r.unitFrom()).isLessThanOrEqualTo(r.unitTo());
        });
    }

    @Test
    @DisplayName("★★ 주가 회독 경계를 넘으면 누적 범위가 뒤집히지 않는다 — from>to 가 나왔다")
    void weeklyRangeDoesNotInvertAtCycleBoundary() {
        var rounds = run(MONDAY.plusYears(2), Set.of());

        // 월 49-50(1회독 끝) · 화 1-2(2회독 시작) 인 주가 실제로 생긴다.
        // 그때 누적을 49~2 로 두면 뒤집힌 범위가 저장되고, 출제가 그 범위를 믿는다
        assertThat(rounds).allSatisfy(r ->
                assertThat(r.unitFrom())
                        .as("%s %s %d-%d", r.date(), r.weekly() ? "누적" : "평일",
                                r.unitFrom(), r.unitTo())
                        .isLessThanOrEqualTo(r.unitTo()));
    }

    @Test
    @DisplayName("★ 경계를 넘은 주의 누적은 새 회독 몫만 담는다 — 앞 회독분은 방금 봤다")
    void weeklyAfterBoundaryCoversNewCycleOnly() {
        var rounds = run(MONDAY.plusYears(1), Set.of());

        // 회독이 바뀐 날을 찾고, 그 주 금요일 누적이 1 부터인지 본다
        var boundary = rounds.stream()
                .filter(r -> !r.weekly() && r.cycle() == 2 && r.unitFrom() == 1)
                .findFirst().orElseThrow();
        var weekly = rounds.stream()
                .filter(RoundScheduler.Round::weekly)
                .filter(r -> !r.date().isBefore(boundary.date())
                        && r.date().isBefore(boundary.date().plusDays(7)))
                .findFirst().orElseThrow();

        assertThat(weekly.unitFrom())
                .as("경계 뒤 유닛만 담는다 — 반대로 하면 새 회독이 누적에서 빠진다")
                .isEqualTo(1);
        assertThat(weekly.cycle()).isEqualTo(2);
    }
}
