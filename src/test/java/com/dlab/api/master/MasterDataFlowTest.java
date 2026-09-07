package com.dlab.api.master;

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

/** 기초 마스터 — 학과 · 계열 · 사물함 · 장학. */
@SpringBootTest
@Transactional
class MasterDataFlowTest {

    private static final String PASSWORD = "master-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    Long enrollmentId;
    Long otherEnrollmentId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("MA01", "마스터테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "MAADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        enrollmentId = createStudent(academy, "학생일", "MASTU01", "0001");
        otherEnrollmentId = createStudent(academy, "학생이", "MASTU02", "0002");
        academyId = academy.getId();
    }

    private Long createStudent(Academy academy, String name, String code, String no) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(student, academy, (short) 2026, no, null, GradeType.N_SU);
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
                                {"loginId":"MAADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private long createLocker(String no) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/masters/lockers")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"lockerNo":"%s"}""".formatted(academyId, no)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("학과를 만들고 목록에서 조회한다")
    void createDepartment() throws Exception {
        mvc.perform(post("/api/v1/admin/masters/departments")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"자연계열"}""".formatted(academyId)))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/masters/departments").header("Authorization", token())
                        .param("year", "2026"))
                .andExpect(jsonPath("$.data[0].name").value("자연계열"));
    }

    @Test
    @DisplayName("★ 학과 삭제는 soft delete — 과거 기록의 학과명이 남아야 한다")
    void departmentSoftDelete() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/masters/departments")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"사라질과"}""".formatted(academyId)))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("data").path("id").asLong();
        em.flush();

        mvc.perform(delete("/api/v1/admin/masters/departments/{id}", id)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();

        // 목록에서는 빠지지만 행은 남아 있다
        mvc.perform(get("/api/v1/admin/masters/departments").header("Authorization", token())
                        .param("year", "2026"))
                .andExpect(jsonPath("$.data").isEmpty());

        Long rowCount = em.createQuery(
                        "SELECT COUNT(d) FROM DepartmentMaster d WHERE d.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 계열은 전 지점 공통이라 지점 관리자가 추가할 수 없다")
    void branchAdminCannotCreateTrack() throws Exception {
        mvc.perform(post("/api/v1/admin/masters/tracks")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"신규계열"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 이미 배정된 사물함에는 다른 학생을 넣을 수 없다")
    void occupiedLockerRejected() throws Exception {
        long lockerId = createLocker("A-01");
        em.flush();

        mvc.perform(put("/api/v1/admin/masters/lockers/{id}/assignment", lockerId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(enrollmentId)))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(put("/api/v1/admin/masters/lockers/{id}/assignment", lockerId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(otherEnrollmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOCKER_ALREADY_OCCUPIED"));
    }

    @Test
    @DisplayName("★ 학생이 다른 사물함을 받으면 이전 것은 자동으로 비워진다")
    void reassignLockerReleasesPrevious() throws Exception {
        long first = createLocker("B-01");
        long second = createLocker("B-02");
        em.flush();

        mvc.perform(put("/api/v1/admin/masters/lockers/{id}/assignment", first)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"enrollmentId":%d}""".formatted(enrollmentId)));
        em.flush();

        mvc.perform(put("/api/v1/admin/masters/lockers/{id}/assignment", second)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(enrollmentId)))
                .andExpect(status().isOk());
        em.flush();

        // 한 명이 사물함 두 개를 점유하면 실물 열쇠와 어긋난다
        Long occupied = em.createQuery("""
                SELECT COUNT(l) FROM LockerMaster l WHERE l.assignedEnrollment.id = :id
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        assertThat(occupied).isEqualTo(1);
    }

    // ── 강의실 ──

    @Test
    @DisplayName("강의실을 만들고 목록에서 조회한다")
    void createRoom() throws Exception {
        mvc.perform(post("/api/v1/admin/masters/rooms")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"roomNo":"201","name":"대강의실","capacity":40}"""
                                .formatted(academyId)))
                .andExpect(jsonPath("$.data.roomNo").value("201"))
                .andExpect(jsonPath("$.data.capacity").value(40))
                .andExpect(jsonPath("$.data.active").value(true));
        em.flush();

        mvc.perform(get("/api/v1/admin/masters/rooms").header("Authorization", token())
                        .param("academyId", academyId.toString()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("같은 지점에 같은 번호의 강의실은 못 만든다")
    void duplicateRoomNo() throws Exception {
        createRoom("201");

        mvc.perform(post("/api/v1/admin/masters/rooms")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"roomNo":"201"}""".formatted(academyId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 지운 강의실 번호는 다시 쓸 수 있다 — 부분 인덱스로 바꾼 이유다")
    void deletedRoomNoReusable() throws Exception {
        long id = createRoom("201");

        mvc.perform(delete("/api/v1/admin/masters/rooms/{id}", id)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();

        // 전체 유니크였다면 여기서 제약 위반으로 막힌다 —
        // 화면에는 안 보이는 방 때문에 "없는 방을 못 만드는" 상태가 된다
        mvc.perform(post("/api/v1/admin/masters/rooms")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"roomNo":"201"}""".formatted(academyId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("중지한 강의실은 목록에 남고 상태필터로 갈린다")
    void roomActiveFilter() throws Exception {
        long id = createRoom("201");
        createRoom("202");

        mvc.perform(patch("/api/v1/admin/masters/rooms/{id}/active", id)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}"""))
                .andExpect(jsonPath("$.data.active").value(false));
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/masters/rooms").header("Authorization", token())
                        .param("academyId", academyId.toString()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/admin/masters/rooms").header("Authorization", token())
                        .param("academyId", academyId.toString()).param("active", "true"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomNo").value("202"));
    }

    private long createRoom(String roomNo) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/masters/rooms")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"roomNo":"%s"}""".formatted(academyId, roomNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    // ── 필수 파라미터 누락 ──

    @Test
    @DisplayName("★ 필수 파라미터를 빼면 400이다 — 그동안 500 INTERNAL_ERROR가 나갔다")
    void missingRequiredParameter() throws Exception {
        mvc.perform(get("/api/v1/admin/masters/course-types").header("Authorization", token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("academyId")));
    }

    @Test
    @DisplayName("값이 틀린 경우는 원래대로 400이다 — 누락과 같은 계열로 보여야 한다")
    void invalidParameterValue() throws Exception {
        mvc.perform(get("/api/v1/admin/masters/course-types").header("Authorization", token())
                        .param("academyId", academyId.toString()).param("year", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ── 마스터 공통 속성 (code · memo · active) ──

    private void createDepartment(String name, String code) throws Exception {
        mvc.perform(post("/api/v1/admin/masters/departments")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"%s","code":%s}"""
                                .formatted(academyId, name,
                                        code == null ? "null" : "\"" + code + "\"")))
                .andExpect(status().isOk());
        em.flush();
    }

    @Test
    @DisplayName("★ 코드는 대문자로 정리된다 — 대조에 쓰이는 값이라 a와 A가 갈리면 안 된다")
    void masterCodeNormalized() throws Exception {
        mvc.perform(post("/api/v1/admin/masters/departments")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"자연계열","code":" sci ","memo":"비고"}"""
                                .formatted(academyId)))
                .andExpect(jsonPath("$.data.code").value("SCI"))
                .andExpect(jsonPath("$.data.memo").value("비고"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("★ 같은 지점·연도에서 코드가 겹치면 409 — DB 제약에만 맡기면 500이 나간다")
    void masterCodeDuplicate() throws Exception {
        createDepartment("자연계열", "SCI");

        mvc.perform(post("/api/v1/admin/masters/departments")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"인문계열","code":"sci"}"""
                                .formatted(academyId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MASTER_CODE_DUPLICATED"));
    }

    @Test
    @DisplayName("코드 없는 마스터는 여럿 만들 수 있다 — 부분 인덱스라 NULL은 제약을 안 받는다")
    void masterCodeNullable() throws Exception {
        createDepartment("가", null);
        createDepartment("나", null);

        mvc.perform(get("/api/v1/admin/masters/departments").header("Authorization", token())
                        .param("year", "2026"))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("★ 중지해도 목록에는 남는다 — 삭제와 다르다")
    void masterActiveFilter() throws Exception {
        createDepartment("사용중", "ON");
        createDepartment("중지", "OFF");

        Long offId = em.createQuery("""
                SELECT d.id FROM DepartmentMaster d WHERE d.name = '중지'
                """, Long.class).getSingleResult();

        mvc.perform(patch("/api/v1/admin/masters/departments/{id}/active", offId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}"""))
                .andExpect(jsonPath("$.data.active").value(false));
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/masters/departments").header("Authorization", token())
                        .param("year", "2026"))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/admin/masters/departments").header("Authorization", token())
                        .param("year", "2026").param("active", "true"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("사용중"));
        mvc.perform(get("/api/v1/admin/masters/departments").header("Authorization", token())
                        .param("year", "2026").param("active", "false"))
                .andExpect(jsonPath("$.data[0].name").value("중지"));
    }

    @Test
    @DisplayName("코드를 빈 문자열로 보내면 지워진다 — 비우는 방법이 있어야 한다")
    void masterCodeCleared() throws Exception {
        createDepartment("자연계열", "SCI");
        Long id = em.createQuery("""
                SELECT d.id FROM DepartmentMaster d WHERE d.name = '자연계열'
                """, Long.class).getSingleResult();

        mvc.perform(put("/api/v1/admin/masters/departments/{id}", id)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"자연계열","code":""}"""))
                .andExpect(jsonPath("$.data.code").doesNotExist());
    }

    @Test
    @DisplayName("장학 할인율이 100을 넘으면 거부한다")
    void scholarshipRateBound() throws Exception {
        mvc.perform(post("/api/v1/admin/masters/scholarships")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d,"scholarshipType":"성적","discountRate":120.0}"""
                                .formatted(enrollmentId)))
                .andExpect(status().isBadRequest());
    }

    /** 부여 전에 장학 종류가 마스터에 있어야 한다. */
    private void registerScholarshipMaster(String code, String rate) throws Exception {
        mvc.perform(post("/api/v1/admin/masters/scholarship-masters")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"code":"%s","name":"%s","discountRate":%s}"""
                                .formatted(academyId, code, code, rate)))
                .andExpect(status().isOk());
        em.flush();
    }

    @Test
    @DisplayName("장학을 부여하고 조회한다 — 할인율은 마스터에서 온다")
    void grantScholarship() throws Exception {
        registerScholarshipMaster("MERIT", "30.5");

        mvc.perform(post("/api/v1/admin/masters/scholarships")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d,"scholarshipType":"MERIT"}"""
                                .formatted(enrollmentId)))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/masters/scholarships").header("Authorization", token())
                        .param("enrollmentId", enrollmentId.toString()))
                .andExpect(jsonPath("$.data[0].scholarshipType").value("MERIT"))
                .andExpect(jsonPath("$.data[0].discountRate").value(30.5));
    }

    @Test
    @DisplayName("★ 마스터에 없는 장학은 부여할 수 없다 — 통과시키면 취소 판정에서 조용히 빠진다")
    void grantRejectsUnknownType() throws Exception {
        mvc.perform(post("/api/v1/admin/masters/scholarships")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d,"scholarshipType":"KICE-50"}"""
                                .formatted(enrollmentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHOLARSHIP_MASTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("★ 화면이 보낸 할인율이 마스터와 다르면 막는다 — 조용히 바꿔치우지 않는다")
    void grantRejectsRateMismatch() throws Exception {
        registerScholarshipMaster("KICE_30", "30.00");

        mvc.perform(post("/api/v1/admin/masters/scholarships")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d,"scholarshipType":"KICE_30","discountRate":40.0}"""
                                .formatted(enrollmentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHOLARSHIP_RATE_MISMATCH"));
    }

    @Test
    @DisplayName("장학 코드는 소문자로 넣어도 대문자로 정리된다 — 규칙과 대조되는 값이다")
    void scholarshipMasterCodeNormalized() throws Exception {
        registerScholarshipMaster("kice_50", "50.00");

        mvc.perform(get("/api/v1/admin/masters/scholarship-masters")
                        .header("Authorization", token())
                        .param("year", "2026").param("academyId", academyId.toString()))
                .andExpect(jsonPath("$.data[0].code").value("KICE_50"));
    }

    @Test
    @DisplayName("중지한 장학은 고를 수 없다 — 이미 부여된 건은 남는다")
    void inactiveMasterNotSelectable() throws Exception {
        registerScholarshipMaster("OLD_50", "50.00");

        Long masterId = em.createQuery("""
                SELECT m.id FROM ScholarshipMaster m WHERE m.code = 'OLD_50'
                """, Long.class).getSingleResult();

        mvc.perform(patch("/api/v1/admin/masters/scholarship-masters/{id}/active", masterId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}"""))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        // 관리 목록에는 남고
        mvc.perform(get("/api/v1/admin/masters/scholarship-masters")
                        .header("Authorization", token())
                        .param("year", "2026").param("academyId", academyId.toString()))
                .andExpect(jsonPath("$.data.length()").value(1));
        // 부여 드롭다운에서는 빠진다
        mvc.perform(get("/api/v1/admin/masters/scholarship-masters/selectable")
                        .header("Authorization", token())
                        .param("year", "2026").param("academyId", academyId.toString()))
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(post("/api/v1/admin/masters/scholarships")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d,"scholarshipType":"OLD_50"}"""
                                .formatted(enrollmentId)))
                .andExpect(status().isNotFound());
    }
}
