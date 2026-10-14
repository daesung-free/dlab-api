package com.dlab.api.admin.report;

import com.dlab.domain.report.service.DailyReportBoardService;

public final class AdminDailyReportResponse {

    private AdminDailyReportResponse() {
    }

    /**
     * Daily Report 학생별 집계 한 줄 (F-4.11-6).
     *
     * <p><b>등수는 지점·기간 전체 기준이다</b> — 반 필터를 걸거나 페이지를 넘겨도 같은
     * 학생의 등수는 바뀌지 않는다. 화면이 페이지 안에서 다시 세면 2페이지 첫 줄이
     * 또 1등이 된다.
     *
     * @param studentName 상위 관리자가 아니면 가려져 있다({@code masked})
     * @param masked      이름이 가려졌는지. 없으면 화면이 "이름이 잘못 저장됐다"로 오인한다
     * @param studyMinutes 순공시간(재실 − 외출 − 급식 − 쉬는시간) 합계.
     *                     <b>집계한 날이 없으면 비어 있다</b> — 0이 아니다
     * @param presenceMinutes 재실시간 합계
     * @param concentration ⚠️ <b>항상 비어 있다 — 정의 미확정.</b> 산식이 요구사항에 없어
     *                     서버가 만들지 않는다. 화면도 이 값을 숫자로 그리지 말 것
     * @param missingCheckOutDays 하원 태깅이 없어 <b>집계하지 않은</b> 날 수.
     *                     직원이 하원 시각을 수기 입력하면 사라진다 — 「0분」이 아니라
     *                     「미입력」이므로 화면도 구분해 표시해야 한다
     * @param inProgressDays 오늘 아직 학원에 있는 날. 잠정값으로 집계에 들어가 있다
     * @param routineDone   루틴 이행(O) 건수. <b>O/X 2단계다</b>(I-19) — 부분 이행이 없다
     * @param routineCompletionRate 이행률(%). 판정된 건이 없으면 비어 있다 —
     *                     "루틴이 없던 기간"과 "0% 이행"은 다르다
     * @param studyRank    순공시간 등수. 동점은 같은 등수이고 다음은 건너뛴다(1, 1, 3) —
     *                     앱 순공 랭킹과 같은 규칙이다. 집계값이 없으면 비어 있다
     */
    public record DailyReportBoardRow(
            Long enrollmentId,
            String studentNo,
            String studentName,
            boolean masked,
            Long classId,
            String className,
            Long homeroomTeacherId,
            String homeroomTeacherName,
            Integer studyMinutes,
            Integer presenceMinutes,
            Integer concentration,
            long attendedDays,
            long measuredDays,
            long missingCheckOutDays,
            long inProgressDays,
            long routineDone,
            long routineMissed,
            long routineJudged,
            Integer routineCompletionRate,
            Integer studyRank,
            Integer presenceRank,
            Integer routineRank) {

        public static DailyReportBoardRow from(DailyReportBoardService.BoardRow row) {
            return new DailyReportBoardRow(
                    row.enrollmentId(), row.studentNo(), row.studentName(), row.masked(),
                    row.classId(), row.className(),
                    row.homeroomTeacherId(), row.homeroomTeacherName(),
                    row.studyMinutes(), row.presenceMinutes(), row.concentration(),
                    row.attendedDays(), row.measuredDays(),
                    row.missingCheckOutDays(), row.inProgressDays(),
                    row.routineDone(), row.routineMissed(), row.routineJudged(),
                    row.routineCompletionRate(),
                    row.studyRank(), row.presenceRank(), row.routineRank());
        }
    }
}
