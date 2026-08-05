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
        Account adminAccount = Account.forEmployee(admin, "MAADM", passwordEncoder.encode(PASSWORD));
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

    @Test
    @DisplayName("장학을 부여하고 조회한다")
    void grantScholarship() throws Exception {
        mvc.perform(post("/api/v1/admin/masters/scholarships")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d,"scholarshipType":"성적우수","discountRate":30.5}"""
                                .formatted(enrollmentId)))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/masters/scholarships").header("Authorization", token())
                        .param("enrollmentId", enrollmentId.toString()))
                .andExpect(jsonPath("$.data[0].scholarshipType").value("성적우수"))
                .andExpect(jsonPath("$.data[0].discountRate").value(30.5));
    }
}
