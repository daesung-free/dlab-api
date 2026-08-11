package com.dlab.domain.report.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.service.AttendanceQueryService;
import com.dlab.domain.qna.repository.QnaOfflineReservationRepository;
import com.dlab.domain.report.entity.DailyReportFeedback;
import com.dlab.domain.report.repository.DailyReportFeedbackRepository;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.routine.service.DailyRoutineService;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily Report 집계 (F-4.11-6) — 앱 홈 대시보드(A-3)의 데이터 원천.
 *
 * <h2>새로 계산하는 게 거의 없다</h2>
 * 출결·순공은 확정 배치가 넣어둔 {@code attendance_daily_status}를, 데일리테스트는
 * 루틴 검수 결과를 그대로 읽는다. 여기서 다시 계산하면 <b>같은 숫자가 화면마다 갈린다</b>.
 *
 * <h2>★ 시트 항목 중 아직 못 넣는 것</h2>
 * <ul>
 *   <li><b>진도(플래너)</b> — I-23 보류(입력 주체·표시 방식 미정). 지침이
 *       "확정 전까지 위젯 자리만 확보"라 필드를 만들지 않았다. 임의로 만들면
 *       앱이 그 모양에 맞춰 굳는다</li>
 *   <li><b>온라인 질의응답</b> — F-4.11-7 미구현(멘토 배정·SLA 미확정).
 *       달력의 '질' 표시는 지금 있는 <b>오프라인 상담실 예약</b> 건수로 채운다.
 *       온라인이 생기면 합산 대상이 하나 늘 뿐 응답 모양은 안 바뀐다</li>
 *   <li><b>매일 밤 FCM 요약 푸시</b> — E-7(FCM 프로젝트)·문구 미확정</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DailyReportService {

    /** 셀프 피드백 최대 길이. 스키마와 맞춘다. */
    private static final int FEEDBACK_MAX = 500;

    private final AttendanceQueryService attendanceQueryService;
    private final DailyRoutineService routineService;
    private final DailyReportFeedbackRepository feedbackRepository;
    private final QnaOfflineReservationRepository reservationRepository;
    private final Clock clock;

    // ── 일자 상세 ─────────────────────────────────────────────

    /**
     * 하루치 상세. 달력에서 일자를 탭했을 때 열리는 화면이다.
     *
     * <p><b>확정 전이면 상태가 {@code null}이다</b> — 오늘을 열면 순공시간이 비어 있다.
     * 0으로 채우면 "오늘 하나도 공부 안 함"으로 읽힌다.
     */
    public DayReport day(StudentEnrollment enrollment, LocalDate date) {
        List<AttendanceQueryService.DailySummary> days =
                attendanceQueryService.daily(enrollment.getId(), date, date);
        AttendanceQueryService.DailySummary summary = days.isEmpty() ? null : days.get(0);

        List<DailyRoutineResult> routines =
                routineService.studentResults(enrollment.getId(), date, date);

        return new DayReport(date,
                summary == null ? null : summary.finalStatus(),
                summary != null && summary.excused(),
                summary == null ? null : summary.studyMinutes(),
                summary == null ? List.of() : summary.events(),
                summary == null ? List.of() : summary.reasons(),
                visibleRoutines(routines),
                qnaCount(enrollment.getId(), date, date),
                feedbackRepository
                        .findByEnrollmentIdAndReportDateAndDeletedFalse(enrollment.getId(), date)
                        .map(DailyReportFeedback::getContent).orElse(null));
    }

    // ── 달력(월간) ────────────────────────────────────────────

    /**
     * 달력 뷰. 일자별로 순공·출결·질의응답·데일리테스트를 한 줄씩 내린다.
     *
     * <p><b>한 달치를 한 번에 읽는다</b> — 날짜마다 부르면 앱이 30번 왕복한다.
     */
    public MonthReport month(StudentEnrollment enrollment, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());

        List<AttendanceQueryService.DailySummary> days =
                attendanceQueryService.daily(enrollment.getId(), from, to);

        Map<LocalDate, Long> routineCounts = routineService
                .studentResults(enrollment.getId(), from, to).stream()
                .filter(r -> r.getStatus().isVisibleToStudent())
                .collect(Collectors.groupingBy(DailyRoutineResult::getResultDate,
                        Collectors.counting()));

        Map<LocalDate, Long> qnaCounts = reservationRepository
                .findMine(enrollment.getId(), from, to).stream()
                .filter(r -> r.getCanceledAt() == null)
                .collect(Collectors.groupingBy(r -> r.getSlot().getSlotDate(),
                        Collectors.counting()));

        Map<LocalDate, String> feedbacks = feedbackRepository
                .findByEnrollmentIdAndReportDateBetweenAndDeletedFalse(
                        enrollment.getId(), from, to)
                .stream()
                .collect(Collectors.toMap(DailyReportFeedback::getReportDate,
                        DailyReportFeedback::getContent, (a, b) -> a, LinkedHashMap::new));

        List<DayCell> cells = days.stream()
                .map(d -> new DayCell(d.date(), d.finalStatus(), d.excused(), d.studyMinutes(),
                        routineCounts.getOrDefault(d.date(), 0L).intValue(),
                        qnaCounts.getOrDefault(d.date(), 0L).intValue(),
                        feedbacks.containsKey(d.date())))
                .toList();

        var period = attendanceQueryService.summarize(days);
        return new MonthReport(year, month, period.studyMinutes(),
                period.attendanceRate(), period.confirmedDays(), cells);
    }

    // ── 셀프 피드백 ───────────────────────────────────────────

    /**
     * 셀프 피드백 저장. 하루 1행이라 다시 쓰면 덮어쓴다.
     *
     * <p><b>미래 날짜에는 못 쓴다</b> — 아직 오지 않은 날의 회고는 있을 수 없고,
     * 달력이 미래 칸을 채운 것으로 표시하게 된다.
     */
    @Transactional
    public DailyReportFeedback writeFeedback(StudentEnrollment enrollment, LocalDate date,
                                             String content) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "미래 날짜에는 작성할 수 없습니다.");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "내용을 입력해 주세요.");
        }
        if (content.length() > FEEDBACK_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "%d자까지 쓸 수 있습니다.".formatted(FEEDBACK_MAX));
        }

        return feedbackRepository
                .findByEnrollmentIdAndReportDateAndDeletedFalse(enrollment.getId(), date)
                .map(existing -> {
                    existing.rewrite(content);
                    return existing;
                })
                .orElseGet(() -> feedbackRepository.save(
                        new DailyReportFeedback(enrollment, date, content)));
    }

    // ─────────────────────────────────────────────────────────

    /** 검수 중인 점수는 감춘다 — 루틴 도메인의 공개 규칙을 그대로 따른다. */
    private List<DailyRoutineResult> visibleRoutines(List<DailyRoutineResult> results) {
        return results.stream()
                .filter(r -> r.getStatus().isVisibleToStudent())
                .toList();
    }

    /** 지금은 오프라인 상담실 예약만 센다(온라인 QnA 미구현). 취소분은 뺀다. */
    private int qnaCount(Long enrollmentId, LocalDate from, LocalDate to) {
        return (int) reservationRepository.findMine(enrollmentId, from, to).stream()
                .filter(r -> r.getCanceledAt() == null)
                .count();
    }

    // ── 응답 ──────────────────────────────────────────────────

    /**
     * @param finalStatus  확정 전이면 {@code null}
     * @param studyMinutes 확정 전이면 {@code null} — 0으로 채우면 "안 했음"으로 읽힌다
     * @param events       그날 태깅 타임라인(등원·외출·복귀·하원)
     */
    public record DayReport(LocalDate date, DailyStatus finalStatus, boolean excused,
                            Integer studyMinutes, List<AttendanceTaggingLog> events,
                            List<AbsenceReason> reasons, List<DailyRoutineResult> routines,
                            int qnaCount, String selfFeedback) {
    }

    public record MonthReport(int year, int month, int studyMinutes,
                              Integer attendanceRate, int confirmedDays, List<DayCell> days) {
    }

    /** 달력 한 칸. 앱이 순공/출결/질/R 네 가지를 점으로 찍는다. */
    public record DayCell(LocalDate date, DailyStatus finalStatus, boolean excused,
                          Integer studyMinutes, int routineCount, int qnaCount,
                          boolean hasFeedback) {
    }
}
