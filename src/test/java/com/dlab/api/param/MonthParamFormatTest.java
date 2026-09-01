package com.dlab.api.param;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code month} 파라미터 형식 통일 (프론트 보고 1-4).
 *
 * <p>같은 이름의 파라미터인데 엔드포인트마다 {@code 2026-09}와 {@code 9}로 갈렸다.
 * <b>전부 {@code yyyy-MM}</b>이다 — 연도를 따로 보내지 않아도 되고, "9가 어느 해냐"는
 * 오해가 없다.
 *
 * <p>여기서 지키는 것은 <b>형식 자체</b>다. 도메인 동작은 각 도메인 테스트가 본다.
 */
@SpringBootTest
@Transactional
class MonthParamFormatTest {

    private static final String PASSWORD = "month-param-password-1234";
    private static final short YEAR = 2026;

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    String studentPhone = "010-4200-0001";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("MP01", "월파라미터지점", LocalTime.of(9, 0));
        em.persist(academy);
        academyId = academy.getId();

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "MPADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId()).executeUpdate();

        Student student = new Student("MPSTU001", "월파라미터학생", studentPhone);
        em.persist(student);
        em.persist(new StudentEnrollment(student, academy, YEAR, "2026-0001", null,
                GradeType.N_SU));
        Account studentAccount = Account.forStudent(student, studentPhone,
                passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);
        em.flush();
    }

    private String token(String loginId, String path) throws Exception {
        String body = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private String adminToken() throws Exception {
        return token("MPADM", "/api/v1/admin/auth/login");
    }

    private String appToken() throws Exception {
        return token(studentPhone, "/api/v1/app/auth/login");
    }

    /**
     * <b>정수 월은 받지 않는다.</b>
     *
     * <p>거부만 확인하고 상태코드를 못 박지 않는다 — 타입 불일치를 400으로 바꾸는
     * 핸들러가 별도 작업으로 들어오는 중이라, 여기서 코드를 고정하면 그 작업과 충돌한다.
     * 지켜야 할 것은 <b>정수 월이 조용히 통과하지 않는 것</b>이다.
     */
    private void assertRejected(org.springframework.test.web.servlet.ResultActions result)
            throws Exception {
        assertThat(result.andReturn().getResponse().getStatus())
                .isGreaterThanOrEqualTo(400);
    }

    // ── 데일리 루틴 ────────────────────────────────────────────

    @Test
    @DisplayName("관리자 루틴 목록 — yyyy-MM 이면 통과, 정수 월은 거부")
    void adminRoutines() throws Exception {
        mvc.perform(get("/api/v1/admin/routines")
                        .header("Authorization", adminToken())
                        .param("academyId", String.valueOf(academyId))
                        .param("month", "2026-09"))
                .andExpect(status().isOk());

        assertRejected(mvc.perform(get("/api/v1/admin/routines")
                .header("Authorization", adminToken())
                .param("academyId", String.valueOf(academyId))
                .param("month", "9")));
    }

    // ── 정기일정 ──────────────────────────────────────────────

    @Test
    @DisplayName("관리자 정기일정 목록 — yyyy-MM 이면 통과, 정수 월은 거부")
    void adminSchedules() throws Exception {
        mvc.perform(get("/api/v1/admin/schedules")
                        .header("Authorization", adminToken())
                        .param("academyId", String.valueOf(academyId))
                        .param("month", "2026-09"))
                .andExpect(status().isOk());

        assertRejected(mvc.perform(get("/api/v1/admin/schedules")
                .header("Authorization", adminToken())
                .param("academyId", String.valueOf(academyId))
                .param("month", "9")));
    }

    @Test
    @DisplayName("앱 정기일정 — yyyy-MM 이면 통과, 정수 월은 거부")
    void appSchedules() throws Exception {
        mvc.perform(get("/api/v1/app/schedules")
                        .header("Authorization", appToken())
                        .param("month", "2026-09"))
                .andExpect(status().isOk());

        assertRejected(mvc.perform(get("/api/v1/app/schedules")
                .header("Authorization", appToken())
                .param("month", "9")));
    }

    /** 월을 비우면 이번 달이다 — 연도를 따로 받지 않으므로 기수 연도로 채운다. */
    @Test
    @DisplayName("앱 정기일정 — 월을 비우면 이번 달")
    void appSchedulesDefaultsToThisMonth() throws Exception {
        mvc.perform(get("/api/v1/app/schedules")
                        .header("Authorization", appToken()))
                .andExpect(status().isOk());
    }

    // ── 교습비 단가표 ──────────────────────────────────────────

    @Test
    @DisplayName("교습비 단가표 — yyyy-MM 이면 통과, 정수 월은 거부")
    void tuitionFeeTable() throws Exception {
        mvc.perform(get("/api/v1/admin/tuition/fee-table")
                        .header("Authorization", adminToken())
                        .param("academyId", String.valueOf(academyId))
                        .param("month", "2026-02")
                        .param("gradeType", "N_SU")
                        .param("seatType", "GENERAL"))
                .andExpect(status().isOk());

        assertRejected(mvc.perform(get("/api/v1/admin/tuition/fee-table")
                .header("Authorization", adminToken())
                .param("academyId", String.valueOf(academyId))
                .param("month", "2")
                .param("gradeType", "N_SU")
                .param("seatType", "GENERAL")));
    }
}
