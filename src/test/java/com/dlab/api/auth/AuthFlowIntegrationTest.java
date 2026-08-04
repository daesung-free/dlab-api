package com.dlab.api.auth;

import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AccountRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 로그인 흐름 통합 테스트.
 *
 * <p>수동 curl로 확인했던 것을 재현 가능하게 고정한다 — 특히 <b>Refresh Token으로 API를
 * 호출할 수 없다</b>와 <b>회전된 Refresh는 재사용 불가</b>는 깨져도 겉으로 티가 안 나는
 * 종류라 테스트가 없으면 놓친다.
 *
 * <p>실행 전 로컬 PostgreSQL과 Redis가 떠 있어야 한다(`docker compose up -d`).
 */
@SpringBootTest
@Transactional
class AuthFlowIntegrationTest {

    private static final String LOGIN_ID = "IT0001";
    private static final String PASSWORD = "test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired AccountRepository accountRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;

    /** 스케줄러가 테스트 중에 돌면서 간섭하지 않도록 막는다. */
    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        redis.delete(redis.keys("auth:*"));

        Academy academy = new Academy("IT01", "통합테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        Teacher teacher = new Teacher(academy, "테스트담임", "010-0000-0000");
        em.persist(teacher);
        em.persist(Account.forTeacher(teacher, LOGIN_ID, passwordEncoder.encode(PASSWORD)));
        em.flush();
    }

    private String login(String password) throws Exception {
        return mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(LOGIN_ID, password)))
                .andReturn().getResponse().getContentAsString();
    }

    private String field(String json, String name) {
        return objectMapper.readTree(json).path("data").path(name).asString();
    }

    @Test
    @DisplayName("로그인하면 토큰이 나오고 Refresh가 Redis에 저장된다")
    void loginIssuesTokens() throws Exception {
        String body = login(PASSWORD);

        org.assertj.core.api.Assertions.assertThat(field(body, "accessToken")).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(field(body, "refreshToken")).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(redis.keys("auth:refresh:*")).isNotEmpty();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 계정 존재 여부를 알려주지 않는다")
    void wrongPassword() throws Exception {
        mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"wrong"}""".formatted(LOGIN_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        // 없는 계정도 같은 코드여야 한다 — 다르면 계정 존재 여부가 샌다
        mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"NOPE","password":"wrong"}"""))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("토큰 없이 보호된 경로에 가면 401")
    void noTokenRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/students"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("★ Refresh Token으로는 API를 호출할 수 없다")
    void refreshTokenCannotCallApi() throws Exception {
        String refresh = field(login(PASSWORD), "refreshToken");

        mvc.perform(get("/api/v1/admin/students").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★ 재발급하면 이전 Refresh Token은 무효가 된다 (회전)")
    void refreshRotates() throws Exception {
        String oldRefresh = field(login(PASSWORD), "refreshToken");

        mvc.perform(post("/api/v1/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(oldRefresh)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(oldRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("★ 승인 대기(PENDING) 학생은 로그인 자체가 막힌다")
    void pendingAccountCannotLogin() throws Exception {
        Student student = new Student("ITSTU01", "대기학생", "010-9999-9999");
        em.persist(student);
        em.persist(Account.forStudent(student, "ITSTU", passwordEncoder.encode(PASSWORD)));
        em.flush();

        mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"ITSTU","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isForbidden())
                // 자격증명 오류와 구분돼야 안내 문구를 다르게 낼 수 있다
                .andExpect(jsonPath("$.error.code").value("SIGNUP_PENDING"));
    }
}
