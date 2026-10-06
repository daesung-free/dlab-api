package com.dlab.domain.attendance.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.attendance.entity.*;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.domain.attendance.repository.AttendanceDailyStatusRepository;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 학생·학부모용 출결·상벌점 조회 (앱 요구사항 A-18).
 *
 * <p><b>조회 전용이다.</b> 태깅은 키오스크가, 정정은 관리자 웹이 한다.
 *
 * <p><b>학부모도 같은 데이터를 본다</b>(A-18 사용자가 "학생·학부모"). 다만 자녀 스코프
 * 검증을 거쳐야 하므로, 호출자가 등록 건 ID를 어떻게 얻었는지가 중요하다 —
 * 학부모 경로는 반드시 {@code ParentSignupService.requireMyChild}를 거친다.
 */
@Service
@RequiredArgsConstructor
public class AttendanceQueryService {

    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final AttendanceDailyStatusRepository dailyStatusRepository;
    private final AbsenceReasonRepository absenceReasonRepository;
    private final PenaltyPointRepository penaltyPointRepository;
    private final java.time.Clock clock;

    /**
     * 하루치 출결.
     *
     * @param finalStatus 배치가 확정한 일자 상태. <b>당일에는 {@code null}</b>이다 —
     *                    확정은 다음날 새벽 배치가 한다. "아직 확정 전"과 "결석"을 구분해야
     *                    앱이 오늘 날짜에 결석을 표시하지 않는다
     * @param studyMinutes 순공시간(분). 확정 전이면 {@code null}
     * @param events      그날의 태깅 이력. 입·퇴실 시각이 여기서 나온다
     */
    public record DailySummary(LocalDate date, DailyStatus finalStatus, boolean excused,
                               Integer studyMinutes, List<AttendanceTaggingLog> events,
                               List<AbsenceReason> reasons) {

        /** 입실 시각 — 그날 첫 등원·지각 태깅({@code isArrival}). */
        public AttendanceTaggingLog firstIn() {
            return events.stream()
                    .filter(e -> e.getEventType().isArrival())
                    .findFirst().orElse(null);
        }

        /**
         * 퇴실 시각 — 그날 <b>마지막</b> 하원 태깅.
         *
         * <p>외출({@code OUTING})·복귀({@code RETURN})가 섞여 있어 첫 건을 쓰면 안 되고,
         * 하원 자체도 재등원 후 다시 찍힐 수 있어 마지막 것이 실제 퇴실이다.
         */
        public AttendanceTaggingLog lastOut() {
            return events.stream()
                    .filter(e -> e.getEventType() == AttendanceEventType.CHECK_OUT)
                    .reduce((a, b) -> b).orElse(null);
        }
    }

    /** 상벌점 현황. */
    /**
     * @param total       <b>전체 기간 누적</b>. 제적 기준(40점)이 보는 값이라 기간을 걸러도 함께 내린다
     * @param periodTotal 선택한 기간의 증감. 기간을 주지 않았으면 {@code null}
     */
    public record PenaltySummary(int total, Integer periodTotal, List<PenaltyPoint> items) {
    }

