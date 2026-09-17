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
import com.dlab.domain.facility.service.SeatLayoutService;
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
    @Autowired SeatLayoutService seatLayoutService;

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
                (short) 1, 1000, true);

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
                (short) 1, 1000, true);
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
                (short) 1, 1000, true);

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
                admin, dongtan.getId(), "2", "2관", (short) 1, 100, true))
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

    @Test
    @DisplayName("★ 구역 목록에 관·키오스크 코드가 실린다 — 화면이 본관 A 와 별관 A 를 구분할 근거")
    void areaListCarriesBuildingAndKioskCode() {
        // 목록 응답은 StudyAreaResponse 가 아니라 AreaSummary 라 필드가 따로 논다.
        // 실제로 kioskAreaCd 가 빠진 채로 화면이 그 칼럼을 그리고 있었다
        Building annex = buildingAdminService.create(admin, dongtan.getId(), "2", "2관",
                (short) 1, 1000, true);
        area(main.getId(), "A");
        area(annex.getId(), "A");
        em.flush();

        assertThat(seatLayoutService.areas(admin, dongtan.getId(), true, null, null))
                .extracting(a -> a.buildingName() + "/" + a.areaCd() + "/" + a.kioskAreaCd())
                .containsExactlyInAnyOrder("본관/A/A", "2관/A/2-A");
    }

    @Test
    @DisplayName("★ 접두어 없이 격자를 만들 수 있다 — 별관 오프셋은 숫자일 때만 걸린다")
    void gridWithoutPrefix() {
        // 접두어가 필수였을 때, 정작 이 기능이 필요한 동탄(순수 숫자 번호)이
        // 격자를 못 만들었다. "A-01" 이면 offset 변환 자체가 안 걸린다
        Building annex = buildingAdminService.create(admin, dongtan.getId(), "2", "2관",
                (short) 1, 1000, true);
        StudyArea annexA = area(annex.getId(), "A");

        var created = seatMasterAdminService.createGrid(admin,
                new SeatMasterAdminService.SeatGridSpec(
                        annexA.getId(), 1, 3, "", 1, 1, 1, 1, false, List.of()));

        assertThat(created)
                .extracting(s -> s.getSeatCd() + "->" + s.getKioskSeatCd())
                .containsExactly("1->1001", "2->1002", "3->1003");
    }

    @Test
    @DisplayName("★★ 별관 번호대가 겹치면 관 등록에서 막는다 — 두 번째 별관이 좌석을 한 자리도 못 만들던 것")
    void rejectsDuplicateOffset() {
        buildingAdminService.create(admin, dongtan.getId(), "2", "2관", (short) 1, 1000, true);

        // 통과시키면 3관은 구역까지 다 만든 뒤 좌석 단계에서 전부 거부된다.
        // 그때는 어디서부터 잘못됐는지 되짚어야 한다
        assertThatThrownBy(() -> buildingAdminService.create(
                admin, dongtan.getId(), "3", "3관", (short) 2, 1000, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2관");
    }

    @Test
    @DisplayName("★★ 번호대를 안 보내면 서버가 채번한다 — 화면이 계산하면 동시 등록에 같은 값이 나온다")
    void autoAssignsOffset() {
        Building second = buildingAdminService.create(
                admin, dongtan.getId(), "2", "2관", (short) 1, null, true);
        Building third = buildingAdminService.create(
                admin, dongtan.getId(), "3", "3관", (short) 2, null, true);

        assertThat(second.getSeatCdOffset()).isEqualTo(1000);
        assertThat(third.getSeatCdOffset()).isEqualTo(2000);
    }

    @Test
    @DisplayName("★ 본관은 지점당 하나다 — 둘이면 buildingId 없는 구역 등록이 통째로 막힌다")
    void rejectsSecondMainBuilding() {
        assertThatThrownBy(() -> buildingAdminService.create(
                admin, dongtan.getId(), "MAIN2", "본관2", (short) 9, 0, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본관은 지점마다 하나");
    }

    @Test
    @DisplayName("★ 배치도에도 키오스크 번호가 실린다 — 단말 문의를 받는 화면이 여기다")
    void layoutCarriesKioskSeatCd() {
        // masters 에만 넣고 layout 에 빠뜨렸던 자리다. DTO 가 여러 벌이라
        // 한쪽만 채우기 쉽고, 빠져도 컴파일은 통과한다
        Building annex = buildingAdminService.create(
                admin, dongtan.getId(), "2", "2관", (short) 1, null, true);
        StudyArea annexA = area(annex.getId(), "A");
        seat(annexA, "1");
        em.flush();

        assertThat(seatLayoutService.layout(admin, annexA.getId(), false))
                .extracting(c -> c.seatCd() + "->" + c.kioskSeatCd())
                .containsExactly("1->1001");
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
