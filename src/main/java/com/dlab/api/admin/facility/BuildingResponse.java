package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.Building;

/**
 * 관(본관/별관).
 *
 * @param seatCdOffset 키오스크에 내릴 좌석번호에 더하는 값. 0이면 본관
 * @param main         본관인가. 화면이 {@code seatCdOffset == 0}을 직접 판정하지 않게 한다
 */
public record BuildingResponse(
        Long id,
        Long academyId,
        String code,
        String name,
        short sortOrder,
        int seatCdOffset,
        boolean main,
        boolean active) {

    public static BuildingResponse from(Building building) {
        return new BuildingResponse(building.getId(), building.getAcademy().getId(),
                building.getCode(), building.getName(), building.getSortOrder(),
                building.getSeatCdOffset(), building.isMain(), building.isActive());
    }
}
