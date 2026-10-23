package com.dlab.api.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.facility.entity.Building;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.SeatStyle;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.service.BuildingAdminService;
import com.dlab.domain.facility.service.SeatMasterAdminService;
import com.dlab.domain.facility.service.SeatMasterAdminService.GridSkip;
import com.dlab.domain.facility.service.SeatMasterAdminService.SeatGridSpec;
import com.dlab.domain.facility.service.SeatMasterAdminService.StyleRange;
import com.dlab.domain.facility.service.StudyAreaAdminService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석형태(교실형·집중형·1인실)와 격자 번호 방향.
 *
 * <h2>왜 이 두 구역으로 검증하나</h2>
 * 역방향이 맞는지는 <b>실제로 거꾸로 붙어 있는 구역</b>으로만 확인된다. 2026-10-22
 * 디랩 답변과 배치표에서 확인된 역방향 구역이 둘뿐이다.
 * <ul>
 *   <li><b>대구 A</b> — 1번이 오른쪽 끝이고 왼쪽으로 증가한다(가로 역방향)</li>
 *   <li><b>동탄 별관 C</b> — 1201이 오른쪽 아래고 왼쪽 위로 증가한다(가로·세로 역방향,
 *       세로 우선)</li>
 * </ul>
 * 숫자는 배치표 PNG 에서 읽은 실제 번호다. 방향 옵션이 없으면 이 두 구역은 68석·25석을
 * <b>한 칸씩 찍어야</b> 만들어진다.
 */
@SpringBootTest
@Transactional
class SeatStyleGridTest {

    @Autowired EntityManager em;
    @Autowired BuildingAdminService buildingAdminService;
    @Autowired StudyAreaAdminService studyAreaAdminService;
    @Autowired SeatMasterAdminService seatMasterAdminService;

    Academy academy;
    Building main;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("SG01", "좌석형태지점", LocalTime.of(9, 0));
        em.persist(academy);
        main = new Building(academy, Building.MAIN_CODE, "본관", (short) 0, 0);
        em.persist(main);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    // ── 번호 방향 ───────────────────────────────────────────────────────────

    /**
     * 대구 A 첫 줄 — 11열 중 왼쪽 4칸이 통로, 오른쪽 끝 1칸이 배치 불가다.
     *
     * <p>배치표에서 번호가 {@code … 6 5 4 3 2 1 ×} 순으로 붙어 있다. 1번이 <b>오른쪽에서
     * 두 번째 칸</b>(10열)이고 왼쪽으로 가며 커진다.
     */
    @Test
    @DisplayName("★★ 가로 역방향 — 대구 A 처럼 1번이 오른쪽 끝에서 시작한다")
    void reverseXNumbersFromRight() {
        StudyArea areaA = area(main.getId(), "A");

        List<SeatMaster> seats = seatMasterAdminService.createGrid(admin, spec(areaA, 1, 11)
                .startNumber(1)
                .reverseX(true)
                // 왼쪽 4칸 통로(하늘색) · 오른쪽 끝 배치 불가(회색)
                .skips(cells(1, 1, 2, 3, 4, 11))
                // 대구 A 는 1~6 이 1인실, 7~57 교실형, 58~68 집중형이다
                .styleRanges(List.of(
                        new StyleRange(1, 6, SeatStyle.SINGLE_ROOM),
                        new StyleRange(7, 57, SeatStyle.CLASSROOM),
                        new StyleRange(58, 68, SeatStyle.FOCUS)))
                .build());

        assertThat(seats).extracting(SeatMaster::getSeatCd, SeatMaster::getXPos)
                .containsExactly(
                        tuple("1", 10), tuple("2", 9), tuple("3", 8),
                        tuple("4", 7), tuple("5", 6), tuple("6", 5));

        // 좌표는 뒤집히지 않는다 — 번호 붙는 순서만 뒤집힌다
        assertThat(seats).allSatisfy(s -> assertThat(s.getYPos()).isEqualTo(1));
        // 1~6 은 전부 1인실 구간이다
        assertThat(seats).extracting(SeatMaster::getSeatStyle)
                .containsOnly(SeatStyle.SINGLE_ROOM);
    }

