package com.dlab.api.student;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 검색조건 저장 (P1-01 "조건저장").
 *
 * <p>핵심은 <b>개인 설정이라는 것</b> — 같은 지점이어도 남의 조건은 보이지도 지워지지도 않는다.
 */
@SpringBootTest
@Transactional
class SavedSearchFlowTest {

    private static final String PASSWORD = "saved-search-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("SS01", "조건저장테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        createAdmin("SSADM", "관리자일");
        createAdmin("SSADM2", "관리자이");
        em.flush();
    }

    private void createAdmin(String loginId, String name) {
        Employee admin = new Employee(academy, name);
        em.persist(admin);
        Account account = Account.forEmployee(admin, loginId, passwordEncoder.encode(PASSWORD));
        em.persist(account);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", account.getId()).executeUpdate();
    }

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private long save(String loginId, String name, String conditions) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/students/saved-searches")
                        .header("Authorization", token(loginId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","conditions":"%s"}"""
                                .formatted(name, conditions.replace("\"", "\\\""))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("조건을 저장하고 그대로 돌려받는다 — 서버는 JSON을 파싱하지 않는다")
    void saveAndList() throws Exception {
        save("SSADM", "내 반 재원생", "{\"grade\":\"N_SU\",\"status\":\"ENROLLED\"}");

        mvc.perform(get("/api/v1/admin/students/saved-searches")
                        .header("Authorization", token("SSADM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("내 반 재원생"))
                .andExpect(jsonPath("$.data[0].conditions")
                        .value("{\"grade\":\"N_SU\",\"status\":\"ENROLLED\"}"));
    }

    @Test
    @DisplayName("★ 같은 이름으로 다시 저장하면 덮어쓴다 — 둘이 남으면 어느 게 최신인지 모른다")
    void sameNameOverwrites() throws Exception {
        save("SSADM", "자주쓰는조건", "{\"grade\":\"HIGH3\"}");
        save("SSADM", "자주쓰는조건", "{\"grade\":\"N_SU\"}");
        em.clear();

        mvc.perform(get("/api/v1/admin/students/saved-searches")
                        .header("Authorization", token("SSADM")))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].conditions").value("{\"grade\":\"N_SU\"}"));
    }

    @Test
    @DisplayName("★ 남의 저장 조건은 목록에 안 나온다 — 같은 지점이어도 개인 설정이다")
    void othersConditionsAreHidden() throws Exception {
        save("SSADM", "관리자일의조건", "{\"grade\":\"HIGH2\"}");
        em.clear();

        mvc.perform(get("/api/v1/admin/students/saved-searches")
                        .header("Authorization", token("SSADM2")))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("★ 남의 저장 조건은 지울 수 없다")
    void cannotDeleteOthers() throws Exception {
        long id = save("SSADM", "지우면안됨", "{\"grade\":\"HIGH2\"}");
        em.clear();

        mvc.perform(delete("/api/v1/admin/students/saved-searches/{id}", id)
                        .header("Authorization", token("SSADM2")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("본인 조건은 지워지고 목록에서 빠진다")
    void deleteOwn() throws Exception {
        long id = save("SSADM", "지울조건", "{\"grade\":\"HIGH2\"}");
        em.clear();

        mvc.perform(delete("/api/v1/admin/students/saved-searches/{id}", id)
                        .header("Authorization", token("SSADM")))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/students/saved-searches")
                        .header("Authorization", token("SSADM")))
                .andExpect(jsonPath("$.data").isEmpty());

        // soft delete라 행은 남는다 — 물리 삭제하면 복구 요청에 답할 수 없다
        Long rows = em.createQuery(
                        "SELECT COUNT(s) FROM SavedSearch s WHERE s.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("조건 길이 상한을 넘으면 거부한다 — 서버가 파싱 안 하는 값이라 무제한이면 저장소가 된다")
    void tooLongConditionRejected() throws Exception {
        String tooLong = "x".repeat(4_001);

        mvc.perform(post("/api/v1/admin/students/saved-searches")
                        .header("Authorization", token("SSADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"긴조건","conditions":"%s"}""".formatted(tooLong)))
                .andExpect(status().isBadRequest());
    }
}
