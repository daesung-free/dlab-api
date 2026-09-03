package com.dlab.api.admin.routine;

import com.dlab.domain.routine.entity.DailyRoutine;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.routine.service.DailyRoutineService;

import java.time.Instant;
import java.util.List;
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
     * 학생 × 루틴 매트릭스 (화면 한 표).
     *
     * @param routines 컬럼 순서. 화면이 이 순서대로 헤더를 그린다
     */
    public record DayMatrix(List<RoutineRef> routines, List<MatrixRow> rows) {

        public static DayMatrix from(com.dlab.domain.routine.service.DailyRoutineService.DayMatrix m) {
            return new DayMatrix(
                    m.routines().stream().map(RoutineRef::from).toList(),
                    m.rows().stream().map(MatrixRow::from).toList());
        }
    }

    public record RoutineRef(Long id, String name, String subject, int maxScore) {

        static RoutineRef from(com.dlab.domain.routine.entity.DailyRoutine r) {
            return new RoutineRef(r.getId(), r.getName(), r.getSubject(), r.getMaxScore());
        }
    }

    /** @param cells 그 학생이 <b>대상인 루틴만</b> 들어온다 — 반 지정 루틴이 섞이기 때문이다 */
    public record MatrixRow(Long enrollmentId, String studentNo, String studentName,
                            String className, List<MatrixCell> cells) {

        static MatrixRow from(com.dlab.domain.routine.service.DailyRoutineService.MatrixRow r) {
            return new MatrixRow(r.enrollmentId(), r.studentNo(), r.studentName(),
                    r.className(), r.cells().stream().map(MatrixCell::from).toList());
        }
    }

    /**
     * @param status {@code null}이면 <b>아직 입력하지 않은 칸</b>이다. 행이 아예 없는 것과
     *               다르다 — 없는 학생은 애초에 그 루틴 대상이 아니다
     */
    public record MatrixCell(Long routineId, Long resultId, String status,
                             Integer selfScore, Integer reviewedScore, String memo) {

        static MatrixCell from(com.dlab.domain.routine.service.DailyRoutineService.MatrixCell c) {
            var r = c.result();
            if (r == null) {
                return new MatrixCell(c.routineId(), null, null, null, null, null);
            }
            return new MatrixCell(c.routineId(), r.getId(), r.getStatus().name(),
                    r.getSelfScore() == null ? null : (int) r.getSelfScore(),
                    r.getReviewedScore() == null ? null : (int) r.getReviewedScore(),
                    r.getMemo());
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
