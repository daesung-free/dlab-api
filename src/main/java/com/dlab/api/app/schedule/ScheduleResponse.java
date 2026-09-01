package com.dlab.api.app.schedule;

import com.dlab.domain.schedule.entity.RegularSchedule;
import com.dlab.domain.schedule.entity.RegularScheduleItem;
import com.dlab.domain.schedule.service.ScheduleComplianceService;
import java.time.LocalTime;
import java.util.List;

/** 정기일정 응답. */
public final class ScheduleResponse {

    private ScheduleResponse() {
    }

    /**
     * 한 달치 제출.
     *
     * @param source         {@code STUDENT} / {@code ADMIN}(담임 대신 등록)
     * @param approvalStatus 관리자 등록분은 {@code null}이다 — 승인 요청 없이 인정된다.
     *                       화면은 {@code approved}를 보고 판단할 것
     */
    public record ScheduleMonth(Long scheduleId, Long enrollmentId, String studentName,
                        String studentNo, short year, short month, String source,
                        String approvalStatus, boolean approved, List<ScheduleItem> items) {

        public static ScheduleMonth from(RegularSchedule s) {
            return new ScheduleMonth(s.getId(), s.getEnrollment().getId(),
                    s.getEnrollment().getStudent().getName(),
                    s.getEnrollment().getStudentNo(),
                    s.getYear(), s.getScheduleMonth(), s.getSource().name(),
                    s.getApprovalRequest() == null ? null
                            : s.getApprovalRequest().getStatus().name(),
                    s.isApproved(),
                    s.getItems().stream().filter(i -> !i.isDeleted())
                            .map(ScheduleItem::from).toList());
        }
    }

    public record ScheduleItem(Long id, String dayOfWeek, LocalTime startTime, LocalTime endTime,
                       String title, String place) {

        static ScheduleItem from(RegularScheduleItem i) {
            return new ScheduleItem(i.getId(), i.dayOfWeekValue().name(), i.getStartTime(),
                    i.getEndTime(), i.getTitle(), i.getPlace());
        }
    }

    /**
     * 인정 판정 결과.
     *
     * @param gapMinutes 등록 시각과 실제 외출의 차이(분). 안 나갔으면 {@code null}
     * @param recognized 30분 이상 어긋나거나 안 나갔으면 {@code false} — 벌점 대상이다
     */
    public record Compliance(ScheduleItem item, LocalTime actualDeparture, Long gapMinutes,
                             boolean recognized) {

        public static Compliance from(ScheduleComplianceService.Verdict v) {
            return new Compliance(ScheduleItem.from(v.item()), v.actualDeparture(),
                    v.gapMinutes(), v.recognized());
        }
    }
}
