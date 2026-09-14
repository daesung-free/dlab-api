package com.dlab.api.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.facility.entity.Building;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.service.BuildingAdminService;
import com.dlab.domain.facility.service.SeatMasterAdminService;
import com.dlab.domain.facility.service.StudyAreaAdminService;
import com.dlab.domain.kiosk.service.KioskSeatQueryService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「관」축 — 동탄2관(별관)이 본관과 <b>같은 좌석번호</b>를 쓸 수 있는가.
 *
 * <p><b>왜 테스트로 못박나</b> — 이 기능의 실패는 두 가지 모습인데 둘 다 조용하다.
 * <ul>
 *   <li>등록이 막히면 데스크가 <b>DSA 처럼 1000번대로 우회</b>해 버린다. 그러면 요구사항이
 *       충족된 적이 없는데 아무도 문제 제기를 안 한다</li>
 *   <li>키오스크 번호가 겹치면 <b>단말에서만</b> 두 자리가 한 자리로 보인다. 우리 화면·DB 는
 *       멀쩡해서 원인을 찾기가 매우 어렵다</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class SeatBuildingTest {

    @Autowired EntityManager em;
    @Autowired BuildingAdminService buildingAdminService;
    @Autowired StudyAreaAdminService studyAreaAdminService;
    @Autowired SeatMasterAdminService seatMasterAdminService;
    @Autowired KioskSeatQueryService kioskSeatQueryService;

    Academy dongtan;
    Building main;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        dongtan = new Academy("33", "동탄", LocalTime.of(9, 0));
        em.persist(dongtan);
        main = new Building(dongtan, Building.MAIN_CODE, "본관", (short) 0, 0);
        em.persist(main);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", dongtan.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    @Test
    @DisplayName("★★ 별관이 본관과 같은 구역명·좌석번호를 쓸 수 있다 — 요구사항 그 자체")
    void annexReusesSameNumbers() {
        Building annex = buildingAdminService.create(admin, dongtan.getId(), "2", "2관",
                (short) 1, 1000);

        StudyArea mainA = area(main.getId(), "A");
        StudyArea annexA = area(annex.getId(), "A");

        SeatMaster mainSeat = seat(mainA, "1");
        SeatMaster annexSeat = seat(annexA, "1");

        // 화면에 보이는 값은 둘 다 본래 번호다 — 이게 클라이언트 요구였다
        assertThat(mainSeat.getSeatCd()).isEqualTo("1");
        assertThat(annexSeat.getSeatCd()).isEqualTo("1");
        assertThat(mainA.getAreaCd()).isEqualTo(annexA.getAreaCd());

        // 단말에 내려가는 값만 갈라진다 — DSA 가 쓰던 1000번대 그대로다
        assertThat(mainSeat.getKioskSeatCd()).isEqualTo("1");
        assertThat(annexSeat.getKioskSeatCd()).isEqualTo("1001");
        assertThat(annexA.getKioskAreaCd()).isEqualTo("2-A");
    }

    @Test
    @DisplayName("★★ 키오스크는 관을 모른 채 구역코드 하나로 두 구역을 구분한다")
    void kioskSeesEachBuildingSeparately() {
        Building annex = buildingAdminService.create(admin, dongtan.getId(), "2", "2관",
                (short) 1, 1000);
        seat(area(main.getId(), "A"), "1");
        seat(area(annex.getId(), "A"), "1");
        em.flush();

        // 단말이 아는 코드로 물어야 그 관의 좌석이 온다.
        // area_cd 로 찾으면 본관이 잡혀 별관 좌석이 통째로 사라진다
        assertThat(kioskSeatQueryService.seats(dongtan.getId(), "A"))
                .extracting(r -> r.seatCd())
                .containsExactly("1");
        assertThat(kioskSeatQueryService.seats(dongtan.getId(), "2-A"))
                .extracting(r -> r.seatCd())
                .containsExactly("1001");
    }

    @Test
    @DisplayName("★ 별관 번호가 본관 실재 번호와 겹치면 등록 시점에 막는다 — 단말에서 터지면 늦다")
    void rejectsWhenTranslatedCodeCollides() {
        Building annex = buildingAdminService.create(admin, dongtan.getId(), "2", "2관",
                (short) 1, 1000);

        // 본관에 1001번이 실재한다. 별관 1번의 변환 결과와 같은 값이다
        seat(area(main.getId(), "A"), "1001");
        em.flush();

        StudyArea annexA = area(annex.getId(), "B");
        assertThatThrownBy(() -> seat(annexA, "1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1001");
    }

    @Test
    @DisplayName("★ 같은 구역 안에서는 여전히 못 겹친다")
    void stillRejectsWithinSameArea() {
        StudyArea mainA = area(main.getId(), "A");
        seat(mainA, "1");
        em.flush();

        assertThatThrownBy(() -> seat(mainA, "1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SEAT_CD_DUPLICATED);
    }

    @Test
    @DisplayName("★ 별관 오프셋은 1000 미만을 받지 않는다 — 100이면 본관 101번과 곧바로 겹친다")
    void rejectsSmallOffset() {
        assertThatThrownBy(() -> buildingAdminService.create(
                admin, dongtan.getId(), "2", "2관", (short) 1, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1000");
    }

    @Test
    @DisplayName("관을 안 보내도 본관에 붙는다 — 화면이 따라오기 전에도 구역 등록이 돌아야 한다")
    void defaultsToMainBuilding() {
        StudyArea area = studyAreaAdminService.create(
                admin, dongtan.getId(), null, "A", "A구역", (short) 1);

        assertThat(area.getBuilding().getId()).isEqualTo(main.getId());
        assertThat(area.getKioskAreaCd()).isEqualTo("A");
    }

    // ── 준비물 ───────────────────────────────────────────────────────────────

    private StudyArea area(Long buildingId, String areaCd) {
        return studyAreaAdminService.create(
                admin, dongtan.getId(), buildingId, areaCd, areaCd + "구역", (short) 1);
    }

    private SeatMaster seat(StudyArea area, String seatCd) {
        return seatMasterAdminService.create(admin, area.getId(), seatCd, seatCd + "번", 1, 1);
    }
}