    /**
     * 동탄 별관 C 오른쪽 세 줄 — 1201이 오른쪽 맨 아래고, 한 열을 다 올라간 뒤 왼쪽 열로
     * 넘어간다(세로 우선 + 가로·세로 역방향).
     *
     * <p>배치표에서 읽은 실제 배열이다(열은 왼쪽부터 9·10·11, 행은 위부터 1~6).
     * <pre>
     *        9열   10열  11열
     *  1행    ×     ×     ×      ← 통로
     *  2행   1214  1209   ×
     *  3행   1213  1208  1204
     *  4행   1212  1207  1203
     *  5행   1211  1206  1202
     *  6행   1210  1205  1201
     * </pre>
     *
     * <p><b>별관이라 저장되는 번호는 201~214</b>이고 1000번대는 키오스크에 내릴 때만
     * 붙는다 — 화면은 본래 번호로 보인다(CLAUDE.md).
     */
    @Test
    @DisplayName("★★ 가로·세로 역방향 + 세로 우선 — 동탄 별관 C 처럼 오른쪽 아래에서 왼쪽 위로 간다")
    void reverseBothAxesColumnMajor() {
        // 운영의 동탄 별관이 실제로 쓰는 코드가 '2' 다(2026-10-23 실측). 별관인지는
        // 코드가 아니라 offset 으로 갈린다 — Building.isAnnex()
        Building annex = buildingAdminService.create(admin, academy.getId(),
                "2", "별관", (short) 1, 1000, true);
        assertThat(annex.isAnnex()).isTrue();
        StudyArea annexC = area(annex.getId(), "C");

        List<SeatMaster> seats = seatMasterAdminService.createGrid(admin, spec(annexC, 6, 3)
                .startNumber(201)
                .startX(9)
                .columnMajor(true)
                .reverseX(true)
                .reverseY(true)
                .skips(List.of(new GridSkip(1, 1), new GridSkip(1, 2),
                        new GridSkip(1, 3), new GridSkip(2, 3)))
                .build());

        assertThat(seats).hasSize(14);

        // 오른쪽 맨 아래가 201 이고, 그 열을 다 올라간 뒤 왼쪽 열로 넘어간다
        assertThat(seats).extracting(
                        SeatMaster::getSeatCd, SeatMaster::getXPos, SeatMaster::getYPos)
                .startsWith(
                        tuple("201", 11, 6), tuple("202", 11, 5),
                        tuple("203", 11, 4), tuple("204", 11, 3),
                        tuple("205", 10, 6))
                .endsWith(tuple("213", 9, 3), tuple("214", 9, 2));

        // ★ 별관이라 단말에 내려갈 번호만 1000번대다. 화면 값은 그대로다
        assertThat(seats.get(0).getKioskSeatCd()).isEqualTo("1201");
        assertThat(seats.get(13).getKioskSeatCd()).isEqualTo("1214");
    }

    @Test
    @DisplayName("방향 옵션이 없으면 지금까지처럼 왼쪽 위에서 가로로 붙는다")
    void defaultDirectionUnchanged() {
        StudyArea areaA = area(main.getId(), "A");

        List<SeatMaster> seats = seatMasterAdminService.createGrid(
                admin, spec(areaA, 2, 3).startNumber(1).build());

        assertThat(seats).extracting(
                        SeatMaster::getSeatCd, SeatMaster::getXPos, SeatMaster::getYPos)
                .containsExactly(
                        tuple("1", 1, 1), tuple("2", 2, 1), tuple("3", 3, 1),
                        tuple("4", 1, 2), tuple("5", 2, 2), tuple("6", 3, 2));
    }

    // ── 좌석형태 ────────────────────────────────────────────────────────────

