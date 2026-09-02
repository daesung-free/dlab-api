package com.dlab.api.app.consult;

import com.dlab.domain.consult.entity.ConsultReservation;
import com.dlab.domain.consult.entity.ConsultSlot;
import com.dlab.domain.consult.entity.ConsultType;
import com.dlab.domain.consult.service.ConsultReservationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class ConsultResponse {

    private ConsultResponse() {}

    /**
     * 슬롯 한 칸.
     *
     * <p>{@code reservedNames}는 <b>담임 화면에서만</b> 채워진다 — 학생에게 남의 상담 예약이
     * 보이면 안 된다.
     */
    public record ConsultSlotView(
            Long id,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            Long teacherId,
            String teacherName,
            String place,
            String memo,
            short capacity,
            long reserved,
            boolean full,
            boolean published,
            List<Reserved> reservedList) {

        public static ConsultSlotView from(ConsultReservationService.SlotView view) {
            ConsultSlot s = view.slot();
            return new ConsultSlotView(s.getId(), s.getSlotDate(), s.getStartTime(), s.getEndTime(),
                    s.getTeacher().getId(), s.getTeacher().getName(),
                    s.getPlace(), s.getMemo(), s.getCapacity(), view.reserved(), view.isFull(),
                    s.isPublished(),
                    view.reservations().stream().map(Reserved::from).toList());
        }
    }

    /** 담임 화면의 예약자. */
    public record Reserved(Long reservationId, Long enrollmentId, String studentName,
                           ConsultType consultType, String requestNote, boolean logWritten) {

        public static Reserved from(ConsultReservation r) {
            return new Reserved(r.getId(), r.getEnrollment().getId(),
                    r.getEnrollment().getStudent().getName(),
                    r.getConsultType(), r.getRequestNote(), r.getConsultLogId() != null);
        }
    }

    /** 학생이 보는 본인 예약. */
    public record ConsultReservationView(
            Long id,
            Long slotId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            String teacherName,
            String place,
            ConsultType consultType,
            String requestNote,
            Instant reservedAt,
            Instant canceledAt) {

        public static ConsultReservationView from(ConsultReservation r) {
            ConsultSlot s = r.getSlot();
            return new ConsultReservationView(r.getId(), s.getId(), s.getSlotDate(),
                    s.getStartTime(), s.getEndTime(), s.getTeacher().getName(), s.getPlace(),
                    r.getConsultType(), r.getRequestNote(), r.getReservedAt(), r.getCanceledAt());
        }
    }
}
