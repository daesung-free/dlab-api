package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.SeatAssignment;

import java.time.Instant;

public record SeatAssignmentResponse(
        Long id,
        Long seatId,
        String seatCd,
        String seatNm,
        int xPos,
        int yPos,
        Long enrollmentId,
        String studentNo,
        String studentName,
        Instant assignedAt
) {

    public static SeatAssignmentResponse from(SeatAssignment a) {
        return new SeatAssignmentResponse(
                a.getId(),
                a.getSeat().getId(),
                a.getSeat().getSeatCd(),
                a.getSeat().getSeatNm(),
                a.getSeat().getXPos(),
                a.getSeat().getYPos(),
                a.getEnrollment().getId(),
                a.getEnrollment().getStudentNo(),
                a.getEnrollment().getStudentName(),
                a.getAssignedAt());
    }
}
