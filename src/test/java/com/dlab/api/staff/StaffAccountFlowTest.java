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
    @DisplayName("★★ 조회 전용은 관리자 웹에서 아무것도 쓸 수 없다 — 휴일이 그대로 뚫려 있었다")
    void readonlyCannotWrite() throws Exception {
        Employee viewer = new Employee(em.find(Academy.class, academyId), "조회전용");
        em.persist(viewer);
        Account viewerAccount = Account.forEmployee(
                viewer, "SFRO", passwordEncoder.encode(PASSWORD), false);
        em.persist(viewerAccount);
        em.flush();
        grantRole(viewerAccount.getId(), "READONLY");
        String viewerToken = token("SFRO");

        // 휴일 — 역할 검사가 없어 조회 전용 계정이 등록·수정·삭제를 다 할 수 있었다
        mvc.perform(post("/api/v1/admin/holidays")
                        .header("Authorization", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"date":"2026-11-11","name":"조회전용이등록",
                                 "type":"ACADEMY"}""".formatted(academyId)))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/admin/holidays/{id}", 1))
                .andExpect(status().isUnauthorized());

        // 휴일만의 문제가 아니라 관리자 웹 전역이다
        mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"몰래등록","loginId":"SNEAK",
                                 "roles":["STAFF"]}""".formatted(academyId)))
                .andExpect(status().isForbidden());

        // 조회는 그대로 된다 — 막는 것은 쓰기뿐이다
        mvc.perform(get("/api/v1/admin/holidays")
                        .header("Authorization", viewerToken)
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ /auth/me — 권한이 낮아도 자기가 누구인지는 읽을 수 있어야 한다")
    void authMe() throws Exception {
        // 헤더에 이름을 띄우는 용도라 모든 역할이 부를 수 있어야 한다.
        // /app/me 는 학생·학부모 전용이고 /staff/accounts 는 조회 권한이 필요해서,
        // 이게 없으면 TEACHER·STAFF·READONLY 는 자기 이름조차 못 읽는다
        mvc.perform(get("/api/v1/admin/auth/me").header("Authorization", token("SFADM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value("SFADM"))
                .andExpect(jsonPath("$.data.name").value("지점관리자"))
                .andExpect(jsonPath("$.data.roles[0]").value("BRANCH_ADMIN"))
                .andExpect(jsonPath("$.data.academyId").value(academyId))
                .andExpect(jsonPath("$.data.academyName").value("직원테스트지점"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        // 조회 전용 계정도 자기 정보는 읽는다
        Employee viewer = new Employee(em.find(Academy.class, academyId), "조회전용");
        em.persist(viewer);
        Account viewerAccount = Account.forEmployee(
                viewer, "SFVIEW", passwordEncoder.encode(PASSWORD), false);
        em.persist(viewerAccount);
        em.flush();
        grantRole(viewerAccount.getId(), "READONLY");

        mvc.perform(get("/api/v1/admin/auth/me").header("Authorization", token("SFVIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("조회전용"))
                .andExpect(jsonPath("$.data.roles[0]").value("READONLY"));

        // 토큰이 없으면 401 — auth/** 가 permitAll 이라 여기만 따로 막아뒀다.
        // 안 막으면 principal 이 null 로 들어와 500 이 난다
        mvc.perform(get("/api/v1/admin/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★ 지점이 만든 계정은 승인 전까지 로그인이 막힌다 — 본사가 승인해야 열린다")
    void branchCreatedAccountNeedsApproval() throws Exception {
        // 비밀번호는 서버가 만들어 응답으로 딱 한 번 돌려준다 —
        // 관리자가 정해주면 그 비밀번호를 관리자가 계속 알고 있게 된다
        String created = mvc.perform(post("/api/v1/admin/staff/teachers")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"새담임","phone":"010-1111-1111",
                                 "email":"t@dlab.kr","loginId":"NEWT","roles":["TEACHER"]}"""
                                .formatted(academyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staff.kind").value("TEACHER"))
                // 만들어진 계정을 함께 돌려준다 — 없으면 화면이 방금 만든 행을 못 그린다
                .andExpect(jsonPath("$.data.accountId").isNumber())
                .andExpect(jsonPath("$.data.loginId").value("NEWT"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.pendingApproval").value(true))
                .andReturn().getResponse().getContentAsString();
        String temporary = objectMapper.readTree(created).path("data")
                .path("temporaryPassword").asString();
        em.flush();

        // 계정과 사람은 한 번에 만들어지지만, 지점이 만든 건 승인 전까지 못 쓴다
        mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"NEWT","password":"%s"}""".formatted(temporary)))
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
                                {"loginId":"NEWT","password":"%s"}""".formatted(temporary)))
                .andExpect(status().isOk())
                // 임시 비밀번호라 첫 로그인에서 변경을 강제한다
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        String access = objectMapper.readTree(body).path("data").path("accessToken").asString();

        // 바꾸기 전에는 다른 API 가 막힌다 — 강제 변경이 말뿐이면 의미가 없다
        mvc.perform(get("/api/v1/admin/approvals").header("Authorization", "Bearer " + access))
                .andExpect(status().is4xxClientError());

        String changed = mvc.perform(post("/api/v1/admin/auth/password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"%s"}"""
                                .formatted(temporary, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();

        // 변경하면 토큰이 새로 나온다 — 옛 토큰은 그대로 쓰지 않는다
        String renewed = objectMapper.readTree(changed).path("data").path("accessToken").asString();

        // 역할이 토큰에 실려야 승인 API를 쓸 수 있다
        mvc.perform(get("/api/v1/admin/approvals").header("Authorization", "Bearer " + renewed))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 지점 관리자는 자기보다 상위 역할을 만들 수 없다 — 승인 화면을 통한 권한 상승을 막는다")
    void cannotGrantHigherRole() throws Exception {
        // 승인 대기로 걸리긴 하지만, 본사가 승인 화면에서 요청된 역할을 못 보고 눌러주면
        // 전 지점 권한이 그대로 넘어간다
        mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"승격시도","loginId":"ESCAL",
                                 "roles":["SUPER_ADMIN"]}""".formatted(academyId)))
                .andExpect(status().isForbidden());

        // 같은 층은 막지 않는다 — 지점이 지점 관리자를 만드는 것은 정상 운영이고
        // 그건 승인 절차가 거른다
        mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"동급","loginId":"PEER",
                                 "roles":["BRANCH_ADMIN"]}""".formatted(academyId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("아이디 중복확인 · 역할 목록 — 폼이 저장 전에 확인할 수 있어야 한다")
    void formSupportApis() throws Exception {
        mvc.perform(get("/api/v1/admin/staff/login-id-available")
                        .header("Authorization", token("SFADM"))
                        .param("loginId", "NEVER_USED_ID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));

        mvc.perform(get("/api/v1/admin/staff/login-id-available")
                        .header("Authorization", token("SFADM"))
                        .param("loginId", "SFADM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false));

        // grantable 은 "지금 로그인한 사람이 줄 수 있는가"다 — 지점 관리자에게 SUPER_ADMIN 은 false
        mvc.perform(get("/api/v1/admin/staff/roles").header("Authorization", token("SFADM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data[0].displayName").value("본사 최고관리자"))
                .andExpect(jsonPath("$.data[0].grantable").value(false))
                .andExpect(jsonPath("$.data[1].code").value("BRANCH_ADMIN"))
                .andExpect(jsonPath("$.data[1].grantable").value(true));
    }

    @Test
    @DisplayName("인적사항을 고칠 수 있다 — 오타 때문에 탈퇴시키고 새로 만들지 않는다")
    void updateProfile() throws Exception {
        String created = mvc.perform(post("/api/v1/admin/staff/employees")
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"name":"오타김","deptName":"운영팀",
                                 "loginId":"TYPO","roles":["STAFF"]}""".formatted(academyId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).path("data").path("staff").path("id").asLong();
        em.flush();

        mvc.perform(patch("/api/v1/admin/staff/employees/{id}", id)
                        .header("Authorization", token("SFADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"정상김","positionName":"팀장"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("정상김"))
                .andExpect(jsonPath("$.data.positionName").value("팀장"))
                // 비운 값은 바꾸지 않는다 — 이름만 고치려다 부서가 지워지면 안 된다
                .andExpect(jsonPath("$.data.deptName").value("운영팀"));
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
