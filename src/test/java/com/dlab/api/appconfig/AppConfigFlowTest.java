package com.dlab.api.appconfig;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
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
 * 앱 부팅 설정 (A-21 · F-4.12-3).
 *
 * <p>지키려는 것 — <b>인증 없이 열려 있을 것</b>(점검 중엔 로그인도 막힌다),
 * <b>버전 비교가 의미 단위일 것</b>, <b>전 지점 공통이라 최상위 관리자만 바꿀 것</b>.
 */
@SpringBootTest
@Transactional
class AppConfigFlowTest {

    private static final String PASSWORD = "app-config-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("AC01", "앱설정테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        createAdmin(academy, "ACSUPER", "본사관리자", "SUPER_ADMIN");
        createAdmin(academy, "ACBRANCH", "지점관리자", "BRANCH_ADMIN");
        em.flush();
    }

    private void createAdmin(Academy academy, String loginId, String name, String role) {
        Employee employee = new Employee(academy, name);
        em.persist(employee);
        Account account = Account.forEmployee(employee, loginId, passwordEncoder.encode(PASSWORD));
        em.persist(account);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :role
                        """)
                .setParameter("accountId", account.getId())
                .setParameter("role", role)
                .executeUpdate();
    }

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 부팅 API는 인증 없이 열린다 — 점검 중엔 로그인 API도 막혀 있을 수 있다")
    void bootIsPublic() throws Exception {
        mvc.perform(get("/api/v1/app/app-config").param("platform", "IOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform").value("IOS"))
                .andExpect(jsonPath("$.data.maintenance").value(false));
    }

    @Test
    @DisplayName("★ 최소 버전 미만이면 강제 업데이트 — 1.10.0은 1.9.0보다 높다")
    void updateRequiredUsesSemanticCompare() throws Exception {
        mvc.perform(patch("/api/v1/admin/app-config/{platform}/versions", "ANDROID")
                        .header("Authorization", token("ACSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minVersion":"1.9.0","latestVersion":"1.10.0"}"""))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/app/app-config")
                        .param("platform", "ANDROID").param("version", "1.8.9"))
                .andExpect(jsonPath("$.data.updateRequired").value(true));

        // 사전순이면 여기서 true가 나온다 — 최신 사용자가 전부 막히는 지점
        mvc.perform(get("/api/v1/app/app-config")
                        .param("platform", "ANDROID").param("version", "1.10.0"))
                .andExpect(jsonPath("$.data.updateRequired").value(false));
    }

    @Test
    @DisplayName("버전을 안 보내면 업데이트를 요구하지 않는다 — 못 읽었다고 막으면 전 사용자 차단이다")
    void noVersionMeansNoBlock() throws Exception {
        mvc.perform(patch("/api/v1/admin/app-config/{platform}/versions", "IOS")
                        .header("Authorization", token("ACSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minVersion":"9.9.9"}"""))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/app-config").param("platform", "IOS"))
                .andExpect(jsonPath("$.data.updateRequired").value(false));
    }

    @Test
    @DisplayName("점검 모드를 켜면 문구·종료시각이 함께 내려간다")
    void maintenanceOn() throws Exception {
        mvc.perform(put("/api/v1/admin/app-config/{platform}/maintenance", "IOS")
                        .header("Authorization", token("ACSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maintenance":true,"message":"서버 점검 중입니다","until":"2026-08-06T02:00:00Z"}"""))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/app-config").param("platform", "IOS"))
                .andExpect(jsonPath("$.data.maintenance").value(true))
                .andExpect(jsonPath("$.data.maintenanceMessage").value("서버 점검 중입니다"));
    }

    @Test
    @DisplayName("★ 점검을 끄면 문구도 비워진다 — 남으면 다음에 켤 때 옛 안내가 뜬다")
    void maintenanceOffClearsMessage() throws Exception {
        String token = token("ACSUPER");
        mvc.perform(put("/api/v1/admin/app-config/{platform}/maintenance", "IOS")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"maintenance":true,"message":"1차 점검"}""")).andExpect(status().isOk());
        em.flush();

        mvc.perform(put("/api/v1/admin/app-config/{platform}/maintenance", "IOS")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"maintenance":false}""")).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/app-config").param("platform", "IOS"))
                .andExpect(jsonPath("$.data.maintenance").value(false))
                .andExpect(jsonPath("$.data.maintenanceMessage").doesNotExist());
    }

    @Test
    @DisplayName("★ 지점 관리자는 점검 모드를 켤 수 없다 — 전 지점 앱이 같이 멈춘다")
    void branchAdminCannotToggleMaintenance() throws Exception {
        mvc.perform(put("/api/v1/admin/app-config/{platform}/maintenance", "IOS")
                        .header("Authorization", token("ACBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maintenance":true}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("버전 형식이 틀리면 거부한다")
    void invalidVersionRejected() throws Exception {
        mvc.perform(patch("/api/v1/admin/app-config/{platform}/versions", "IOS")
                        .header("Authorization", token("ACSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minVersion":"버전1"}"""))
                .andExpect(status().isBadRequest());
    }
}
