package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.SeatMaster;

/**
 * 좌석 마스터.
 *
 * @param seatCd 키오스크가 이 코드로 좌석을 찾는다. 등록 후 바뀌지 않는다
 * @param usable 좌석 자체의 사용가능 여부. 실시간 착석 여부가 아니다
 */
public record SeatMasterResponse(
        Long id,
        Long studyAreaId,
        String seatCd,
        String seatNm,
        int xPos,
        int yPos,
        boolean usable) {

    public static SeatMasterResponse from(SeatMaster seat) {
        return new SeatMasterResponse(seat.getId(), seat.getStudyArea().getId(), seat.getSeatCd(),
                seat.getSeatNm(), seat.getXPos(), seat.getYPos(), seat.isUsable());
    }
}
