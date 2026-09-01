package com.dlab.api.app.consult;

import com.dlab.domain.consult.entity.ConsultType;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;

public final class ConsultRequests {

    private ConsultRequests() {}

    /**
     * 가능 일정 일괄 개설.
     *
     * <p><b>간격을 여기서 받는다.</b> 스키마에 두면 바뀔 때 마이그레이션이 필요해진다 —
     * 시작·종료·간격으로 슬롯 행을 여러 개 만든다.
     */
    public record ConsultOpenSlots(
            @NotNull LocalDate date,
            @NotNull LocalTime from,
            @NotNull LocalTime to,
            @Min(1) @Max(240) int intervalMinutes,
            /* 1:1이 기본. 학부모 상담에 부모가 함께 오는 경우가 있어 열어둔다 */
            @Min(1) @Max(10) short capacity,
            @Size(max = 50) String place) {}

    /** 노출 켜기/끄기. 삭제가 아니라 이미 잡힌 예약은 유효하다. */
    public record Publish(@NotNull Boolean published) {}

    public record UpdateSlot(@Size(max = 50) String place, @Size(max = 200) String memo) {}

    public record ConsultReserve(
            @NotNull Long slotId,
            @NotNull ConsultType consultType,
            /* 무엇을 상담하고 싶은지. 없으면 담임이 준비를 못 한다 */
            @Size(max = 500) String requestNote) {}
}
