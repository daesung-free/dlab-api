package com.dlab.api.app.attendance;

import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.service.AttendanceQueryService;
import com.dlab.domain.penalty.entity.PenaltyPoint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 앱 출결·상벌점 응답 (A-18). */
public final class AttendanceResponse {

    private AttendanceResponse() {
    }

    /**
     * 하루치.
     *
     * @param finalStatus  배치가 확정한 상태. <b>{@code null}이면 아직 확정 전</b>(당일 등)이다 —
     *                     "확정 전"과 "결석"을 구분해야 앱이 오늘 날짜에 결석을 띄우지 않는다
     * @param studyMinutes 순공시간(분). 확정 전이면 {@code null}
     * @param inAt         입실 — 그날 첫 등원·지각 태깅
     * @param outAt        퇴실 — 그날 <b>마지막</b> 하원 태깅
     */
    public record Daily(LocalDate date, String finalStatus, boolean excused,
                        Integer studyMinutes, Instant inAt, Instant outAt,
                        List<AttendanceEvent> events, List<AbsenceReasonRow> reasons) {

        public static Daily from(AttendanceQueryService.DailySummary summary) {
            AttendanceTaggingLog in = summary.firstIn();
            AttendanceTaggingLog out = summary.lastOut();
            return new Daily(
                    summary.date(),
                    summary.finalStatus() == null ? null : summary.finalStatus().name(),
                    summary.excused(),
                    summary.studyMinutes(),
                    in == null ? null : in.getRecordedAt(),
                    out == null ? null : out.getRecordedAt(),
                    summary.events().stream().map(AttendanceEvent::from).toList(),
                    summary.reasons().stream().map(AbsenceReasonRow::from).toList());
        }
    }

    /**
     * 태깅 이벤트 한 건.
     *
     * @param eventType 7종(`CHECK_IN`·`CHECK_OUT`·`LATE`·`OUTING`·`EXCUSED_OUTING`·
     *                  `EARLY_LEAVE`·`RETURN`). <b>결석은 여기 없다</b> — 태깅 이벤트가 아니다
     * @param source    `KIOSK_NFC` / `APP_QR` / `MANUAL`. 관리자가 손으로 넣은 건 MANUAL이라
     *                  앱에서 "직접 등록됨"을 표시할 수 있다
     */
    public record AttendanceEvent(Instant recordedAt, String eventType, String source) {

        public static AttendanceEvent from(AttendanceTaggingLog log) {
            return new AttendanceEvent(log.getRecordedAt(), log.getEventType().name(),
                    log.getSource() == null ? null : log.getSource().name());
        }
    }

    /**
     * 상벌점.
     *
     * @param total <b>벌점은 음수</b>다. 부호를 그대로 내려 앱이 상점·벌점을 구분한다 —
     *              절댓값으로 바꾸면 상쇄가 사라진다
     */
    public record Penalties(int total, List<PenaltyRow> items) {

        public static Penalties from(AttendanceQueryService.PenaltySummary summary) {
            return new Penalties(summary.total(),
                    summary.items().stream().map(PenaltyRow::from).toList());
        }
    }

    public record PenaltyRow(Long id, String itemName, String category, int points,
                             String reason, String source, Instant occurredAt) {

        public static PenaltyRow from(PenaltyPoint p) {
            return new PenaltyRow(p.getId(),
                    p.getPenaltyItem().getItemName(),
                    p.getPenaltyItem().getCategory().name(),
                    p.getPoints(),
                    p.getReason(),
                    p.getSource() == null ? null : p.getSource().name(),
                    p.getOccurredAt());
        }
    }

    /**
     * 사유출결 한 건.
     *
     * @param approvalStatus 승인 상태. {@code null}이면 승인 절차를 안 타는 건이다
     */
    public record AbsenceReasonRow(Long id, LocalDate date, String reasonType, String reasonText,
                                   String period, String approvalStatus, Instant submittedAt) {

        public static AbsenceReasonRow from(AbsenceReason r) {
            return new AbsenceReasonRow(
                    r.getId(),
                    r.getAttendanceDate(),
                    r.getReasonType().name(),
                    r.getReasonText(),
                    r.periodLabel(),
                    r.getApprovalRequest() == null ? null : r.getApprovalRequest().getStatus().name(),
                    r.getSubmittedAt());
        }
    }
}