    /**
     * 광명 C — 한 구역이 형태 3구간으로 쪼개진다. 시트가 이 모양으로 오므로 그대로 옮겨
     * 넣을 수 있어야 한다.
     */
    @Test
    @DisplayName("★★ 번호 범위로 형태를 일괄 지정한다 — 한 구역에 교실형·집중형이 섞인다")
    void styleRangesSplitOneArea() {
        StudyArea areaC = area(main.getId(), "C");

        List<SeatMaster> seats = seatMasterAdminService.createGrid(admin, spec(areaC, 1, 12)
                .startNumber(201)
                .styleRanges(List.of(
                        // 광명 C 앞부분: 201~212 집중 · 213~253 교실
                        new StyleRange(201, 212, SeatStyle.FOCUS),
                        new StyleRange(213, 253, SeatStyle.CLASSROOM)))
                .build());

        assertThat(seats).extracting(SeatMaster::getSeatStyle)
                .containsOnly(SeatStyle.FOCUS);

        // 213부터는 교실형이다 — 같은 구역, 같은 격자인데 형태가 갈린다
        List<SeatMaster> next = seatMasterAdminService.createGrid(admin, spec(areaC, 1, 3)
                .startNumber(213)
                .startY(2)
                .styleRanges(List.of(
                        new StyleRange(201, 212, SeatStyle.FOCUS),
                        new StyleRange(213, 253, SeatStyle.CLASSROOM)))
                .build());
        assertThat(next).extracting(SeatMaster::getSeatStyle)
                .containsOnly(SeatStyle.CLASSROOM);
    }

    @Test
    @DisplayName("범위에 안 든 번호는 기본값(교실형)이다")
    void outsideRangeFallsBackToDefault() {
        StudyArea areaA = area(main.getId(), "A");

        List<SeatMaster> seats = seatMasterAdminService.createGrid(admin, spec(areaA, 1, 4)
                .startNumber(1)
                .styleRanges(List.of(new StyleRange(1, 2, SeatStyle.SINGLE_ROOM)))
                .build());

        assertThat(seats).extracting(SeatMaster::getSeatCd, SeatMaster::getSeatStyle)
                .containsExactly(
                        tuple("1", SeatStyle.SINGLE_ROOM), tuple("2", SeatStyle.SINGLE_ROOM),
                        tuple("3", SeatStyle.CLASSROOM), tuple("4", SeatStyle.CLASSROOM));
    }

