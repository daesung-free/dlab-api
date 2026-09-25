package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.AreaType;
import com.dlab.domain.facility.entity.StudyArea;

/**
 * 구역.
 *
 * @param areaCd        화면에 보여주는 코드. <b>관 안에서만</b> 유일하다 — 본관과 별관에
 *                      둘 다 {@code "A"} 구역이 있을 수 있다
 * @param kioskAreaCd   키오스크가 좌석을 조회하는 키. 지점 안에서 유일하고 등록 후 바뀌지
 *                      않는다. <b>화면에 뿌릴 값이 아니라 대조용</b>이다 — 단말에서 구역이
 *                      안 보인다는 문의가 오면 이 값부터 확인한다
 * @param areaType      독서실({@code STUDY})인지 반 교실({@code CLASSROOM})인지
 * @param classMasterId 반 교실이면 그 반. 독서실이면 {@code null}
 * @param className     반 이름. 화면이 id 로 반을 다시 조회하지 않게 함께 내린다
 */
public record StudyAreaResponse(
        Long id,
        Long academyId,
        Long buildingId,
        String buildingName,
        String areaCd,
        String kioskAreaCd,
        String areaNm,
        short sortOrder,
        boolean active,
        AreaType areaType,
        Long classMasterId,
        String className,
        long seatCount) {

    public static StudyAreaResponse from(StudyArea area, long seatCount) {
        var clazz = area.getClassMaster();
        return new StudyAreaResponse(area.getId(), area.getAcademy().getId(),
                area.getBuilding().getId(), area.getBuilding().getName(),
                area.getAreaCd(), area.getKioskAreaCd(),
                area.getAreaNm(), area.getSortOrder(), area.isActive(),
                area.getAreaType(),
                clazz == null ? null : clazz.getId(),
                clazz == null ? null : clazz.getName(),
                seatCount);
    }
}
