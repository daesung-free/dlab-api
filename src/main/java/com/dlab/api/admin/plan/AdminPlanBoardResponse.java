package com.dlab.api.admin.plan;

import com.dlab.domain.plan.service.LearningPlanBoardService;

public final class AdminPlanBoardResponse {

    private AdminPlanBoardResponse() {}

    /**
     * 반·지점 학습계획 현황 한 줄.
     *
     * <p><b>계획을 한 줄도 안 쓴 학생도 이 목록에 들어온다.</b> 그 학생은 계획 관련 값이
     * 전부 0이고 {@code missingDays}가 조회 기간 전체와 같다 — 화면의 "미작성"이 이 행이다.
     *
     * <p><b>{@code completionRate}는 분(minute) 기준이다.</b> 줄 수로 재면 10분짜리와
     * 3시간짜리가 같은 무게가 되어, 짧은 항목을 여러 개 체크한 학생이 더 성실해 보인다.
     * 줄 수({@code totalItems}/{@code doneItems})도 함께 내리므로 화면이 필요하면 그쪽을 쓴다.
     *
     * @param doneMinutes 이행(O)으로 체크된 <b>계획</b> 시간이다. 출결 기반 순공시간과 다른
     *                    값이므로 같은 자리에 섞어 보여주면 안 된다
     * @param homeroomTeacherName 담임. <b>반에 붙어 있어</b> 반 미배정 학생은 비어 있다.
     *                            담임이 자기 반 미작성자를 보는 화면이라 필요하다
     * @param missingDays 계획을 한 줄도 안 쓴 날 수. <b>학습계획 차단일은 빠진 값</b>이다
     *                    ({@code holiday.plan_excluded}). 등록된 차단일이 없으면 달력 일수 기준이다
     * @param countedDays 계획을 써야 했던 날 수 — 화면의 분모
     */
    public record LearningPlanBoardRow(
            Long enrollmentId,
            String studentNo,
            String studentName,
            Long classId,
            String className,
            Long homeroomTeacherId,
            String homeroomTeacherName,
            int plannedMinutes,
            int doneMinutes,
            int completionRate,
            long totalItems,
            long doneItems,
            long plannedDays,
            long missingDays,
            long countedDays) {

        public static LearningPlanBoardRow from(LearningPlanBoardService.BoardRow row) {
            return new LearningPlanBoardRow(
                    row.enrollmentId(), row.studentNo(), row.studentName(),
                    row.classId(), row.className(),
                    row.homeroomTeacherId(), row.homeroomTeacherName(),
                    row.plannedMinutes(), row.doneMinutes(), row.completionRate(),
                    row.totalItems(), row.doneItems(),
                    row.plannedDays(), row.missingDays(), row.countedDays());
        }
    }
}
