package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 순공(순수 공부)시간 산출 (오픈이슈 I-6).
 *
 * <h2>정의</h2>
 * <b>재실시간에서 외출·급식·쉬는시간을 뺀 것.</b> (2026-08-05 확정)
 *
 * <p>계산은 두 단계다:
 * <ol>
 *   <li><b>재실 구간</b> — 등원/지각/복귀로 열리고 하원/조퇴/외출로 닫힌다.
 *       외출 구간이 여기서 자동으로 빠진다</li>
 *   <li><b>학습 교시와 교집합</b> — 급식({@link PeriodType#MEAL})·쉬는시간
 *       ({@link PeriodType#BREAK})은 애초에 교시 마스터에 별도 행으로 있으므로
 *       학습 교시만 남기면 자동으로 빠진다</li>
 * </ol>
 *
 * <p><b>급식·쉬는시간을 시각 상수로 박지 않는 이유</b>가 여기 있다. 점심시간이 지점마다
 * 다르고 토요일엔 아예 구성이 다른데(그래서 {@code day_type}이 있다), 상수로 두면
 * 교시를 바꿀 때마다 순공시간 계산이 조용히 어긋난다. 교시 마스터가 유일한 출처다.
 *
 * <h2>{@link PeriodType#ETC}는 학습으로 치지 않는다</h2>
 * 종례·조회처럼 자리에 있어도 공부가 아닌 시간이다. 학습으로 칠 교시는
 * <b>수업과 자습 둘뿐</b>이다.
 *
 * <h2>하원을 안 찍고 간 경우</h2>
 * 그날 마지막 교시 종료(또는 조회 시점 중 이른 쪽)로 닫는다. 열어두면 순공시간이
 * 무한정 늘고, 마지막 태깅으로 닫으면 하루 종일 공부한 학생이 0분이 된다.
 * <b>둘 다 틀리지만 전자가 더 위험하다</b> — 랭킹 1위가 되기 때문이다.
 */
@Component
@Slf4j
public class StudyTimeCalculator {

    /** 재실을 여는 이벤트. */
    private static final Set<AttendanceEventType> OPENING = EnumSet.of(
            AttendanceEventType.CHECK_IN,
            AttendanceEventType.LATE,
            AttendanceEventType.RETURN);

    /** 학습으로 인정하는 교시. */
    private static final Set<PeriodType> STUDY = EnumSet.of(
            PeriodType.CLASS,
            PeriodType.SELF_STUDY);

    /**
     * 하루치 순공시간.
     *
     * @param dayLogs 그 학생의 그날 태깅 원장(순서 무관 — 내부에서 정렬한다)
     * @param periods 그날 요일 구분의 교시 전체
     * @param until   아직 안 닫힌 재실 구간을 닫을 시각(보통 현재 시각).
     *                지난 날짜를 계산할 때는 그날 마지막 교시 종료가 자동으로 상한이 된다
     */
    public Duration calculate(List<AttendanceTaggingLog> dayLogs,
                              List<PeriodMaster> periods,
                              LocalTime until) {
        if (dayLogs.isEmpty()) {
            return Duration.ZERO;
        }

        List<Interval> presence = presenceIntervals(dayLogs, periods, until);
        if (presence.isEmpty()) {
            return Duration.ZERO;
        }

        // ★ 교시가 없는 날(자율등원일)은 재실시간이 곧 순공이다.
        //   교집합을 구하는 이유가 급식·쉬는시간을 빼기 위해서인데 뺄 것이 없다.
        //   0을 돌려주면 주말에 하루 종일 공부한 학생이 순공 0분으로 남아
        //   랭킹에서 주말이 통째로 빠진다. 외출은 재실 구간이 이미 걸러낸다
        if (periods.isEmpty()) {
            return presence.stream()
                    .map(Interval::duration)
                    .reduce(Duration.ZERO, Duration::plus);
        }

        Duration total = Duration.ZERO;
        for (PeriodMaster period : periods) {
            if (!STUDY.contains(period.getPeriodType())) {
                continue;
            }
            Interval study = new Interval(period.getStartTime(), period.getEndTime());
            for (Interval p : presence) {
                total = total.plus(p.overlapWith(study));
            }
        }
        return total;
    }

    /**
     * 하루치 <b>재실시간</b> — 순공시간의 원재료다.
     *
     * <p>Daily Report 집계(F-4.11-6)가 순공과 나란히 내려야 하는 값이라 여기에 둔다.
     * <b>같은 재실 구간에서 두 값이 나와야</b> "재실 5시간인데 순공 6시간"처럼 서로
     * 모순되는 숫자가 한 화면에 나오지 않는다 — 호출부가 따로 계산하면 외출 처리나
     * 하원 누락 보정이 어긋나면서 그렇게 된다.
     *
     * <p>급식·쉬는시간은 <b>빼지 않는다</b>. 그걸 빼는 것이 순공의 정의이고,
     * 재실은 "자리에 있던 시간"이다 — 둘의 차이가 곧 화면이 보여주려는 정보다.
     */
    public Duration presence(List<AttendanceTaggingLog> dayLogs,
                             List<PeriodMaster> periods,
                             LocalTime until) {
        if (dayLogs.isEmpty()) {
            return Duration.ZERO;
        }
        return presenceIntervals(dayLogs, periods, until).stream()
                .map(Interval::duration)
                .reduce(Duration.ZERO, Duration::plus);
    }

    /** 재실 구간. 외출은 구간을 닫으므로 여기서 이미 빠진다. */
    private List<Interval> presenceIntervals(List<AttendanceTaggingLog> dayLogs,
                                             List<PeriodMaster> periods,
                                             LocalTime until) {
        List<Interval> result = new ArrayList<>();
        LocalTime openedAt = null;
        /** 조퇴로 하루가 닫혔는가. 닫힌 뒤 태깅은 재실을 다시 열지 않는다 */
        boolean closedForDay = false;

        List<AttendanceTaggingLog> sorted = dayLogs.stream()
                .sorted(Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .toList();

        for (AttendanceTaggingLog tagging : sorted) {
            // ★ systemDefault()를 쓰면 안 된다 — 운영 서버(EC2/ECS)는 UTC라
            //   교시(KST 기준 시각)와 9시간 어긋나 순공시간이 통째로 틀어진다.
            //   로컬은 KST라 통과하고 CI·운영에서만 깨져서 알아채기 어렵다
            LocalTime at = tagging.getRecordedAt().atZone(TimeConfig.KST).toLocalTime();

            // ★★ 조퇴는 하루의 종료 경계다 — 그 뒤 태깅으로 재실이 다시 열리면 안 된다.
            //    「08:30 외출 → 08:30 조퇴 → 08:31 복귀」를 넣으면 조퇴가 버려지고
            //    복귀가 재실을 다시 열어, 안 닫힌 구간이 마지막 교시까지 밀려
            //    08:31 에 간 학생에게 순공 11시간 49분이 찍혔다(2026-10-16 한*주, 실제 발생).
            //    조퇴 뒤에 다시 들어왔다면 조퇴가 취소돼야 맞다 — 계산기가 추측하지 않는다.
            if (closedForDay) {
                continue;
            }

            if (tagging.getEventType() == AttendanceEventType.EARLY_LEAVE) {
                if (openedAt != null) {
                    result.add(new Interval(openedAt, at));
                    openedAt = null;
                }
                // ⚠️ 재실이 안 열린 상태로 조퇴가 와도 종료는 세운다. 열려 있지 않다고
                //    버리면(종전 동작) 뒤따르는 복귀가 하루를 다시 열어 버린다
                closedForDay = true;
            } else if (OPENING.contains(tagging.getEventType())) {
                // 이미 열려 있으면 무시한다. 중복 등원 기록이 재실을 두 번 세지 않게
                if (openedAt == null) {
                    openedAt = at;
                }
            } else if (openedAt != null) {
                result.add(new Interval(openedAt, at));
                openedAt = null;
            } else {
                // ⚠️ 재실이 열려 있지 않은데 닫는 이벤트가 왔다 — 조용히 버리지 않는다.
                //    원장이 어긋난 것이고(중복 외출·순서 뒤바뀜) 그대로 두면 숫자만 이상해진다
                log.warn("재실이 열리지 않은 상태의 닫는 태깅 — event={}, at={}, date={}",
                        tagging.getEventType(), at, tagging.getAttendanceDate());
            }
        }

        if (openedAt != null) {
            // 교시가 없으면 닫을 기준이 조회 시점뿐이다(자율등원일)
            LocalTime close = periods.isEmpty()
                    ? until
                    : periods.get(periods.size() - 1).getEndTime();
            result.add(new Interval(openedAt, until.isBefore(close) ? until : close));
        }
        return result;
    }

    /** {@code "HH시간 mm분"}. 규격서 샘플이 {@code "06시간 31분"}으로 <b>0을 채운다</b>. */
    public static String format(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        return String.format("%02d시간 %02d분", minutes / 60, minutes % 60);
    }

    private record Interval(LocalTime start, LocalTime end) {

        Duration duration() {
            return end.isAfter(start) ? Duration.between(start, end) : Duration.ZERO;
        }

        Duration overlapWith(Interval other) {
            LocalTime from = start.isAfter(other.start) ? start : other.start;
            LocalTime to = end.isBefore(other.end) ? end : other.end;
            return to.isAfter(from) ? Duration.between(from, to) : Duration.ZERO;
        }
    }
}
