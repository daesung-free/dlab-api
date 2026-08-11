package com.dlab.domain.statistics.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
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

    /**
     * 조회 지점 결정.
     *
     * <p><b>전 지점 권한자만 {@code null}(전체)을 쓸 수 있다.</b> 지점 관리자가
     * {@code academyId}를 비우거나 남의 지점을 넣어도 자기 지점으로 고정된다.
     */
    private Long resolveScope(AuthPrincipal me, Long requested) {
        if (me.allAcademy()) {
            return requested;   // null이면 전 지점
        }
        if (requested != null && !me.canAccessAcademy(requested)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        Long own = me.academyScopeFilter();
        if (own == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        return own;
    }

    /** 대시보드 한 번에. 화면이 카드 여러 개를 한 화면에 띄운다. */
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
