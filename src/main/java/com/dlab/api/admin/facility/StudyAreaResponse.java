package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.AreaType;
import com.dlab.domain.facility.entity.StudyArea;

/**
 * 구역.
 *
 * @param areaCd        키오스크가 좌석을 조회하는 키다. 등록 후 바뀌지 않는다
 * @param areaType      독서실({@code STUDY})인지 반 교실({@code CLASSROOM})인지
 * @param classMasterId 반 교실이면 그 반. 독서실이면 {@code null}
 * @param className     반 이름. 화면이 id 로 반을 다시 조회하지 않게 함께 내린다
 */
public record StudyAreaResponse(
        Long id,
        Long academyId,
        String areaCd,
        String areaNm,
        short sortOrder,
        boolean active,
        AreaType areaType,
        Long classMasterId,
        String className,
        long seatCount) {

    public static StudyAreaResponse from(StudyArea area, long seatCount) {
        var clazz = area.getClassMaster();
        return new StudyAreaResponse(area.getId(), area.getAcademy().getId(), area.getAreaCd(),
                area.getAreaNm(), area.getSortOrder(), area.isActive(),
                area.getAreaType(),
                clazz == null ? null : clazz.getId(),
                clazz == null ? null : clazz.getName(),
                seatCount);
    }
}
