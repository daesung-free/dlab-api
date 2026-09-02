package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.StudyArea;

/**
 * 자습 구역.
 *
 * @param areaCd 키오스크가 좌석을 조회하는 키다. 등록 후 바뀌지 않는다
 */
public record StudyAreaResponse(
        Long id,
        Long academyId,
        String areaCd,
        String areaNm,
        short sortOrder,
        boolean active,
        long seatCount) {

    public static StudyAreaResponse from(StudyArea area, long seatCount) {
        return new StudyAreaResponse(area.getId(), area.getAcademy().getId(), area.getAreaCd(),
                area.getAreaNm(), area.getSortOrder(), area.isActive(), seatCount);
    }
}
