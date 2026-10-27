package com.dlab.api.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.common.security.SessionCutoff;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
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
 * 계정 단위 세션 컷오프 ({@link SessionCutoff}).
 *
 * <h2>★★ 고치기 전에는 중지해도 최대 1시간 더 쓸 수 있었다</h2>
 * 차단 목록의 키가 <b>토큰 문자열</b>이라, 관리자가 남의 계정을 중지할 때는
 * <b>올릴 값이 없었다</b> — 그 사람의 토큰을 서버가 모른다. 필터도 DB 를 보지 않으므로
 * 중지·역할변경·메뉴변경이 <b>이미 발급된 토큰에는 반영되지 않았다</b>
 * (운영 Access Token 수명 {@code PT1H}).
 *
 * <h2>★ 권한 작업과 같은 문제였다</h2>
 * 메뉴를 바꿨는데 안 바뀌는 증상이 <b>「역할에 없어서 403」과 구분되지 않아</b>
 * 2026-10-27 에 실제로 혼동이 있었다. 그래서 권한이 바뀌는 자리마다 컷오프를 찍는다.
 */
@SpringBootTest
@Transactional
class SessionCutoffTest {

    private static final String PASSWORD = "cutoff-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired SessionCutoff sessionCutoff;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long accountId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("31", "분당", LocalTime.of(8, 20));
        em.persist(academy);
        Employee staff = new Employee(academy, "행정쌤");
        em.persist(staff);
        Account account = Account.forEmployee(staff, "CUTOFF",
                passwordEncoder.encode(PASSWORD), false);
        em.persist(account);
        em.flush();
        accountId = account.getId();

        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'SUPER_ADMIN'
                        """)
                .setParameter("accountId", accountId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("★★ 컷오프를 찍으면 이미 발급된 토큰이 즉시 끊긴다")
    void cutoffRevokesExistingToken() throws Exception {
        String token = login();

        // 끊기 전에는 된다
        mvc.perform(get("/api/v1/admin/menus/mine").header("Authorization", token))
                .andExpect(status().isOk());

        // ★ 초가 넘어가기를 기다린다. iat 이 초 단위로 잘려 오므로 ★ 컷오프와 같은 초에
        //   발급된 토큰은 일부러 살려 둔다(그러지 않으면 재발급 직후 로그인이 즉시 튕긴다).
        //   여기서 기다리지 않으면 이 테스트가 그 설계를 거꾸로 고정하게 된다.
        awaitNextSecond();
        sessionCutoff.revokeAll(accountId);

        // ★ 같은 토큰이 이제 통하지 않는다 — 서명·만료는 멀쩡하다
        mvc.perform(get("/api/v1/admin/menus/mine").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★ 다른 계정은 끊기지 않는다 — 전 계정 로그아웃이 되면 안 된다")
    void cutoffIsPerAccount() throws Exception {
        String token = login();
        awaitNextSecond();
        sessionCutoff.revokeAll(accountId + 999_999);

        mvc.perform(get("/api/v1/admin/menus/mine").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★★ 계정별 메뉴를 바꾸면 그 계정이 끊긴다 — 「설정했는데 안 바뀐다」를 없앤다")
    void changingMenusRevokesThatAccount() throws Exception {
        String token = login();

        mvc.perform(put("/api/v1/admin/staff/accounts/" + accountId + "/menus")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restricted": true, "menuCodes": ["student"]}"""))
                .andExpect(status().isOk());

        // ★ 자기 메뉴를 바꿨으니 자기도 끊긴다. 다시 로그인하면 새 범위로 들어온다 —
        //   옛 범위로 1시간 더 도는 것보다 낫다.
        assertThat(sessionCutoff.revoked(accountId, java.time.Instant.now().minusSeconds(5)))
                .as("메뉴 변경이 컷오프를 찍었는가")
                .isTrue();
    }

    /** 초 경계를 넘긴다 — 최대 1초. */
    private void awaitNextSecond() throws InterruptedException {
        long start = java.time.Instant.now().getEpochSecond();
        while (java.time.Instant.now().getEpochSecond() == start) {
            Thread.sleep(50);
        }
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"CUTOFF","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer "
                + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }
}
