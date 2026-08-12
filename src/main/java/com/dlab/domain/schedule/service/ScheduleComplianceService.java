package com.dlab.domain.schedule.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyRuleEngine;
import com.dlab.domain.schedule.entity.RegularScheduleItem;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정기일정 인정 판정 (F-4.1-7).
 *
 * <h2>규칙</h2>
 * <b>{@code ABS(등록 시각 − 실제 출입 시각) ≥ 30분 → 미인정}</b> (요구사항 3시트).
 * 미인정이면 그 외출은 정기일정으로 보호받지 못하고 벌점 대상이 된다.
 *
 * <h2>★ 나간 시각만 본다</h2>
 * 시트가 말하는 "출입"을 나감·돌아옴 둘 다로 읽으면, 현강이 늦게 끝나 30분 늦게 복귀한
 * 학생이 <b>제시간에 나갔는데도</b> 미인정이 된다. 학원이 통제할 수 있는 건 나가는 시각이고,
 * 돌아오는 시각은 외부 사정이다. 복귀 지연을 따로 벌하려면 별도 규칙으로 둘 일이다.
 *
 * <h2>★ 판정 결과를 저장하지 않는다</h2>
 * 원천(태깅 로그 · 일정)이 그대로 있어 언제든 다시 계산할 수 있고, 저장해두면 사유가
 * 뒤늦게 승인되거나 관리자가 태깅을 정정했을 때 <b>판정만 옛 값으로 남는다</b>.
 * 벌점은 다르다 — 그건 부여 시점 사실이라 {@code penalty_point}에 남는다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleComplianceService {

    /** 미인정 임계. 요구사항 3시트가 지정한 값이다. */
    private static final Duration TOLERANCE = Duration.ofMinutes(30);

    /** 벌점 규칙이 매칭할 조건값. 규칙에서 조건을 비우면 전부 걸린다. */
    private static final String NOT_RECOGNIZED = "NOT_RECOGNIZED";

    /** 나가는 태깅. 사유외출도 나간 것이다. */
    private static final Set<AttendanceEventType> LEAVING = EnumSet.of(
            AttendanceEventType.OUTING,
            AttendanceEventType.EXCUSED_OUTING,
            AttendanceEventType.EARLY_LEAVE);

    private final RegularScheduleService scheduleService;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final PenaltyRuleEngine penaltyRuleEngine;

    /**
     * 그날 일정별 인정 여부.
     *
     * <p>일정이 없으면 빈 목록이다 — 정기일정이 없는 학생은 판정 대상이 아니다.
     */
    @Transactional(readOnly = true)
    public List<Verdict> judge(StudentEnrollment enrollment, LocalDate date) {
        List<RegularScheduleItem> items = scheduleService.itemsOn(enrollment.getId(), date);
        if (items.isEmpty()) {
            return List.of();
        }

        List<LocalTime> departures = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(enrollment.getId(), date)
                .stream()
                .filter(log -> LEAVING.contains(log.getEventType()))
                .map(this::timeOf)
                .toList();

        List<Verdict> verdicts = new ArrayList<>();
        for (RegularScheduleItem item : items) {
            verdicts.add(verdict(item, departures));
        }
        return verdicts;
    }

    /**
     * 판정 + 미인정이면 벌점 트리거.
     *
     * <p><b>규칙이 없으면 아무 일도 일어나지 않는다</b> — 트리거→점수 매핑(I-5)이
     * 미확정이라 {@code penalty_rule} 행이 생기고 {@code active}가 켜질 때까지
     * 엔진이 그냥 통과한다. 멱등키가 {@code 학생:일자:규칙}이라 다시 돌려도 중복되지 않는다.
     */
    @Transactional
    public List<Verdict> judgeAndPenalize(StudentEnrollment enrollment, LocalDate date) {
        List<Verdict> verdicts = judge(enrollment, date);

        if (verdicts.stream().anyMatch(v -> !v.recognized())) {
            penaltyRuleEngine.apply(enrollment, PenaltyTriggerType.REGULAR_SCHEDULE,
                    NOT_RECOGNIZED, date);
        }
        return verdicts;
    }

    // ─────────────────────────────────────────────────────────

    /**
     * 등록 시각에 가장 가까운 외출 태깅으로 판정한다.
     *
     * <p><b>아예 안 나갔으면 미인정이다.</b> 나가지도 않고 일정만 등록해두면 그 시간이
     * 인정되는 셈이라, 등록만 해놓고 자리를 비우는 통로가 된다.
     */
    private Verdict verdict(RegularScheduleItem item, List<LocalTime> departures) {
        LocalTime nearest = departures.stream()
                .min((a, b) -> Long.compare(gap(item.getStartTime(), a),
                        gap(item.getStartTime(), b)))
                .orElse(null);

        if (nearest == null) {
            return new Verdict(item, null, null, false);
        }
        long minutes = gap(item.getStartTime(), nearest);
        return new Verdict(item, nearest, minutes,
                minutes < TOLERANCE.toMinutes());
    }

    private long gap(LocalTime scheduled, LocalTime actual) {
        return Math.abs(Duration.between(scheduled, actual).toMinutes());
    }

    /** <b>{@code systemDefault()}를 쓰지 말 것</b> — 서버가 UTC면 시각이 9시간 밀린다. */
    private LocalTime timeOf(AttendanceTaggingLog log) {
        return log.getRecordedAt().atZone(TimeConfig.KST).toLocalTime();
    }

    /**
     * @param actualDeparture 실제 외출 태깅 시각. 안 나갔으면 {@code null}
     * @param gapMinutes      등록 시각과의 차이(분). 안 나갔으면 {@code null}
     * @param recognized      인정 여부. 30분 이상 어긋나거나 안 나갔으면 {@code false}
     */
    public record Verdict(RegularScheduleItem item, LocalTime actualDeparture,
                          Long gapMinutes, boolean recognized) {
    }
}
