package com.dlab.api.app.report;

import com.dlab.api.app.attendance.AttendanceResponse;
import com.dlab.domain.report.service.DailyReportService;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import java.time.LocalDate;
import java.util.List;

/**
 * Daily Report 응답 (A-3).
 *
 * <p>태깅 이벤트·사유출결은 <b>출결 응답의 형태를 그대로 쓴다</b> — 같은 데이터를 두 화면이
 * 다른 모양으로 받으면 앱이 파서를 두 벌 갖게 된다.
 */
public final class DailyReportResponse {

    private DailyReportResponse() {
    }

    /**
     * 하루 상세.
     *
     * @param finalStatus  확정 전이면 {@code null} — "확정 전"과 "결석"은 다르다
     * @param studyMinutes 확정 전이면 {@code null}
     * @param qnaCount     지금은 <b>오프라인 상담실 예약</b> 건수다(온라인 QnA 미구현)
     */
    public record Day(LocalDate date, String finalStatus, boolean excused,
                      Integer studyMinutes,
                      List<AttendanceResponse.Event> events,
                      List<AttendanceResponse.AbsenceReasonRow> reasons,
                      List<Routine> routines, int qnaCount, String selfFeedback) {

        public static Day from(DailyReportService.DayReport r) {
            return new Day(r.date(),
                    r.finalStatus() == null ? null : r.finalStatus().name(),
                    r.excused(),
                    r.studyMinutes(),
                    r.events().stream().map(AttendanceResponse.Event::from).toList(),
                    r.reasons().stream().map(AttendanceResponse.AbsenceReasonRow::from).toList(),
                    r.routines().stream().map(Routine::from).toList(),
                    r.qnaCount(),
                    r.selfFeedback());
        }
    }

    /**
     * 데일리테스트 한 건.
     *
     * <p><b>검수 전 가채점 점수는 내리지 않는다</b> — 학생이 매긴 점수가 확정처럼 보이면
     * 교사 검수 결과와 달라졌을 때 이의가 붙는다.
     */
    public record Routine(Long routineId, String name, String subject, String status,
                          Short score, Short maxScore) {

        static Routine from(DailyRoutineResult r) {
            return new Routine(r.getRoutine().getId(), r.getRoutine().getName(),
                    r.getRoutine().getSubject(), r.getStatus().name(),
                    r.getReviewedScore(), r.getRoutine().getMaxScore());
        }
    }

    /**
     * 달력(월간).
     *
     * @param attendanceRate 확정된 날이 없으면 {@code null} — 0%면 결석한 것처럼 보인다
     */
    public record Month(int year, int month, int studyMinutes, Integer attendanceRate,
                        int confirmedDays, List<DayCell> days) {

        public static Month from(DailyReportService.MonthReport r) {
            return new Month(r.year(), r.month(), r.studyMinutes(), r.attendanceRate(),
                    r.confirmedDays(), r.days().stream().map(DayCell::from).toList());
        }
    }

    /** 달력 한 칸 — 앱이 순공/출결/질/R 네 가지를 점으로 찍는다. */
    public record DayCell(LocalDate date, String finalStatus, boolean excused,
                          Integer studyMinutes, int routineCount, int qnaCount,
                          boolean hasFeedback) {

        static DayCell from(DailyReportService.DayCell c) {
            return new DayCell(c.date(),
                    c.finalStatus() == null ? null : c.finalStatus().name(),
                    c.excused(), c.studyMinutes(),
                    c.routineCount(), c.qnaCount(), c.hasFeedback());
        }
    }
}
