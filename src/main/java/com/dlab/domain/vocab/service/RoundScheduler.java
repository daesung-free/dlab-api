package com.dlab.domain.vocab.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * D:VOCA 회차 일정 계산 (v1 5장).
 *
 * <h2>★★ 일정을 미리 만들어 두지 않는다 (v1 5.6)</h2>
 * <i>"지점 시작일 + 시험 없는 날 목록 + 회독 규칙으로 계산하는 쪽을 권장한다.
 * <b>시험 없는 날이 나중에 추가되면 이후 일정이 모두 밀려야 하기 때문이다.</b>"</i>
 *
 * <p>그래서 이 클래스는 <b>상태를 갖지 않는 순수 계산</b>이다. 저장되는 것은
 * 「이미 공개된 회차」뿐이고, 그 범위는 공개 시점에 확정된다 — 나중에 시험 없는 날을
 * 추가해도 <b>지난 회차와 지난 응시 기록이 바뀌지 않는다.</b>
 *
 * <h2>규칙 (v1 5.1 · 5.3)</h2>
 * <pre>
 * 월~목   시험일마다 다음 회차 1개
 * 금      그 주 월~목에 **실제로 공개된** 유닛 전체를 묶은 누적
 * 토·일   시험 없음
 * 시험 없는 날   회차가 공개되지 않고 진도가 다음 시험일로 밀린다
 *
 * 1회독  하루 1유닛  50회차
 * 2회독  하루 2유닛  25회차
 * 3회독  하루 5유닛  10회차
 * </pre>
 *
 * <h2>⚠️ 「실제로 공개된」이 핵심이다</h2>
 * 금요일 누적은 <b>달력상 그 주</b>가 아니라 <b>그 주에 실제로 공개된 유닛</b>이 범위다.
 * v1 5.2 업체 예시 2주차가 그것이다 — {@code 월5·화6·수7·목공휴일 → 금 5-7unit}.
 * 달력으로 계산하면 5-8 이 되어 <b>학생이 안 본 유닛이 시험에 나온다.</b>
 */
public final class RoundScheduler {

    /** 교재 유닛 수. */
    public static final int TOTAL_UNITS = 50;

    /** 회독별 하루 진도 (v1 5.3). */
    private static final int[] UNITS_PER_DAY = {1, 2, 5};

    private RoundScheduler() {
    }

    /**
     * 한 회차.
     *
     * @param cycle    회독 1~3
     * @param unitFrom 범위 시작 유닛
     * @param unitTo   범위 끝 유닛
     * @param weekly   금요일 누적인가
     */
    public record Round(LocalDate date, int roundNo, int cycle,
                        int unitFrom, int unitTo, boolean weekly) {
    }

    /**
     * {@code startDate} 부터 {@code until} 까지 공개될 회차를 계산한다.
     *
     * <p>★ <b>달력을 하루씩 걸으며</b> 만든다. 「n번째 시험일」을 수식으로 구하지 않는다 —
     * 시험 없는 날이 중간에 끼면 수식이 성립하지 않고, 그걸 맞추려 하면 <b>주 경계와
     * 회독 경계가 어긋난다.</b>
     *
     * @param noExamDays 시험 없는 날. 그날은 건너뛰고 진도가 밀린다
     * @return 날짜순. 금요일 누적은 그날 평일분과 <b>함께</b> 들어간다(금요일도 시험일이다)
     */
    public static List<Round> calculate(LocalDate startDate, LocalDate until,
                                        Set<LocalDate> noExamDays) {
        List<Round> rounds = new ArrayList<>();
        if (startDate == null || until == null || until.isBefore(startDate)) {
            return rounds;
        }

        int roundNo = 0;
        int nextUnit = 1;      // 다음에 낼 유닛 (1~50)
        int cycle = 1;
        // ★ 그 주에 실제로 공개된 유닛 — 금요일 누적의 범위다
        Integer weekFrom = null;
        Integer weekTo = null;

        for (LocalDate day = startDate; !day.isAfter(until); day = day.plusDays(1)) {
            DayOfWeek dow = day.getDayOfWeek();

            // 주가 바뀌면 누적 범위를 비운다. ★ 월요일에 비운다 —
            //   일요일에 비우면 금요일 누적이 이미 지나간 뒤라 영향이 없지만,
            //   시작일이 주 중간이면 첫 주 범위가 어긋난다
            if (dow == DayOfWeek.MONDAY) {
                weekFrom = null;
                weekTo = null;
            }
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                continue;
            }
            if (noExamDays.contains(day)) {
                // ★ 진도가 밀린다 — nextUnit 을 올리지 않는다
                continue;
            }
            // ★★ 3회독이 끝나면 멈춘다 (v1 Q4 미확정 — "3회독 종료 후 정지")
            if (cycle > UNITS_PER_DAY.length) {
                break;
            }

            if (dow == DayOfWeek.FRIDAY) {
                // 금요일은 그 주 누적만 낸다 (v1 5.2 예시가 그렇다 — 금요일에 새 유닛이 없다)
                if (weekFrom != null) {
                    rounds.add(new Round(day, ++roundNo, cycle, weekFrom, weekTo, true));
                }
                // ⚠️ 그 주에 공개된 유닛이 없으면 누적도 없다 — 빈 시험을 내지 않는다
                continue;
            }

            int step = UNITS_PER_DAY[cycle - 1];
            int from = nextUnit;
            int to = Math.min(nextUnit + step - 1, TOTAL_UNITS);
            rounds.add(new Round(day, ++roundNo, cycle, from, to, false));

            if (weekFrom == null) {
                weekFrom = from;
            }
            weekTo = to;

            nextUnit = to + 1;
            if (nextUnit > TOTAL_UNITS) {
                // ★ 앞 회독의 50유닛이 끝나면 다음 회독이 이어서 시작된다 (v1 5.3)
                cycle++;
                nextUnit = 1;
                // ★★ 누적 범위를 여기서 비운다 — 주가 회독 경계를 넘는 경우다.
                //
                //   월 49-50(1회독 끝) · 화 1-2(2회독 시작) 이면 그 주에 공개된 유닛이
                //   49,50,1,2 다. unit_from~unit_to 한 쌍으로는 표현할 수 없고,
                //   그대로 두면 from=49 · to=2 라는 **뒤집힌 범위**가 저장된다.
                //
                //   ⚠️ v1 에 이 경우가 없다. 「경계 뒤 유닛만 누적에 넣는다」로 두었다 —
                //   경계 앞 유닛(49-50)은 그 주 평일에 방금 본 것이고, 다음 회독에서
                //   다시 돌아온다. 반대로 하면(경계 앞만) 새로 시작한 회독이 누적에서
                //   빠진다.
                //
                //   ★ 업체 확인이 필요한 항목이다 — 유닛 목록을 저장하는 쪽으로 바꾸면
                //   둘 다 담을 수 있지만, v1 모델이 from~to 다.
                weekFrom = null;
                weekTo = null;
            }
        }
        return rounds;
    }

    /**
     * 그날 공개되는 회차. 없으면 빈 목록이다.
     *
     * <p>★ 금요일에도 평일분이 없다 — v1 5.2 예시가 금요일을 누적만으로 둔다.
     */
    public static List<Round> roundsOn(LocalDate startDate, LocalDate day,
                                       Set<LocalDate> noExamDays) {
        return calculate(startDate, day, noExamDays).stream()
                .filter(r -> r.date().equals(day))
                .toList();
    }
}
