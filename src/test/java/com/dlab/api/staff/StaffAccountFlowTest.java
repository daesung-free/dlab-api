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
        Account adminAccount = Account.forEmployee(admin, "SFADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        // 본사 — 지점이 만든 계정을 승인하는 쪽이다
        Employee hq = new Employee(academy, "본사관리자");
        em.persist(hq);
        Account hqAccount = Account.forEmployee(hq, "SFHQ", passwordEncoder.encode(PASSWORD), false);
        em.persist(hqAccount);
        em.flush();
        grantRole(hqAccount.getId(), "SUPER_ADMIN");

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
    @DisplayName("★ 지점이 만든 계정은 승인 전까지 로그인이 막힌다 — 본사가 승인해야 열린다")
    void branchCreatedAccountNeedsApproval() throws Exception {
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

        // 계정과 사람은 한 번에 만들어지지만, 지점이 만든 건 승인 전까지 못 쓴다
        mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"NEWT","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().is4xxClientError());

        Long accountId = ((Number) em.createNativeQuery(
                        "SELECT id FROM account WHERE login_id = 'NEWT'").getSingleResult())
                .longValue();

        // 승인은 본사만 — 지점이 자기가 만든 계정을 스스로 승인하면 절차가 무의미하다
        mvc.perform(post("/api/v1/admin/staff/accounts/" + accountId + "/approve")
                        .header("Authorization", token("SFADM")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/staff/accounts/" + accountId + "/approve")
                        .header("Authorization", token("SFHQ")))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

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
    @DisplayName("★★ 계정 목록에 로그인 아이디·상태·권한이 나온다 — 사람 목록만으론 사용자 관리 화면을 못 그린다")
    void accountListCarriesLoginAndRoles() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"목록행정","deptName":"교무부","positionName":"주임",
                                 "loginId":"LISTE","password":"%s","roles":["STAFF","READONLY"]}"""
                                .formatted(academyId, PASSWORD)))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/staff/accounts").header("Authorization", token("SFADM"))
                        .param("academyId", academyId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.loginId=='LISTE')].name").value("목록행정"))
                .andExpect(jsonPath("$.data[?(@.loginId=='LISTE')].accountType").value("EMPLOYEE"))
                // 지점 관리자가 만든 계정이라 승인 대기다 — 본사 승인 뒤 ACTIVE 가 된다
                .andExpect(jsonPath("$.data[?(@.loginId=='LISTE')].status").value("PENDING"))
                .andExpect(jsonPath("$.data[?(@.loginId=='LISTE')].deptName").value("교무부"))
                .andExpect(jsonPath("$.data[?(@.loginId=='LISTE')].locked").value(false))
                .andExpect(jsonPath("$.data[?(@.loginId=='LISTE')].roles.length()").value(2));
    }

    @Test
    @DisplayName("★★ 학생·학부모 계정은 목록에 안 나온다 — 섞이면 수백 건이 되어 관리자를 못 찾는다")
    void accountListExcludesStudentsAndParents() throws Exception {
        Student student = new Student("SFSTU01", "목록학생", "010-7000-0000");
        em.persist(student);
        Account studentAccount = Account.forStudent(student, "LISTSTU", passwordEncoder.encode(PASSWORD));
        em.persist(studentAccount);
        em.flush();

        mvc.perform(get("/api/v1/admin/staff/accounts").header("Authorization", token("SFADM"))
                        .param("academyId", academyId.toString()))
                .andExpect(jsonPath("$.data[?(@.loginId=='LISTSTU')]").isEmpty());
    }

    @Test
    @DisplayName("★ 지점 관리자는 다른 지점 계정을 볼 수 없다 — academyId를 바꿔 보내도 자기 지점이다")
    void accountListIsScopedToOwnAcademy() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"우리지점","loginId":"MINE","password":"%s",
                                 "roles":["STAFF"]}""".formatted(academyId, PASSWORD)))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/staff/accounts").header("Authorization", token("SFADM"))
                        .param("academyId", otherAcademyId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.loginId=='MINE')]").isNotEmpty());
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
