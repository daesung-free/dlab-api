package com.dlab.api.kiosk.seatleave;

import com.dlab.domain.kiosk.entity.SeatLeaveEventType;
import com.dlab.domain.kiosk.service.SeatLeaveIngestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class SeatLeaveRequests {

    private SeatLeaveRequests() {}

    /**
     * 좌석 이탈·복귀 일괄 전송.
     *
     * @param token  DSA 호환 구획에서 쓰는 그 토큰이다 — 키오스크가 이미 갖고 있어
     *               새로 발급받을 것이 없다
     * @param events 재전송이 밀렸을 때를 위해 배열로 받는다
     */
    public record Ingest(
            @NotNull String token,
            @NotEmpty @Size(max = 500) @Valid List<Event> events) {

        public List<SeatLeaveIngestService.Event> toEvents() {
            return events.stream()
                    .map(e -> new SeatLeaveIngestService.Event(
                            e.sourceRowId(), e.rfidNo(), e.studentNo(),
                            e.areaCd(), e.seatCd(), e.eventType(), e.occurredAt()))
                    .toList();
        }
    }

    /**
     * @param sourceRowId 키오스크 {@code seat_leaves} 행 ID. <b>재전송 중복을 거르는
     *                    유일한 수단</b>이라 필수다
     * @param occurredAt  발생 시각. 받은 시각이 아니라 <b>실제 이탈·복귀 시각</b>이어야
     *                    미복귀 판정이 맞는다
     */
    public record Event(
            @NotNull Long sourceRowId,
            @Size(max = 50) String rfidNo,
            @Size(max = 20) String studentNo,
            @Size(max = 20) String areaCd,
            @Size(max = 20) String seatCd,
            @NotNull SeatLeaveEventType eventType,
            @NotNull Instant occurredAt) {}
}
