package com.dlab.api.app.plan;

import com.dlab.domain.plan.entity.LearningPlan;
import com.dlab.domain.plan.entity.LearningPlanItem;
import com.dlab.domain.plan.entity.LearningPlanOption;
import com.dlab.domain.plan.entity.LearningPlanOptionType;
import com.dlab.domain.plan.service.LearningPlanService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class PlanResponse {

    private PlanResponse() {}

    /**
     * 하루치.
     *
     * <p>{@code doneCount}/{@code totalCount}를 함께 내린다 — 앱이 "완료 X/Y 진행률"을
     * 그리는데, 항목을 세게 하면 삭제된 줄을 걸러야 하는 규칙이 앱에도 생긴다.
     */
    public record Day(
            Long id,
            LocalDate date,
            List<Item> items,
            int plannedMinutes,
            int doneMinutes,
            long doneCount,
            int totalCount,
            boolean copied) {

        public static Day from(LearningPlan plan) {
            List<Item> items = plan.activeItems().stream().map(Item::from).toList();
            return new Day(plan.getId(), plan.getPlanDate(), items,
                    plan.totalMinutes(), plan.doneMinutes(), plan.doneCount(), items.size(),
                    plan.getCopiedFromId() != null);
        }

        /** 계획이 없는 날. 앱이 빈 화면을 그릴 때 날짜만 있으면 된다. */
        public static Day empty(LocalDate date) {
            return new Day(null, date, List.of(), 0, 0, 0, 0, false);
        }
    }

    public record Item(
            Long id,
            short sequence,
            LocalTime startTime,
            LocalTime endTime,
            short durationMinutes,
            Long subjectOptionId,
            String subject,
            Long studyTypeOptionId,
            String studyType,
            String material,
            boolean done,
            Instant doneAt) {

        public static Item from(LearningPlanItem item) {
            return new Item(item.getId(), item.getSequence(), item.getStartTime(), item.endTime(),
                    item.getDurationMinutes(),
                    item.getSubject().getId(), item.getSubject().getLabel(),
                    item.getStudyType().getId(), item.getStudyType().getLabel(),
                    item.getMaterial(), item.isDone(), item.getDoneAt());
        }
    }

    /** 주간 뷰. 계획이 없는 날도 빈 날로 채워 7일을 온전히 내린다. */
    public record Week(LocalDate weekStart, List<Day> days) {

        public static Week of(LocalDate weekStart, List<LearningPlan> plans) {
            List<Day> days = new java.util.ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate date = weekStart.plusDays(i);
                days.add(plans.stream()
                        .filter(p -> p.getPlanDate().equals(date))
                        .findFirst().map(Day::from)
                        .orElseGet(() -> Day.empty(date)));
            }
            return new Week(weekStart, days);
        }
    }

    public record Option(Long id, LearningPlanOptionType optionType, String label, short sortOrder) {
        public static Option from(LearningPlanOption o) {
            return new Option(o.getId(), o.getOptionType(), o.getLabel(), o.getSortOrder());
        }
    }

    public record Statistics(
            LocalDate from,
            LocalDate to,
            int plannedMinutes,
            int doneMinutes,
            long totalItems,
            long doneItems,
            List<Composition> bySubject,
            List<Composition> byStudyType) {

        public static Statistics from(LocalDate from, LocalDate to,
                                      LearningPlanService.Statistics s) {
            int total = s.plannedMinutes();
            return new Statistics(from, to, s.plannedMinutes(), s.doneMinutes(),
                    s.totalItems(), s.doneItems(),
                    s.bySubject().stream().map(c -> Composition.from(c, total)).toList(),
                    s.byStudyType().stream().map(c -> Composition.from(c, total)).toList());
        }
    }

    /**
     * 비율은 서버가 계산해 내린다 — 앱과 관리자 웹이 각자 나누면 반올림이 갈려
     * 같은 학생의 파이가 화면마다 다르게 보인다.
     */
    public record Composition(Long optionId, String label, int plannedMinutes, int doneMinutes,
                              int plannedPercent) {

        static Composition from(LearningPlanService.Composition c, int totalPlannedMinutes) {
            int percent = totalPlannedMinutes == 0
                    ? 0
                    : (int) Math.round(c.plannedMinutes() * 100.0 / totalPlannedMinutes);
            return new Composition(c.optionId(), c.label(), c.plannedMinutes(), c.doneMinutes(),
                    percent);
        }
    }
}