    @Test
    @DisplayName("★ 범위가 겹치면 거부한다 — 먼저 쓴 쪽이 이기는 숨은 규칙을 만들지 않는다")
    void overlappingRangesRejected() {
        StudyArea areaA = area(main.getId(), "A");

        assertThatThrownBy(() -> seatMasterAdminService.createGrid(admin, spec(areaA, 1, 3)
                .startNumber(1)
                .styleRanges(List.of(
                        new StyleRange(1, 10, SeatStyle.FOCUS),
                        new StyleRange(5, 20, SeatStyle.CLASSROOM)))
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("겹칩니다");
    }

    /**
     * 이미 만들어 둔 좌석에 시트가 나중에 왔을 때의 경로.
     *
     * <p>좌석을 다시 만들지 않으므로 <b>배정 이력이 끊기지 않는다</b>.
     */
    @Test
    @DisplayName("★ 기존 좌석에 형태를 범위로 입힌다 — 범위 밖은 건드리지 않는다")
    void applyStylesToExistingSeats() {
        StudyArea areaA = area(main.getId(), "A");
        seatMasterAdminService.createGrid(admin, spec(areaA, 1, 5).startNumber(1).build());
        em.flush();

        int changed = seatMasterAdminService.applyStyles(admin, areaA.getId(),
                List.of(new StyleRange(2, 3, SeatStyle.FOCUS)), null);

        assertThat(changed).isEqualTo(2);
        assertThat(seatMasterAdminService.list(admin, areaA.getId()))
                .extracting(SeatMaster::getSeatCd, SeatMaster::getSeatStyle)
                .contains(
                        tuple("1", SeatStyle.CLASSROOM),
                        tuple("2", SeatStyle.FOCUS),
                        tuple("3", SeatStyle.FOCUS),
                        tuple("4", SeatStyle.CLASSROOM));
    }

    @Test
    @DisplayName("접두어가 붙은 좌석번호도 끝 숫자로 범위에 든다")
    void prefixedSeatCdStillMatchesRange() {
        StudyArea areaA = area(main.getId(), "A");
        seatMasterAdminService.createGrid(admin, spec(areaA, 1, 3)
                .startNumber(1).seatCdPrefix("A-").numberPadding(2).build());
        em.flush();

        int changed = seatMasterAdminService.applyStyles(admin, areaA.getId(),
                List.of(new StyleRange(1, 2, SeatStyle.SINGLE_ROOM)), null);

        assertThat(changed).isEqualTo(2);
        assertThat(seatMasterAdminService.list(admin, areaA.getId()))
                .extracting(SeatMaster::getSeatCd, SeatMaster::getSeatStyle)
                .contains(tuple("A-01", SeatStyle.SINGLE_ROOM),
                        tuple("A-03", SeatStyle.CLASSROOM));
    }

    // ── 준비물 ───────────────────────────────────────────────────────────────

    private StudyArea area(Long buildingId, String areaCd) {
        return studyAreaAdminService.create(
                admin, academy.getId(), buildingId, areaCd, areaCd + "구역", (short) 1);
    }

    private List<GridSkip> cells(int row, int... columns) {
        List<GridSkip> skips = new ArrayList<>();
        for (int column : columns) {
            skips.add(new GridSkip(row, column));
        }
        return skips;
    }

    private SpecBuilder spec(StudyArea area, int rows, int columns) {
        return new SpecBuilder(area.getId(), rows, columns);
    }

    /** 격자 입력이 필드가 많아 테스트마다 전부 적으면 읽히지 않는다. */
    private static final class SpecBuilder {
        private final Long studyAreaId;
        private final int rows;
        private final int columns;
        private String seatCdPrefix = "";
        private int startNumber = 1;
        private int numberPadding = 0;
        private int startX = 1;
        private int startY = 1;
        private boolean columnMajor;
        private boolean reverseX;
        private boolean reverseY;
        private List<GridSkip> skips = List.of();
        private List<StyleRange> styleRanges = List.of();

        private SpecBuilder(Long studyAreaId, int rows, int columns) {
            this.studyAreaId = studyAreaId;
            this.rows = rows;
            this.columns = columns;
        }

        SpecBuilder seatCdPrefix(String v) {
            this.seatCdPrefix = v;
            return this;
        }

        SpecBuilder startNumber(int v) {
            this.startNumber = v;
            return this;
        }

        SpecBuilder numberPadding(int v) {
            this.numberPadding = v;
            return this;
        }

        SpecBuilder startX(int v) {
            this.startX = v;
            return this;
        }

        SpecBuilder startY(int v) {
            this.startY = v;
            return this;
        }

        SpecBuilder columnMajor(boolean v) {
            this.columnMajor = v;
            return this;
        }

        SpecBuilder reverseX(boolean v) {
            this.reverseX = v;
            return this;
        }

        SpecBuilder reverseY(boolean v) {
            this.reverseY = v;
            return this;
        }

        SpecBuilder skips(List<GridSkip> v) {
            this.skips = v;
            return this;
        }

        SpecBuilder styleRanges(List<StyleRange> v) {
            this.styleRanges = v;
            return this;
        }

        SeatGridSpec build() {
            return new SeatGridSpec(studyAreaId, rows, columns, seatCdPrefix, startNumber,
                    numberPadding, startX, startY, columnMajor, reverseX, reverseY, skips,
                    SeatStyle.CLASSROOM, styleRanges);
        }
    }
}
