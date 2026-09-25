package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.SeatMaster;

/**
 * 좌석 마스터.
 *
 * @param seatCd      화면에 보여주는 좌석번호. <b>구역 안에서만 유일하다</b> — 별관에도
 *                    같은 번호가 있을 수 있다
 * @param kioskSeatCd 키오스크가 이 코드로 좌석을 찾는다. 지점 안에서 유일하고 등록 후
 *                    바뀌지 않는다. 본관은 {@code seatCd}와 같고 별관은 관 offset 이 더해진
 *                    값이다(1번 → 1001번)
 * @param usable      좌석 자체의 사용가능 여부. 실시간 착석 여부가 아니다
 */
public record SeatMasterResponse(
        Long id,
        Long studyAreaId,
        String seatCd,
        String kioskSeatCd,
        String seatNm,
        int xPos,
        int yPos,
        boolean usable) {

    public static SeatMasterResponse from(SeatMaster seat) {
        return new SeatMasterResponse(seat.getId(), seat.getStudyArea().getId(), seat.getSeatCd(),
                seat.getKioskSeatCd(), seat.getSeatNm(), seat.getXPos(), seat.getYPos(),
                seat.isUsable());
    }
}
