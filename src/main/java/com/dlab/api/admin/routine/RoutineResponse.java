package com.dlab.api.admin.routine;

import com.dlab.domain.routine.entity.DailyRoutine;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.routine.service.DailyRoutineService;

import java.time.Instant;
import java.time.LocalDate;

/** 데일리 루틴 응답 DTO. */
public final class RoutineResponse {

    private RoutineResponse() {
    }

    /**
     * @param classId     {@code null}이면 지점 공통 루틴
     * @param maxScore    0이면 점수 없이 완료/미완료만 본다
     * @param recommended 앱에서 강조 표시된다(A-11)
     */
    public record RoutineDetail(Long id, int year, int month, Long classId, String className,
                         String name, String subject, int maxScore, boolean recommended,
                         int sortOrder, Long copiedFromId) {

        public static RoutineDetail from(DailyRoutine r) {
            return new RoutineDetail(r.getId(), r.getYear(), r.getMonth(),
                    r.getClassMaster() == null ? null : r.getClassMaster().getId(),
                    r.getClassMaster() == null ? null : r.getClassMaster().getName(),
                    r.getName(), r.getSubject(), r.getMaxScore(), r.isRecommended(),
                    r.getSortOrder(), r.getCopiedFromId());
        }
    }

    /**
     * 반 단위 그리드 한 줄.
     *
     * <p>{@code id}가 {@code null}이면 <b>아직 저장된 적 없는 행</b>이다 —
     * 첫 조회에서 대상 학생 전원을 빈 줄로 내리기 때문이다.
     *
     * @param selfScore     학생 가채점
     * @param reviewedScore 교사 검수 점수. <b>둘을 분리 보관</b>한다
     */
    public record GridRow(Long id, Long enrollmentId, String studentNo, String studentName,
                          String status, Integer selfScore, Integer reviewedScore,
                          String memo, Instant reviewedAt) {

        public static GridRow from(DailyRoutineResult r) {
            return new GridRow(r.getId(),
                    r.getEnrollment().getId(),
                    r.getEnrollment().getStudentNo(),
                    r.getEnrollment().getStudent().getName(),
                    r.getStatus().name(),
                    r.getSelfScore() == null ? null : (int) r.getSelfScore(),
                    r.getReviewedScore() == null ? null : (int) r.getReviewedScore(),
                    r.getMemo(), r.getReviewedAt());
        }
    }

    /**
     * 학생 앱 한 줄 (A-11).
     *
     * @param score <b>공개된 것만</b> 내려간다. 검수 중인 점수가 새어나가면
     *              교사가 고치기 전 값이 학생에게 보인다
     */
    public record Today(Long routineId, String name, String subject, int maxScore,
                        boolean recommended, String status, Integer score) {

        public static Today from(DailyRoutineService.TodayRoutine t) {
            DailyRoutine routine = t.routine();
            Short score = t.visibleScore();
            return new Today(routine.getId(), routine.getName(), routine.getSubject(),
                    routine.getMaxScore(), routine.isRecommended(),
                    t.status().name(), score == null ? null : (int) score);
        }
    }

    /** 결과 이력 한 줄 — 기간 조회. */
    public record History(LocalDate date, Long routineId, String name, String status,
                          Integer reviewedScore, int maxScore) {

        public static History from(DailyRoutineResult r) {
            return new History(r.getResultDate(), r.getRoutine().getId(),
                    r.getRoutine().getName(), r.getStatus().name(),
                    r.getReviewedScore() == null ? null : (int) r.getReviewedScore(),
                    r.getRoutine().getMaxScore());
        }
    }
}