    /**
     * 기간 출결 — 앱의 월 달력·이력 화면.
     *
     * <p><b>일자 확정 행이 없는 날도 빠뜨리지 않는다.</b> 확정 배치는 <b>교시가 있는 날</b>만
     * 도는데, 주말·공휴일에도 태깅은 남을 수 있다(자율학습 등). 확정 행만 보면 그날이
     * 통째로 사라져 앱 달력에 구멍이 생긴다.
     */
    @Transactional(readOnly = true)
    public List<DailySummary> daily(Long enrollmentId, LocalDate from, LocalDate to) {
        verifyRange(from, to);

        Map<LocalDate, List<AttendanceTaggingLog>> logsByDate =
                taggingLogRepository.findByEnrollmentAndPeriod(enrollmentId, from, to).stream()
                        .collect(Collectors.groupingBy(AttendanceTaggingLog::getAttendanceDate));

        Map<LocalDate, AttendanceDailyStatus> statusByDate =
                dailyStatusRepository.findByEnrollmentAndPeriod(enrollmentId, from, to).stream()
                        .collect(Collectors.toMap(AttendanceDailyStatus::getAttendanceDate,
                                Function.identity(), (a, b) -> a));

        Map<LocalDate, List<AbsenceReason>> reasonsByDate =
                absenceReasonRepository.findByEnrollmentAndPeriod(enrollmentId, from, to).stream()
                        .collect(Collectors.groupingBy(AbsenceReason::getAttendanceDate));

        // 세 곳 중 어디에라도 기록이 있는 날은 전부 내린다
        return from.datesUntil(to.plusDays(1))
                .filter(date -> logsByDate.containsKey(date)
                        || statusByDate.containsKey(date)
                        || reasonsByDate.containsKey(date))
                .map(date -> {
                    AttendanceDailyStatus status = statusByDate.get(date);
                    return new DailySummary(
                            date,
                            status == null ? null : status.getFinalStatus(),
                            status != null && status.isExcused(),
                            status == null ? null : status.getStudyMinutes(),
                            logsByDate.getOrDefault(date, List.of()),
                            reasonsByDate.getOrDefault(date, List.of()));
                })
                .toList();
    }

    /**
     * 상벌점 — 누적 점수 + 내역.
     *
     * <p><b>벌점은 음수로 저장돼 있다.</b> 합계를 그대로 내리므로 앱이 부호를 보고
     * 상점/벌점을 구분한다 — 절댓값으로 바꾸면 상쇄가 사라진다.
     */
    @Transactional(readOnly = true)
    /**
     * 기간 집계 — 순공시간 합계 · 출석률 · 확정 일수.
     *
     * <p><b>앱 홈과 관리자 대시보드가 같은 값을 봐야 한다.</b> 계산이 표현 계층에 있으면
     * 화면마다 조금씩 갈리는데, 출석률은 학생·학부모가 직접 보는 숫자라
     * 두 화면이 다른 값을 내면 신뢰가 깨진다.
     */
    public PeriodSummary summarize(Long enrollmentId, LocalDate from, LocalDate to) {
        return summarize(daily(enrollmentId, from, to));
    }

    /** 이미 뽑아둔 일자 목록으로 집계한다 — 같은 구간을 두 번 조회하지 않게. */
    public PeriodSummary summarize(List<DailySummary> days) {
        int studyMinutes = days.stream()
                .map(DailySummary::studyMinutes)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        List<DailyStatus> confirmed = days.stream()
                .map(DailySummary::finalStatus)
                .filter(Objects::nonNull)
                .toList();

        Integer rate = null;
        if (!confirmed.isEmpty()) {
            long present = confirmed.stream().filter(s -> s != DailyStatus.ABSENT).count();
            rate = (int) Math.round(present * 100.0 / confirmed.size());
        }

        // ★ 외출은 일자 상태에 없어 태깅 이벤트에서 센다 — 상태 enum 은 4종(출석·지각·결석·조퇴)뿐이다
        long outing = countEvents(days, AttendanceEventType.OUTING);
        long excusedOuting = countEvents(days, AttendanceEventType.EXCUSED_OUTING);

        return new PeriodSummary(studyMinutes, rate, confirmed.size(),
                countStatus(confirmed, DailyStatus.LATE),
                countStatus(confirmed, DailyStatus.ABSENT),
                countStatus(confirmed, DailyStatus.EARLY_LEAVE),
                (int) outing, (int) excusedOuting);
    }

    private static int countStatus(List<DailyStatus> confirmed, DailyStatus status) {
        return (int) confirmed.stream().filter(s -> s == status).count();
    }

    private static long countEvents(List<DailySummary> days, AttendanceEventType type) {
        return days.stream()
                .flatMap(d -> d.events().stream())
                .filter(e -> e.getEventType() == type)
                .count();
    }

