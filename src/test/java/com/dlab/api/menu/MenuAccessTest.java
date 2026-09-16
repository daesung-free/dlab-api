package com.dlab.api.menu;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.menu.service.MenuAccessService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계정별 메뉴 노출 (0914 확정).
 *
 * <p>지키려는 것 — <b>서버도 같은 설정으로 막을 것</b>(화면만 감추면 주소를 직접 쳤을 때
 * 그대로 열린다), <b>설정이 없는 계정은 그대로 쓸 것</b>(있던 계정이 전부 잠기면 안 된다),
 * <b>경로 경계를 지킬 것</b>.
 */
@SpringBootTest
@Transactional
class MenuAccessTest {

    private static final String PASSWORD = "menu-access-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired MenuAccessService menuAccessService;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Account viewer;
    AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        Employee staff = new Employee(bundang, "조회전용");
        em.persist(staff);
        viewer = Account.forEmployee(staff, "MENUVIEW", passwordEncoder.encode(PASSWORD), false);
        em.persist(viewer);
        em.flush();
        grantRole(viewer.getId(), "STAFF");

        superAdmin = new AuthPrincipal(1L, "admin", bundang.getId(),
                Set.of(Role.SUPER_ADMIN), true, false);
    }

    @Test
    @DisplayName("★ 설정이 없으면 제한이 없다 — 있던 계정이 전부 잠기면 안 된다")
    void noSettingMeansNoRestriction() {
        assertThat(menuAccessService.restricted(viewer.getId())).isFalse();
        assertThat(menuAccessService.allows(viewer.getId(), "/api/v1/admin/students")).isTrue();
        // 화면에는 전체가 내려간다 — "설정 없음"을 화면이 따로 다루지 않아도 되게
        assertThat(menuAccessService.visibleMenus(viewer.getId()))
                .hasSameSizeAs(menuAccessService.catalog());
    }

    @Test
    @DisplayName("★★ 화면만 감추지 않는다 — 주소를 직접 쳐도 서버가 막는다")
    void serverBlocksHiddenMenu() throws Exception {
        menuAccessService.replace(superAdmin, viewer.getId(), List.of("attendance"));
        em.flush();

        String token = login();

        mvc.perform(get("/api/v1/admin/students")
                        .header("Authorization", token)
                        .param("year", "2026"))
                .andExpect(status().isForbidden());

        // 준 메뉴는 열린다 — 전부 막으면 화면이 아무것도 못 한다
        int status = mvc.perform(get("/api/v1/admin/menus/mine").header("Authorization", token))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("빈 목록을 보내면 제한이 풀린다 — 되돌릴 방법이 있어야 한다")
    void emptyCodesClearRestriction() {
        menuAccessService.replace(superAdmin, viewer.getId(), List.of("attendance"));
        assertThat(menuAccessService.restricted(viewer.getId())).isTrue();

        menuAccessService.replace(superAdmin, viewer.getId(), List.of());
        assertThat(menuAccessService.restricted(viewer.getId())).isFalse();
    }

    @Test
    @DisplayName("★ 없는 메뉴 코드는 거절한다 — 조용히 빠지면 못 준 줄 모른다")
    void rejectsUnknownCode() {
        assertThatThrownBy(() -> menuAccessService.replace(
                superAdmin, viewer.getId(), List.of("attendance", "없는메뉴")))
                .hasMessageContaining("없는 메뉴");
    }

    @Test
    @DisplayName("★ 최고관리자만 바꾼다 — 지점이 바꾸면 권한 관리가 지점으로 내려간다")
    void onlySuperAdminCanChange() {
        AuthPrincipal branch = new AuthPrincipal(2L, "branch", 1L,
                Set.of(Role.BRANCH_ADMIN), false, false);

        assertThatThrownBy(() -> menuAccessService.replace(
                branch, viewer.getId(), List.of("attendance")))
                .hasMessageContaining("최고관리자");
    }

    @Test
    @DisplayName("★ 경로 경계를 본다 — /staff 가 /staff-cards 까지 삼키면 안 된다")
    void prefixRespectsBoundary() {
        menuAccessService.replace(superAdmin, viewer.getId(), List.of("staff"));
        em.flush();

        assertThat(menuAccessService.allows(viewer.getId(), "/api/v1/admin/staff/accounts")).isTrue();
        // staff-cards 는 자기 메뉴가 없다 — 어느 메뉴에도 안 걸리므로 역할 검사에 맡긴다
        assertThat(menuAccessService.allows(viewer.getId(), "/api/v1/admin/staff-cards")).isTrue();
        assertThat(menuAccessService.allows(viewer.getId(), "/api/v1/admin/students")).isFalse();
    }

    @Test
    @DisplayName("자주 쓰는 메뉴는 보낸 순서 그대로다 — 사용자가 정한 배치다")
    void favoritesKeepOrder() {
        menuAccessService.replaceFavorites(viewer.getId(),
                List.of("penalty", "attendance", "student"));
        em.flush();

        assertThat(menuAccessService.favorites(viewer.getId()))
                .extracting(com.dlab.domain.menu.entity.Menu::getCode)
                .containsExactly("penalty", "attendance", "student");
    }

    @Test
    @DisplayName("★ 볼 수 없는 메뉴는 담기지 않는다 — 눌러도 403 인 칸이 대시보드에 남는다")
    void cannotFavoriteHiddenMenu() {
        menuAccessService.replace(superAdmin, viewer.getId(), List.of("attendance"));
        em.flush();

        assertThatThrownBy(() -> menuAccessService.replaceFavorites(
                viewer.getId(), List.of("student")))
                .hasMessageContaining("담을 수 없는");
    }

    @Test
    @DisplayName("자주 쓰는 메뉴를 담아도 권한은 그대로다 — 편의 설정이 권한을 넓히지 않는다")
    void favoriteDoesNotGrantAccess() {
        menuAccessService.replace(superAdmin, viewer.getId(), List.of("attendance", "penalty"));
        menuAccessService.replaceFavorites(viewer.getId(), List.of("penalty"));
        em.flush();

        assertThat(menuAccessService.allows(viewer.getId(), "/api/v1/admin/students")).isFalse();
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"MENUVIEW","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer "
                + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private void grantRole(Long accountId, String roleName) {
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :roleName
                        """)
                .setParameter("accountId", accountId).setParameter("roleName", roleName)
                .executeUpdate();
    }
}
