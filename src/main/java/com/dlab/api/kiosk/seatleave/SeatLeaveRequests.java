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
            @NotEmpty @Size(max = 500) @Valid List<SeatLeaveEvent> events) {

        public List<SeatLeaveIngestService.Event> toEvents() {
            return events.stream()
                    .map(e -> new SeatLeaveIngestService.Event(
                            e.sourceRowId(), e.rfidNo(), e.studentNo(),
                            e.areaCd(), e.seatCd(), e.eventType(), e.occurredAt(),
                            e.reasonName()))
                    .toList();
        }
    }

    /**
     * @param sourceRowId 키오스크 {@code seat_leaves} 행 ID. <b>재전송 중복을 거르는
     *                    유일한 수단</b>이라 필수다
     * @param occurredAt  발생 시각. 받은 시각이 아니라 <b>실제 이탈·복귀 시각</b>이어야
     *                    미복귀 판정이 맞는다
     */
    public record SeatLeaveEvent(
            @NotNull Long sourceRowId,
            @Size(max = 50) String rfidNo,
            @Size(max = 20) String studentNo,
            @Size(max = 20) String areaCd,
            @Size(max = 20) String seatCd,
            /**
             * {@code LEAVE} / {@code RETURN} / {@code AUTO_CLOSE}.
             *
             * <p><b>00:30 일괄 마감은 {@code AUTO_CLOSE}로 보낸다.</b> {@code RETURN}으로
             * 보내면 장시간 미복귀가 복귀로 닫혀 감지가 무의미해진다.
             */
            @NotNull SeatLeaveEventType eventType,
            @NotNull Instant occurredAt,
            /**
             * 이탈 사유(이탈 위치) — <b>선택값</b>이다.
             *
             * <p>키오스크가 지점별로 관리하는 <b>이름</b>을 그대로 받는다(화장실·강의실 …).
             * 코드로 받지 않는 이유는 지점마다 독립 채번이라 지점 간 비교가 안 되기 때문이다.
             *
             * <p><b>비어도 된다.</b> 키오스크가 아직 안 보내는 동안에도 이탈 수신이 그대로
             * 돌아야 하고, {@code RETURN}·{@code AUTO_CLOSE}에는 애초에 사유가 없다.
             */
            @Size(max = 50) String reasonName) {}
}
