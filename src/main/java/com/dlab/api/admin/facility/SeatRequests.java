package com.dlab.api.admin.facility;

import jakarta.validation.constraints.NotNull;

public final class SeatRequests {

    private SeatRequests() {
    }

    public record Assign(
            @NotNull(message = "좌석은 필수입니다.") Long seatId,
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId) {
    }
}
