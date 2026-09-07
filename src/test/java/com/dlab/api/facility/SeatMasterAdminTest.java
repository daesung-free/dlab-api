package com.dlab.api.facility;

import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 구역·좌석 등록 (관리자).
 *
 * <p>조회·배정만 있고 <b>만드는 경로가 없어서</b> 배정 관리·좌석배치표·좌석 이탈 화면이
 * 셋 다 막혀 있던 것을 푼 API다.
 */
@SpringBootTest
@Transactional
class SeatMasterAdminTest {

    private static final String PASSWORD = "seat-master-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Academy otherAcademy;
    Long otherAreaId;
    Long enrollmentA;
    Long enrollmentB;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("SM01", "좌석마스터지점", LocalTime.of(9, 0));
        em.persist(academy);
        otherAcademy = new Academy("SM02", "다른지점", LocalTime.of(9, 0));
        em.persist(otherAcademy);

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "SMADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        enrollmentA = createStudent(academy, "학생가", "SMSTU01", "0001");
        enrollmentB = createStudent(academy, "학생나", "SMSTU02", "0002");

        // 다른 지점 구역 — 지점 격리 검증용
        em.createNativeQuery("""
                        INSERT INTO study_area (academy_id, area_cd, area_nm, sort_order)
                        VALUES (:aid, 'X', '남의구역', 1)
                        """)
                .setParameter("aid", otherAcademy.getId()).executeUpdate();
        otherAreaId = ((Number) em.createNativeQuery(
                        "SELECT id FROM study_area WHERE academy_id = :aid")
                .setParameter("aid", otherAcademy.getId()).getSingleResult()).longValue();
        em.flush();
    }

    private Long createStudent(Academy target, String name, String code, String no) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, target, (short) 2026, no, null, GradeType.N_SU);
        em.persist(e);
        em.flush();
        return e.getId();
    }

    private void grantRole(Long accountId, String roleName) {
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :roleName
                        """)
                .setParameter("accountId", accountId).setParameter("roleName", roleName)
                .executeUpdate();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"SMADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private Long createArea(String areaCd, String areaNm) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/seats/areas")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"areaCd":"%s","areaNm":"%s","sortOrder":1}"""
                                .formatted(areaCd, areaNm)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    // ── 구역 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("구역을 만들면 목록에 뜬다")
    void createAndListArea() throws Exception {
        createArea("A", "자습실A");
        em.flush();

        mvc.perform(get("/api/v1/admin/seats/areas").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].areaCd").value("A"))
                .andExpect(jsonPath("$.data[0].seatCount").value(0));
    }

    @Test
    @DisplayName("★ 같은 지점에 같은 구역 코드는 두 번 못 넣는다")
    void duplicateAreaCdRejected() throws Exception {
        createArea("A", "자습실A");
        em.flush();

        mvc.perform(post("/api/v1/admin/seats/areas")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"areaCd":"A","areaNm":"중복","sortOrder":2}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STUDY_AREA_DUPLICATED"));
    }

    @Test
    @DisplayName("비활성 구역은 기본 목록에서 빠지고 includeInactive면 다시 보인다")
    void inactiveAreaHiddenButManageable() throws Exception {
        Long areaId = createArea("A", "자습실A");
        em.flush();

        mvc.perform(patch("/api/v1/admin/seats/areas/{id}", areaId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}"""))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/seats/areas").header("Authorization", token()))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/admin/seats/areas").header("Authorization", token())
                        .param("includeInactive", "true"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].active").value(false));
    }

    @Test
    @DisplayName("★ 좌석이 남아 있는 구역은 삭제할 수 없다")
    void areaWithSeatsNotDeletable() throws Exception {
        Long areaId = createArea("A", "자습실A");
        createGrid(areaId, 1, 2, "A-");
        em.flush();

        mvc.perform(delete("/api/v1/admin/seats/areas/{id}", areaId)
                        .header("Authorization", token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STUDY_AREA_HAS_SEATS"));
    }

    @Test
    @DisplayName("★ 다른 지점 구역은 건드릴 수 없다")
    void otherBranchAreaDenied() throws Exception {
        mvc.perform(patch("/api/v1/admin/seats/areas/{id}", otherAreaId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"areaNm":"뺏기"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("OTHER_BRANCH_ACCESS_DENIED"));

        mvc.perform(post("/api/v1/admin/seats/masters")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studyAreaId":%d,"seatCd":"X-01","xPos":1,"yPos":1}"""
                                .formatted(otherAreaId)))
                .andExpect(status().isForbidden());
    }

    // ── 좌석 ────────────────────────────────────────────────────────────────

    private String createGrid(Long areaId, int rows, int cols, String prefix) throws Exception {
        return mvc.perform(post("/api/v1/admin/seats/masters/grid")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studyAreaId":%d,"rows":%d,"columns":%d,"seatCdPrefix":"%s"}"""
                                .formatted(areaId, rows, cols, prefix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★ 격자로 4행×5열을 한 번에 만든다 — 좌표와 번호가 순서대로 붙는다")
    void createGridSeats() throws Exception {
        Long areaId = createArea("A", "자습실A");

        mvc.perform(post("/api/v1/admin/seats/masters/grid")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studyAreaId":%d,"rows":4,"columns":5,"seatCdPrefix":"A-"}"""
                                .formatted(areaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(20))
                .andExpect(jsonPath("$.data[0].seatCd").value("A-01"))
                .andExpect(jsonPath("$.data[0].xPos").value(1))
                .andExpect(jsonPath("$.data[0].yPos").value(1))
                // 가로 우선이므로 6번째가 둘째 줄 첫 칸이다
                .andExpect(jsonPath("$.data[5].seatCd").value("A-06"))
                .andExpect(jsonPath("$.data[5].xPos").value(1))
                .andExpect(jsonPath("$.data[5].yPos").value(2))
                .andExpect(jsonPath("$.data[19].seatCd").value("A-20"));
    }

    @Test
    @DisplayName("★ 통로(skips)는 좌석을 만들지 않고 번호는 건너뛰고 이어진다")
    void gridWithSkips() throws Exception {
        Long areaId = createArea("A", "자습실A");

        mvc.perform(post("/api/v1/admin/seats/masters/grid")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studyAreaId":%d,"rows":2,"columns":3,"seatCdPrefix":"A-",
                                 "skips":[{"row":1,"column":2}]}"""
                                .formatted(areaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                // 1행 2열이 빠졌으므로 A-02는 1행 3열이다 — 번호에 구멍이 생기지 않는다
                .andExpect(jsonPath("$.data[1].seatCd").value("A-02"))
                .andExpect(jsonPath("$.data[1].xPos").value(3))
                .andExpect(jsonPath("$.data[1].yPos").value(1));
    }

    @Test
    @DisplayName("★ 같은 지점에 같은 좌석번호는 두 번 못 넣는다 — 겹친 코드를 전부 알려준다")
    void duplicateSeatCdRejected() throws Exception {
        Long areaA = createArea("A", "자습실A");
        Long areaB = createArea("B", "별관B");
        createGrid(areaA, 1, 3, "A-");
        em.flush();

        // 별관이 본관과 같은 번호대를 쓰면 걸린다. 스키마에 「관」축이 없어
        // UNIQUE (academy_id, seat_cd)가 구역을 넘어 전 지점에 걸리기 때문이다
        String body = mvc.perform(post("/api/v1/admin/seats/masters/grid")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studyAreaId":%d,"rows":1,"columns":3,"seatCdPrefix":"A-"}"""
                                .formatted(areaB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_CD_DUPLICATED"))
                .andReturn().getResponse().getContentAsString();

        // 하나씩이 아니라 겹친 코드가 전부 들어 있어야 고쳐 올릴 수 있다
        String message = objectMapper.readTree(body).path("error").path("message").asString();
        assertThat(message).contains("A-01").contains("A-02").contains("A-03");

        // 전부-아니면-전무 — 별관 좌석은 하나도 안 들어갔다
        Long count = em.createQuery("""
                SELECT COUNT(s) FROM SeatMaster s WHERE s.studyArea.id = :id AND s.deleted = false
                """, Long.class).setParameter("id", areaB).getSingleResult();
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("좌석 이름·좌표를 고칠 수 있다")
    void updateSeat() throws Exception {
        Long areaId = createArea("A", "자습실A");
        Long seatId = firstSeatId(createGrid(areaId, 1, 1, "A-"));

        mvc.perform(patch("/api/v1/admin/seats/masters/{id}", seatId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatNm":"창가석","xPos":7,"yPos":9}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seatNm").value("창가석"))
                .andExpect(jsonPath("$.data.xPos").value(7))
                // 코드는 키오스크 계약이라 그대로다
                .andExpect(jsonPath("$.data.seatCd").value("A-01"));
    }

    @Test
    @DisplayName("★ 좌석 삭제는 soft delete고 배정 중이면 거부된다")
    void deleteSeat() throws Exception {
        Long areaId = createArea("A", "자습실A");
        Long seatId = firstSeatId(createGrid(areaId, 1, 2, "A-"));

        assign(seatId, enrollmentA);
        em.flush();

        mvc.perform(delete("/api/v1/admin/seats/masters/{id}", seatId)
                        .header("Authorization", token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_ALREADY_OCCUPIED"));

        mvc.perform(delete("/api/v1/admin/seats/students/{id}", enrollmentA)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(delete("/api/v1/admin/seats/masters/{id}", seatId)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        // 행은 남고 플래그만 선다 — 배정 이력이 이 좌석을 참조한다
        Boolean deleted = (Boolean) em.createNativeQuery(
                        "SELECT is_deleted FROM seat_master WHERE id = :id")
                .setParameter("id", seatId).getSingleResult();
        assertThat(deleted).isTrue();
    }

    @Test
    @DisplayName("지운 좌석과 같은 번호로 다시 등록하면 그 좌석이 되살아난다")
    void recreateDeletedSeatCd() throws Exception {
        Long areaId = createArea("A", "자습실A");
        Long seatId = firstSeatId(createGrid(areaId, 1, 1, "A-"));

        mvc.perform(delete("/api/v1/admin/seats/masters/{id}", seatId)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();

        // UNIQUE (academy_id, seat_cd)가 부분 인덱스가 아니라 삭제분도 코드를 붙들고 있다.
        // 새 행을 넣으려 하면 제약 위반이 그대로 500이 되므로 되살리는 쪽으로 간다
        mvc.perform(post("/api/v1/admin/seats/masters")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studyAreaId":%d,"seatCd":"A-01","seatNm":"부활","xPos":3,"yPos":4}"""
                                .formatted(areaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(seatId))
                .andExpect(jsonPath("$.data.seatNm").value("부활"));
    }

    // ── 일괄 배정 ───────────────────────────────────────────────────────────

    private void assign(Long seatId, Long enrollmentId) throws Exception {
        mvc.perform(post("/api/v1/admin/seats")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatId":%d,"enrollmentId":%d}"""
                                .formatted(seatId, enrollmentId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 일괄 배정 — 여러 건이 한 번에 들어간다")
    void bulkAssign() throws Exception {
        Long areaId = createArea("A", "자습실A");
        String grid = createGrid(areaId, 1, 2, "A-");
        Long seat1 = seatIdAt(grid, 0);
        Long seat2 = seatIdAt(grid, 1);

        mvc.perform(post("/api/v1/admin/seats/bulk")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"seatId":%d,"enrollmentId":%d},
                                          {"seatId":%d,"enrollmentId":%d}]}"""
                                .formatted(seat1, enrollmentA, seat2, enrollmentB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        em.flush();

        Long active = em.createQuery("""
                SELECT COUNT(a) FROM SeatAssignment a WHERE a.releasedAt IS NULL
                """, Long.class).getSingleResult();
        assertThat(active).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 일괄 배정은 하나라도 실패하면 전부 취소된다 — 절반만 남지 않는다")
    void bulkAssignIsAllOrNothing() throws Exception {
        Long areaId = createArea("A", "자습실A");
        String grid = createGrid(areaId, 1, 2, "A-");
        Long seat1 = seatIdAt(grid, 0);
        Long seat2 = seatIdAt(grid, 1);

        // 2번 자리에는 이 요청에 없는 학생이 이미 앉아 있다
        Long other = createStudent(academy, "선점학생", "SMSTU03", "0003");
        assign(seat2, other);
        em.flush();

        mvc.perform(post("/api/v1/admin/seats/bulk")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"seatId":%d,"enrollmentId":%d},
                                          {"seatId":%d,"enrollmentId":%d}]}"""
                                .formatted(seat1, enrollmentA, seat2, enrollmentB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_ASSIGN_PARTIAL_FAILED"));

        // 성공했을 1번 자리도 들어가지 않았다
        Long assignedToA = em.createQuery("""
                SELECT COUNT(a) FROM SeatAssignment a
                WHERE a.enrollment.id = :id AND a.releasedAt IS NULL
                """, Long.class).setParameter("id", enrollmentA).getSingleResult();
        assertThat(assignedToA).isZero();
    }

    private Long firstSeatId(String gridResponse) {
        return seatIdAt(gridResponse, 0);
    }

    private Long seatIdAt(String gridResponse, int index) {
        return objectMapper.readTree(gridResponse).path("data").get(index).path("id").asLong();
    }
}
