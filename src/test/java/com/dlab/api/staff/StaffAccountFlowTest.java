package com.dlab.api.staff;

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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 직원·선생님 계정 관리.
 *
 * <p>지금까지 계정을 SQL로만 만들 수 있었다. 이 API가 그 자리를 메운다.
 * 핵심은 <b>만든 계정으로 실제 로그인이 되고 역할이 토큰에 실리는지</b>다.
 */
@SpringBootTest
@Transactional
class StaffAccountFlowTest {

    private static final String PASSWORD = "staff-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    Long otherAcademyId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("SF01", "직원테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        Academy other = new Academy("SF02", "다른지점", LocalTime.of(9, 0));
        em.persist(other);

        Employee admin = new Employee(academy, "지점관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "SFADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        academyId = academy.getId();
        otherAcademyId = other.getId();
    }

    private void grantRole(Long accountId, String roleName) {
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :roleName
                        """)
                .setParameter("accountId", accountId).setParameter("roleName", roleName)
                .executeUpdate();
    }

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    @Test
    @DisplayName("★ 선생님을 등록하면 그 계정으로 바로 로그인된다")
    void createTeacherAndLogin() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/teachers")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"새담임","phone":"010-1111-1111",
                                 "email":"t@dlab.kr","loginId":"NEWT","password":"%s","roles":["TEACHER"]}"""
                                .formatted(academyId, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("TEACHER"));
        em.flush();

        // 계정과 사람을 따로 만들면 "담임 지정은 되는데 로그인은 안 되는" 상태가 생긴다
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"NEWT","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 역할이 토큰에 실려야 승인 API를 쓸 수 있다
        String access = objectMapper.readTree(body).path("data").path("accessToken").asString();
        mvc.perform(get("/api/v1/admin/approvals").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("직원을 등록하고 목록에서 조회한다")
    void createEmployee() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"새행정","deptName":"교무부","positionName":"주임",
                                 "loginId":"NEWE","password":"%s","roles":["STAFF"]}"""
                                .formatted(academyId, PASSWORD)))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/staff/employees").header("Authorization", token("SFADM"))
                        .param("academyId", academyId.toString()))
                .andExpect(jsonPath("$.data[?(@.name=='새행정')].deptName").value("교무부"));
    }

    @Test
    @DisplayName("로그인 아이디가 중복되면 거부한다")
    void duplicateLoginId() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/teachers")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"중복","loginId":"SFADM","password":"%s","roles":["TEACHER"]}"""
                                .formatted(academyId, PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 다른 지점에는 계정을 만들 수 없다")
    void cannotCreateInOtherAcademy() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/teachers")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"남의지점","loginId":"OTHERT","password":"%s","roles":["TEACHER"]}"""
                                .formatted(otherAcademyId, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 지점 관리자는 SUPER_ADMIN 권한을 부여할 수 없다")
    void branchAdminCannotGrantSuperAdmin() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"승격대상","loginId":"PROMO","password":"%s","roles":["STAFF"]}"""
                                .formatted(academyId, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        em.flush();

        Long accountId = em.createQuery(
                        "SELECT a.id FROM Account a WHERE a.loginId = 'PROMO'", Long.class)
                .getSingleResult();

        // 스스로를 상위 관리자로 승격하는 경로를 막는다
        mvc.perform(put("/api/v1/admin/staff/accounts/{id}/roles", accountId)
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roles":["SUPER_ADMIN"]}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("역할을 교체하면 이전 역할은 사라진다")
    void replaceRoles() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/employees")
                .header("Authorization", token("SFADM"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"academyId":%d,"name":"역할변경","loginId":"ROLEC","password":"%s","roles":["STAFF"]}"""
                        .formatted(academyId, PASSWORD)));
        em.flush();

        Long accountId = em.createQuery(
                        "SELECT a.id FROM Account a WHERE a.loginId = 'ROLEC'", Long.class)
                .getSingleResult();

        mvc.perform(put("/api/v1/admin/staff/accounts/{id}/roles", accountId)
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roles":["READONLY"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("READONLY"));
    }
}
