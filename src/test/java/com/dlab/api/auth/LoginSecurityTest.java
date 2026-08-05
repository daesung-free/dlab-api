package com.dlab.api.auth;

import com.dlab.common.security.PasswordPolicy;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 로그인 보안 (앱 요구사항 A-1 · 관리자 F-4.12-1).
 *
 * <p><b>★ {@code @Transactional}을 붙이지 않는다.</b> 계정 잠금은 실패 예외에 롤백되지 않도록
 * {@code REQUIRES_NEW}로 나간다({@code LoginFailureRecorder}). 테스트 트랜잭션 안에서는 그
 * 별도 트랜잭션이 아직 커밋되지 않은 계정을 보지 못해 <b>잠금이 안 걸린 것처럼 통과해버린다</b> —
 * 검증하려던 것을 정확히 못 보게 된다. 대신 정리를 직접 한다.
 */
@SpringBootTest
class LoginSecurityTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "login-security-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionTemplate tx;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    Long targetAccountId;
    Long adminAccountId;
    String userLoginId;
    String adminLoginId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        int seq = SEQ.incrementAndGet();
        userLoginId = "LS" + seq + "U" + System.nanoTime() % 100_000;
        adminLoginId = "LS" + seq + "A" + System.nanoTime() % 100_000;

        tx.executeWithoutResult(status -> {
            Academy academy = new Academy("LS" + seq + System.nanoTime() % 100_000,
                    "로그인보안테스트지점", LocalTime.of(9, 0));
            em.persist(academy);

            Teacher teacher = new Teacher(academy, "대상선생님", "010-0000-0000");
            em.persist(teacher);
            Account target = Account.forTeacher(teacher, userLoginId,
                    passwordEncoder.encode(PASSWORD));
            em.persist(target);

            Employee admin = new Employee(academy, "관리자");
            em.persist(admin);
            Account adminAccount = Account.forEmployee(admin, adminLoginId,
                    passwordEncoder.encode(PASSWORD));
            em.persist(adminAccount);
            em.flush();

            em.createNativeQuery("""
                            INSERT INTO account_role (account_id, role_id)
                            SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                            """)
                    .setParameter("accountId", adminAccount.getId()).executeUpdate();

            academyId = academy.getId();
            targetAccountId = target.getId();
            adminAccountId = adminAccount.getId();
        });

        // 앞선 테스트가 남긴 카운터가 있으면 실패 횟수가 어긋난다
        redis.delete(List.of("auth:fail:" + userLoginId, "auth:fail:" + adminLoginId));
    }

    /** REQUIRES_NEW로 실제 커밋되므로 직접 지운다. FK 역순. */
    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("""
                    DELETE FROM account_role WHERE account_id IN
                        (SELECT a.id FROM account a
                          LEFT JOIN teacher t ON t.id = a.teacher_id
                          LEFT JOIN employee e ON e.id = a.employee_id
                         WHERE t.academy_id = :id OR e.academy_id = :id)
                    """).setParameter("id", academyId).executeUpdate();
            em.createNativeQuery("""
                    DELETE FROM account WHERE id IN
                        (SELECT a.id FROM account a
                          LEFT JOIN teacher t ON t.id = a.teacher_id
                          LEFT JOIN employee e ON e.id = a.employee_id
                         WHERE t.academy_id = :id OR e.academy_id = :id)
                    """).setParameter("id", academyId).executeUpdate();
            em.createNativeQuery("DELETE FROM teacher WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();
            em.createNativeQuery("DELETE FROM employee WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();
            em.createNativeQuery("DELETE FROM academy WHERE id = :id")
                    .setParameter("id", academyId).executeUpdate();
        });
        redis.delete(List.of("auth:fail:" + userLoginId, "auth:fail:" + adminLoginId));
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions login(String loginId, String password)
            throws Exception {
        return mvc.perform(post("/api/v1/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"loginId":"%s","password":"%s"}""".formatted(loginId, password)));
    }

    private String tokenOf(String loginId, String password) throws Exception {
        String body = login(loginId, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private boolean lockedInDb() {
        return Boolean.TRUE.equals(tx.execute(status -> em.createNativeQuery(
                        "SELECT locked_at IS NOT NULL FROM account WHERE id = :id")
                .setParameter("id", targetAccountId).getSingleResult()));
    }

    // ─────────────────────────────────────────────────────────────
    // 실패 누적 잠금
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 실패 5회면 계정이 잠긴다 — 잠금이 실패 예외와 함께 롤백되지 않아야 한다")
    void locksAfterMaxFailures() throws Exception {
        for (int i = 0; i < PasswordPolicy.MAX_LOGIN_FAILURES; i++) {
            login(userLoginId, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }

        // ★ 여기가 핵심이다. 잠금을 login()과 같은 트랜잭션에서 걸면 아래가 false가 된다 —
        //   로그는 "잠금" 이라고 남는데 DB는 그대로다.
        assertThat(lockedInDb()).isTrue();
    }

    @Test
    @DisplayName("★ 잠긴 계정은 비밀번호가 맞아도 로그인되지 않는다 — 맞으면 열어주면 잠금이 무의미하다")
    void lockedAccountRejectsCorrectPassword() throws Exception {
        for (int i = 0; i < PasswordPolicy.MAX_LOGIN_FAILURES; i++) {
            login(userLoginId, "wrong").andExpect(status().isUnauthorized());
        }

        login(userLoginId, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("★ 성공하면 실패 카운터가 초기화된다 — 안 지우면 한참 뒤 한 번 틀렸다고 잠긴다")
    void successResetsCounter() throws Exception {
        for (int i = 0; i < PasswordPolicy.MAX_LOGIN_FAILURES - 1; i++) {
            login(userLoginId, "wrong").andExpect(status().isUnauthorized());
        }
        login(userLoginId, PASSWORD).andExpect(status().isOk());

        // 초기화되지 않았다면 이 한 번으로 곧장 잠긴다
        login(userLoginId, "wrong").andExpect(status().isUnauthorized());
        assertThat(lockedInDb()).isFalse();
    }

    @Test
    @DisplayName("관리자가 잠금을 풀면 다시 로그인된다 — 카운터도 함께 지워져야 한다")
    void adminUnlock() throws Exception {
        for (int i = 0; i < PasswordPolicy.MAX_LOGIN_FAILURES; i++) {
            login(userLoginId, "wrong").andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/admin/app-accounts/{id}/unlock", targetAccountId)
                        .header("Authorization", tokenOf(adminLoginId, PASSWORD)))
                .andExpect(status().isOk());

        login(userLoginId, PASSWORD).andExpect(status().isOk());
        // 카운터가 남아 있었다면 아래 한 번으로 즉시 재잠금된다
        login(userLoginId, "wrong").andExpect(status().isUnauthorized());
        assertThat(lockedInDb()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 임시 비밀번호 · 변경 강제
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 임시 비밀번호 상태에서는 비밀번호 변경 외 API가 막힌다 — 앱만 믿으면 우회된다")
    void temporaryPasswordBlocksOtherApis() throws Exception {
        // 대상은 관리자 계정 자신이다 — 차단이 풀린 뒤 "이제 되는가"까지 보려면
        // 권한이 있는 계정이어야 한다(선생님 계정은 원래도 이 API가 403이라 구분되지 않는다).
        String adminToken = tokenOf(adminLoginId, PASSWORD);
        String issued = mvc.perform(post("/api/v1/admin/app-accounts/{id}/temporary-password",
                                adminAccountId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String temporary = objectMapper.readTree(issued).path("data")
                .path("temporaryPassword").asString();

        String body = login(adminLoginId, temporary).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + objectMapper.readTree(body).path("data")
                .path("accessToken").asString();

        // 다른 API는 막힌다
        mvc.perform(get("/api/v1/admin/staff/teachers").param("academyId", String.valueOf(academyId))
                        .header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));

        // 변경하면 새 토큰이 나오고 플래그가 풀린다
        String changed = mvc.perform(post("/api/v1/admin/auth/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"new-password-5678"}"""
                                .formatted(temporary)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andReturn().getResponse().getContentAsString();
        String newToken = "Bearer " + objectMapper.readTree(changed).path("data")
                .path("accessToken").asString();

        mvc.perform(get("/api/v1/admin/staff/teachers").param("academyId", String.valueOf(academyId))
                        .header("Authorization", newToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("임시 비밀번호 재발급은 잠금도 함께 푼다 — 안 풀면 새 비밀번호로도 로그인이 안 된다")
    void reissueAlsoUnlocks() throws Exception {
        for (int i = 0; i < PasswordPolicy.MAX_LOGIN_FAILURES; i++) {
            login(userLoginId, "wrong").andExpect(status().isUnauthorized());
        }

        String issued = mvc.perform(post("/api/v1/admin/app-accounts/{id}/temporary-password",
                                targetAccountId)
                        .header("Authorization", tokenOf(adminLoginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String temporary = objectMapper.readTree(issued).path("data")
                .path("temporaryPassword").asString();

        login(userLoginId, temporary).andExpect(status().isOk());
    }

    @Test
    @DisplayName("임시 비밀번호를 그대로 다시 설정할 수 없다 — 허용하면 변경 강제가 무의미하다")
    void cannotReuseSamePassword() throws Exception {
        mvc.perform(post("/api/v1/admin/auth/password")
                        .header("Authorization", tokenOf(userLoginId, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"%s"}"""
                                .formatted(PASSWORD, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_SAME_AS_BEFORE"));
    }

    @Test
    @DisplayName("정책에 맞지 않는 비밀번호는 거부된다 (8자 미만·숫자 없음)")
    void rejectsWeakPassword() throws Exception {
        String token = tokenOf(userLoginId, PASSWORD);

        mvc.perform(post("/api/v1/admin/auth/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"ab1"}""".formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_POLICY_VIOLATION"));

        mvc.perform(post("/api/v1/admin/auth/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"onlyletters"}"""
                                .formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_POLICY_VIOLATION"));
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 변경되지 않는다")
    void wrongCurrentPasswordRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/auth/password")
                        .header("Authorization", tokenOf(userLoginId, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"totally-wrong-1","newPassword":"new-password-5678"}"""))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────
    // 로그아웃 블랙리스트
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 로그아웃한 Access Token은 즉시 무효다 — 블랙리스트를 읽는 곳이 없어 살아 있었다")
    void logoutInvalidatesAccessToken() throws Exception {
        String token = tokenOf(adminLoginId, PASSWORD);

        mvc.perform(get("/api/v1/admin/staff/teachers").param("academyId", String.valueOf(academyId))
                .header("Authorization", token)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/auth/logout").header("Authorization", token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/staff/teachers").param("academyId", String.valueOf(academyId))
                        .header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }
}