    /**
     * 기간 집계 결과.
     *
     * <p><b>확정된 날만 센다.</b> 순공은 다음날 새벽 배치가 계산하므로 오늘 몫을 0으로
     * 채우면 "오늘 하나도 안 했다"로 읽혀 어제까지의 합보다 작아 보인다. 출석률도
     * 미확정인 오늘을 분모에 넣으면 매일 아침 떨어졌다가 새벽에 회복되는 것처럼 보인다.
     *
     * <p><b>지각·조퇴는 출석으로 센다</b> — 결석만 결석이다. 둘까지 빼면 출석률이
     * 사실상 "무지각률"이 되는데 화면 이름과 다른 값이 나온다.
     *
     * <p>★ <b>조퇴·외출 건수를 여기서 센다</b>(2026-10-06 디랩 요청, 4-1 15번). 앱이 태깅
     * 이벤트를 직접 세게 두면 <b>"조퇴를 몇 건으로 보는가"가 화면마다 갈리고</b>, 월 단위로
     * 보려고 전 기간 이력을 받아와야 한다.
     *
     * @param attendanceRate 확정된 날이 하나도 없으면 {@code null}. 0%로 내리면
     *                       아무 일도 없었는데 결석한 것처럼 보인다
     * @param outingCount        외출. <b>일자 상태가 아니라 태깅 이벤트</b>라 같은 날 두 번이면 2다
     * @param excusedOutingCount 사유외출. 외출과 합치지 않는다 — 사유 승인을 받은 것이라
     *                           학생에게 불리하게 세면 안 된다
     */
    public record PeriodSummary(int studyMinutes, Integer attendanceRate, int confirmedDays,
                                int lateDays, int absentDays, int earlyLeaveDays,
                                int outingCount, int excusedOutingCount) {
    }

    public PenaltySummary penalties(Long enrollmentId) {
        return penalties(enrollmentId, null, null);
    }

    /**
     * 상벌점 — 기간을 주면 그 기간 내역만, <b>누적 점수는 그대로 함께</b> 내린다.
     *
     * <p>★ <b>누적을 빼고 기간 점수만 주면 안 된다.</b> 제적 기준(40점)이 누적이라,
     * 선택한 달 점수만 보이면 학생이 자기 누적 상태를 알 수 없다 — "난 5점인데 왜 제적인가"가 된다.
     *
     * <p>기간 필터를 쿼리로 내리지 않고 받아온 행에서 가른다. 한 학생의 상벌점은 많아도
     * 수십 건이고, <b>누적 합계와 기간 내역을 같은 시점에서 보여야</b> 두 숫자가 어긋나지 않는다.
     */
    @Transactional(readOnly = true)
    public PenaltySummary penalties(Long enrollmentId, LocalDate from, LocalDate to) {
        int total = penaltyPointRepository.sumPointsByEnrollment(enrollmentId);
        List<PenaltyPoint> all = penaltyPointRepository.findByEnrollment(enrollmentId);

        if (from == null || to == null) {
            return new PenaltySummary(total, null, all);
        }
        verifyRange(from, to);

        List<PenaltyPoint> inRange = all.stream()
                .filter(p -> withinRange(p, from, to))
                .toList();
        int periodTotal = inRange.stream().mapToInt(PenaltyPoint::getPoints).sum();
        return new PenaltySummary(total, periodTotal, inRange);
    }

    /** 부여 시각은 {@code Instant}라 날짜 비교 전에 운영 시간대로 옮긴다. */
    private boolean withinRange(PenaltyPoint point, LocalDate from, LocalDate to) {
        LocalDate occurred = point.getOccurredAt().atZone(clock.getZone()).toLocalDate();
        return !occurred.isBefore(from) && !occurred.isAfter(to);
    }

    /** 당월 사유출결 — A-18의 "당월 사유출결/벌점 표". */
    @Transactional(readOnly = true)
    public List<AbsenceReason> absenceReasons(Long enrollmentId, LocalDate from, LocalDate to) {
        verifyRange(from, to);
        return absenceReasonRepository.findByEnrollmentAndPeriod(enrollmentId, from, to);
    }

    /**
     * 조회 기간 상한.
     *
     * <p>없으면 앱이 실수로 5년치를 요청했을 때 원장 전체를 훑는다 —
     * 학생 한 명당 하루 최대 7건이라 1년이면 2,500건이 넘는다.
     */
    private void verifyRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간이 올바르지 않습니다.");
        }
        if (from.plusYears(1).isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간은 최대 1년입니다.");
        }
    }
}
