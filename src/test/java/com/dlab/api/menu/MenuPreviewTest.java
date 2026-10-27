package com.dlab.api.menu;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
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

/**
 * 계정별 메뉴 지정 미리보기.
 *
 * <h2>★★ 「체크했는데 안 되는」 상태를 체크하기 전에 보여준다</h2>
 * 메뉴 지정은 <b>보이는 것을 줄이는</b> 기능이고 권한을 늘리지 못하는데, 화면에서는
 * 역할에 없는 메뉴도 체크가 된다. 그러면 사이드바에는 보이고 <b>누르면 403</b> 이다 —
 * 담당자는 설정이 틀린 줄 안다(2026-10-27 입학예약 건이 그랬다).
 *
 * <h2>★ 미리보기와 저장이 같은 결과를 내야 한다</h2>
 * 이 테스트의 요점이다. 둘이 갈리면 <b>본 것과 다른 것이 저장되어</b> 미리보기가
 * 있는 것이 없는 것보다 나쁘다.
 */
@SpringBootTest
@Transactional
class MenuPreviewTest {

    private static final String PASSWORD = "preview-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long superId;
    Long viewerId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("31", "분당", LocalTime.of(8, 20));
        em.persist(academy);

        superId = staff(academy, "본사쌤", "MPSUPER", "SUPER_ADMIN");
        viewerId = staff(academy, "조회쌤", "MPVIEW", "READONLY");
        em.flush();
        em.clear();
    }

    private Long staff(Academy academy, String name, String loginId, String role) {
        Employee e = new Employee(academy, name);
        em.persist(e);
        Account a = Account.forEmployee(e, loginId, passwordEncoder.encode(PASSWORD), false);
        em.persist(a);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :a, id FROM role WHERE name = :r
                        """)
                .setParameter("a", a.getId()).setParameter("r", role)
                .executeUpdate();
        return a.getId();
    }

    @Test
    @DisplayName("★★ 역할에 없는 메뉴를 체크하면 PARTIAL 로 표시된다 — 체크 전에 보인다")
    void menuOutsideRoleIsPartial() throws Exception {
        String token = login("MPSUPER");

        // 조회 전용에 「대기자 관리」는 역할 기본 노출에 없다
        String body = mvc.perform(post("/api/v1/admin/staff/accounts/{id}/menus/preview", viewerId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restricted": true, "menuCodes": ["student","waiting"]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var rows = objectMapper.readTree(body).path("data");
        var waiting = find(rows, "waiting");
        var student = find(rows, "student");

        assertThat(waiting.path("visible").asBoolean()).as("체크했으니 보인다").isTrue();
        assertThat(waiting.path("level").asString())
                .as("★ 역할 기본 노출에 없어 확인이 필요하다").isEqualTo("PARTIAL");
        assertThat(waiting.path("note").asString()).as("왜 그런지 적혀 있다").isNotBlank();

        assertThat(student.path("visible").asBoolean()).isTrue();
        assertThat(student.path("level").asString())
                .as("학생 관리는 조회 전용에 열려 있다").isEqualTo("FULL");
    }

    @Test
    @DisplayName("★★ 미리보기와 저장이 같은 결과를 낸다 — 갈리면 미리보기가 거짓말이 된다")
    void previewMatchesSave() throws Exception {
        String token = login("MPSUPER");
        String payload = """
                {"restricted": true, "menuCodes": ["student","attendance"]}""";

        String previewed = mvc.perform(
                        post("/api/v1/admin/staff/accounts/{id}/menus/preview", viewerId)
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> previewVisible = visibleCodes(previewed);

        String saved = mvc.perform(put("/api/v1/admin/staff/accounts/{id}/menus", viewerId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> savedMenus = new java.util.ArrayList<>();
        objectMapper.readTree(saved).path("data").path("menus")
                .forEach(m -> savedMenus.add(m.path("code").asString()));

        assertThat(previewVisible)
                .as("★ 미리보기에서 보이던 것이 곧 저장된 것이어야 한다")
                .containsExactlyInAnyOrderElementsOf(savedMenus);
    }

    @Test
    @DisplayName("restricted=false 면 역할 기본값이 그대로 보인다 — 체크는 무시된다")
    void unrestrictedFallsBackToRoleDefaults() throws Exception {
        String token = login("MPSUPER");

        String body = mvc.perform(post("/api/v1/admin/staff/accounts/{id}/menus/preview", viewerId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restricted": false, "menuCodes": ["waiting"]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(find(body2node(body), "waiting").path("visible").asBoolean())
                .as("★ 제한이 없으면 체크가 아니라 역할 기본값을 따른다").isFalse();
        assertThat(find(body2node(body), "student").path("visible").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ 미리보기는 저장하지 않는다 — 불러도 설정이 생기지 않는다")
    void previewDoesNotSave() throws Exception {
        String token = login("MPSUPER");

        mvc.perform(post("/api/v1/admin/staff/accounts/{id}/menus/preview", viewerId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restricted": true, "menuCodes": ["student"]}"""))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/admin/staff/accounts/{id}/menus", viewerId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restricted").value(false));
    }

    @Test
    @DisplayName("최고관리자만 미리보기를 쓴다 — 권한 화면이다")
    void onlySuperAdmin() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/accounts/{id}/menus/preview", viewerId)
                        .header("Authorization", login("MPVIEW"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restricted": true, "menuCodes": ["student"]}"""))
                .andExpect(status().isForbidden());
    }

    private tools.jackson.databind.JsonNode body2node(String body) {
        return objectMapper.readTree(body).path("data");
    }

    private tools.jackson.databind.JsonNode find(tools.jackson.databind.JsonNode rows,
                                                 String code) {
        for (var row : rows) {
            if (code.equals(row.path("code").asString())) {
                return row;
            }
        }
        throw new AssertionError(code + " 가 결과에 없다");
    }

    private List<String> visibleCodes(String body) {
        List<String> codes = new java.util.ArrayList<>();
        body2node(body).forEach(row -> {
            if (row.path("visible").asBoolean()) {
                codes.add(row.path("code").asString());
            }
        });
        return codes;
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer "
                + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }
}
