package com.dlab.support;

import com.dlab.domain.facility.entity.Building;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;

/**
 * 좌석 관련 테스트가 공통으로 쓰는 준비물.
 *
 * <p><b>왜 생겼나</b> — 구역 위에 「관」축이 생기면서 구역을 만들려면 관이 먼저 있어야
 * 한다. 여섯 개 테스트가 각자 본관을 만들면 <b>관 기본값 규칙이 바뀔 때 여섯 군데를
 * 고쳐야 하고</b>, 한 군데를 놓쳐도 컴파일은 통과한다.
 */
public final class FacilityFixtures {

    private FacilityFixtures() {
    }

    /**
     * 본관 하나.
     *
     * <p>운영에서는 마이그레이션이 지점마다 만들어 두지만, 테스트는 지점을 직접 만들기
     * 때문에 여기서 같이 만들어야 한다.
     */
    public static Building mainBuilding(EntityManager em, Academy academy) {
        Building building = new Building(academy, Building.MAIN_CODE, "본관", (short) 0, 0);
        em.persist(building);
        return building;
    }
}
