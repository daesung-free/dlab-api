package com.dlab.domain.statistics.service;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.meal.entity.CancelPath;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.statistics.repository.StatisticsRepository;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.common.config.TimeConfig;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통계·관리자 대시보드 (F-4.11-11).
 *
 * <h2>사전집계 테이블을 두지 않는다</h2>
 * 시트 지침이 <i>"대용량 실시간 집계 금지"</i>인데, 금지하려는 건 <b>요청 시점에 원시
 * 로그를 훑는 것</b>이다. 출결·순공은 이미 새벽 배치가 {@code attendance_daily_status}에
 * 학생×하루 1행으로 집계해두고, 통계는 그 행을 더할 뿐이다 — 태깅 원장은 안 본다.
 *
 * <p>여기서 집계 테이블을 또 두면 배치가 하나 늘고, <b>그게 안 돌면 통계가 조용히
 * 멈춘다</b>(어제 숫자가 그대로 보인다). 실측으로 느려지면 그때 넣는다.
 *
 * <h2>★ 지점 스코프는 서버가 강제한다</h2>
 * {@code academyId}를 요청에서 그대로 받지 않는다 — 전 지점 권한이 없는 사용자가
 * 값을 바꿔 보내면 다른 지점 통계가 새어나간다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatisticsService {

    /** 순공 랭킹 기본 인원. 대시보드가 상위 몇 명만 보여준다. */
    private static final int RANKING_SIZE = 10;

    private final StatisticsRepository repository;
    private final java.time.Clock clock;

    /**
     * 조회 지점 결정.
     *
     * <p><b>전 지점 권한자만 {@code null}(전체 합계)을 쓸 수 있다.</b> 지점 관리자가
     * {@code academyId}를 비우면 자기 지점으로 고정되고, 남의 지점을 넣으면 거부된다.
     *
     * <p>통계는 "전 지점 합계"가 의미 있는 화면이라 {@code require~}가 아니라
     * {@link AuthPrincipal#resolveAcademyScope(Long)}를 쓴다.
     *
     * <p>⚠️ 예전에는 {@code requested}를 권한 검사까지 해놓고 <b>정작 쓰지 않고</b>
     * 자기 지점을 돌려줬다 — 값이 같아 결과는 맞았지만 의도가 드러나지 않았다.
     */
    private Long resolveScope(AuthPrincipal me, Long requested) {
        return me.resolveAcademyScope(requested);
    }

    /** 대시보드 한 번에. 화면이 카드 여러 개를 한 화면에 띄운다. */
    /**
     * 반별·계열별·월별 집계 (F-C-2 학원생 현황).
     *
     * <p><b>대시보드 개요와 축이 다르다.</b> {@code overview}는 "지금 전체가 어떤가"이고
     * 이쪽은 "어디에 몇 명인가"다 — 전 원생을 내려받아 화면에서 세면 원생 수가 늘수록
     * 그대로 느려진다.
     */
    @Transactional(readOnly = true)
    public List<GroupRow> group(AuthPrincipal me, Long academyId, short year,
                                GroupBy groupBy, LocalDate asOf) {
        Long scope = resolveScope(me, academyId);
        return switch (groupBy) {
            case CLASS -> byClass(scope, year);
            case TRACK -> byTrack(scope, year);
            case MONTH -> byMonth(scope, year, asOf);
        };
    }

    private List<GroupRow> byClass(Long scope, short year) {
        return repository.countByClass(scope, year).stream()
                .map(r -> {
                    Short capacity = (Short) r[2];
                    long count = ((Number) r[3]).longValue();
                    return new GroupRow(String.valueOf(r[0]), (String) r[1],
                            count,
                            capacity == null ? null : (long) capacity,
                            // 정원이 없으면 충원율을 내지 않는다 — 0으로 두면 화면이
                            // "아무도 없음"으로, 100으로 두면 "만석"으로 잘못 읽는다
                            capacity == null || capacity == 0 ? null
                                    : Math.round(count * 100.0 / capacity),
                            null);
                })
                .toList();
    }

    private List<GroupRow> byTrack(Long scope, short year) {
        return repository.countByTrack(scope, year).stream()
                .map(r -> new GroupRow(
                        r[0] == null ? "UNASSIGNED" : String.valueOf(r[0]),
                        // 계열이 안 정해진 학생을 빼면 합계가 전체 인원과 안 맞는다
                        r[0] == null ? "미지정" : String.valueOf(r[0]),
                        ((Number) r[1]).longValue(), null, null, null))
                .toList();
    }

    /**
     * 월별 추이 — 그 달 <b>말일 기준</b> 재원 인원과 전월 대비 증감.
     *
     * <p>이번 달은 말일이 아직 안 왔으므로 <b>오늘 기준</b>으로 센다. 말일로 세면
     * 아직 오지 않은 퇴원까지 반영돼 이번 달만 값이 튄다.
     */
    private List<GroupRow> byMonth(Long scope, short year, LocalDate asOf) {
        LocalDate today = asOf == null ? LocalDate.now(clock) : asOf;
        List<GroupRow> rows = new java.util.ArrayList<>();
        Long previous = null;

        for (int month = 1; month <= 12; month++) {
            java.time.YearMonth ym = java.time.YearMonth.of(year, month);
            if (ym.isAfter(java.time.YearMonth.from(today))) {
                break;
            }
            LocalDate at = ym.equals(java.time.YearMonth.from(today)) ? today : ym.atEndOfMonth();
            long count = repository.countEnrolledAt(scope, year, at);

            rows.add(new GroupRow("%d-%02d".formatted(year, month), "%d월".formatted(month),
                    count, null, null, previous == null ? null : count - previous));
            previous = count;
        }
        return rows;
    }

    /**
     * @param key      반 id · 계열 코드 · {@code yyyy-MM}. 화면이 정렬·연결에 쓴다
     * @param capacity 반에만 있다. 정원이 안 정해진 반은 비어 있다
     * @param fillRate 충원율(%). 정원이 없으면 비어 있다 — 0이나 100으로 채우면 오독된다
     * @param delta    전월 대비 증감. 월별에만 있고, 첫 달은 비교 대상이 없어 비어 있다
     */
    public record GroupRow(String key, String label, long count,
                           Long capacity, Long fillRate, Long delta) {
    }

    /** 집계 축. */
    public enum GroupBy {
        CLASS, TRACK, MONTH
    }

    public Overview overview(AuthPrincipal me, Long academyId, short year,
                             LocalDate from, LocalDate to) {
        Long scope = resolveScope(me, academyId);
        return new Overview(
                students(scope, year),
                attendance(scope, from, to),
                studyTime(scope, from, to),
                penalty(scope, from, to),
                meals(scope, from, to),
                revenue(scope, year));
    }

    // ── 재원생 ────────────────────────────────────────────────

    public StudentStat students(Long academyId, short year) {
        Map<EnrollmentStatus, Long> byStatus = new EnumMap<>(EnrollmentStatus.class);
        repository.countByEnrollmentStatus(academyId, year)
                .forEach(r -> byStatus.put((EnrollmentStatus) r[0], (Long) r[1]));

        long enrolled = byStatus.getOrDefault(EnrollmentStatus.ENROLLED, 0L);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new StudentStat(enrolled, total, byStatus);
    }

    // ── 출결 ──────────────────────────────────────────────────

    /**
     * 출결 집계.
     *
     * <p><b>출석률은 확정된 날만 센다</b> — 미확정 오늘을 분모에 넣으면 매일 아침
     * 떨어졌다가 새벽에 회복되는 것처럼 보인다. 지각·조퇴는 출석으로 센다.
     * 앱 홈과 같은 규칙이다.
     */
    public AttendanceStat attendance(Long academyId, LocalDate from, LocalDate to) {
        Map<DailyStatus, Long> byStatus = new EnumMap<>(DailyStatus.class);
        repository.countByDailyStatus(academyId, from, to)
                .forEach(r -> byStatus.put((DailyStatus) r[0], (Long) r[1]));

        long confirmed = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long absent = byStatus.getOrDefault(DailyStatus.ABSENT, 0L);
        Integer rate = confirmed == 0
                ? null
                : (int) Math.round((confirmed - absent) * 100.0 / confirmed);

        return new AttendanceStat(confirmed, rate, byStatus);
    }

    // ── 순공시간 ──────────────────────────────────────────────

    public StudyTimeStat studyTime(Long academyId, LocalDate from, LocalDate to) {
        Object[] row = repository.studyTimeTotals(academyId, from, to).get(0);
        long totalMinutes = ((Number) row[0]).longValue();
        double avgMinutes = ((Number) row[1]).doubleValue();
        long countedDays = ((Number) row[2]).longValue();

        List<RankingRow> ranking = repository
                .studyTimeRanking(academyId, from, to, PageRequest.of(0, RANKING_SIZE))
                .stream()
                .map(r -> new RankingRow((String) r[0], (String) r[1],
                        ((Number) r[2]).longValue()))
                .toList();

        return new StudyTimeStat(totalMinutes, (int) Math.round(avgMinutes),
                countedDays, ranking);
    }

    /**
     * 순공시간 랭킹 — 엑셀 Export용이라 인원을 지정한다.
     *
     * <p>대시보드는 상위 {@value #RANKING_SIZE}명만 보지만, 엑셀은 전체를 받아
     * 상담 자료로 쓴다.
     */
    public List<RankingRow> studyTimeRanking(AuthPrincipal me, Long academyId,
                                             LocalDate from, LocalDate to, int size) {
        Long scope = resolveScope(me, academyId);
        return repository.studyTimeRanking(scope, from, to, PageRequest.of(0, size))
                .stream()
                .map(r -> new RankingRow((String) r[0], (String) r[1],
                        ((Number) r[2]).longValue()))
                .toList();
    }

    // ── 상벌점 ────────────────────────────────────────────────

    /** 벌점은 음수로 저장돼 있다 — 부호로 상점·벌점을 가른다. */
    public PenaltyStat penalty(Long academyId, LocalDate from, LocalDate to) {
        Object[] row = repository.penaltyTotals(academyId,
                from.atStartOfDay(TimeConfig.KST).toInstant(),
                to.plusDays(1).atStartOfDay(TimeConfig.KST).toInstant()).get(0);

        return new PenaltyStat(((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue());
    }

    // ── 급식 ──────────────────────────────────────────────────

    public MealStat meals(Long academyId, LocalDate from, LocalDate to) {
        Map<MealType, Long> applied = new EnumMap<>(MealType.class);
        repository.mealCounts(academyId, from, to)
                .forEach(r -> applied.put((MealType) r[0], (Long) r[1]));

        Map<CancelPath, Long> canceled = new EnumMap<>(CancelPath.class);
        repository.mealCancelCounts(academyId, from, to)
                .forEach(r -> canceled.put((CancelPath) r[0], (Long) r[1]));

        long total = applied.values().stream().mapToLong(Long::longValue).sum();
        return new MealStat(total, applied, canceled);
    }

    // ── 매출 ──────────────────────────────────────────────────

    /**
     * 유형별 매출.
     *
     * <p><b>결제(PG)가 아직 없다</b> — 수납은 수기 기록이라 실제 매출과 다를 수 있다.
     * 청구액·수납액·미납액을 그대로 내리고 판단은 화면에 맡긴다.
     */
    public RevenueStat revenue(Long academyId, short year) {
        Map<BillingType, RevenueRow> byType = new LinkedHashMap<>();
        long billed = 0;
        long received = 0;

        for (Object[] r : repository.revenueByType(academyId, year)) {
            BillingType type = (BillingType) r[0];
            long typeBilled = ((Number) r[1]).longValue();
            long typeReceived = ((Number) r[2]).longValue();
            long count = ((Number) r[3]).longValue();

            byType.put(type, new RevenueRow(typeBilled, typeReceived,
                    Math.max(0, typeBilled - typeReceived), count));
            billed += typeBilled;
            received += typeReceived;
        }
        return new RevenueStat(billed, received, Math.max(0, billed - received), byType);
    }

    // ── 응답 ──────────────────────────────────────────────────

    public record Overview(StudentStat students, AttendanceStat attendance,
                           StudyTimeStat studyTime, PenaltyStat penalty,
                           MealStat meals, RevenueStat revenue) {
    }

    /** @param enrolled 재원생. {@code total}은 휴원·퇴원까지 포함한 등록 건 전체다 */
    public record StudentStat(long enrolled, long total, Map<EnrollmentStatus, Long> byStatus) {
    }

    /** @param attendanceRate 확정된 날이 없으면 {@code null} — 0%면 결석한 것처럼 보인다 */
    public record AttendanceStat(long confirmedDays, Integer attendanceRate,
                                 Map<DailyStatus, Long> byStatus) {
    }

    public record StudyTimeStat(long totalMinutes, int avgMinutesPerDay,
                                long countedDays, List<RankingRow> ranking) {
    }

    public record RankingRow(String studentNo, String studentName, long studyMinutes) {
    }

    /** @param demeritPoints 음수다 — 저장된 부호를 그대로 내린다 */
    public record PenaltyStat(long meritPoints, long demeritPoints, long count) {
    }

    public record MealStat(long appliedTotal, Map<MealType, Long> applied,
                           Map<CancelPath, Long> canceled) {
    }

    public record RevenueStat(long billedAmount, long receivedAmount, long unpaidAmount,
                              Map<BillingType, RevenueRow> byType) {
    }

    public record RevenueRow(long billedAmount, long receivedAmount, long unpaidAmount,
                             long count) {
    }
}
