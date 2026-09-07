package com.dlab.api.appconfig;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 약관 동의 · 알림 수신 · FCM 토큰 (A-21).
 *
 * <p>지키려는 것 — <b>동의는 문구 버전까지 남을 것</b>, <b>개정되면 다시 물을 것</b>,
 * <b>필수 알림은 못 끌 것</b>, <b>토큰이 이전 사용자에게 남지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class AppSettingsFlowTest {

    private static final String PASSWORD = "settings-password-1234";
    private static final String PARENT_PHONE = "010-5555-6666";
    private static final String OTHER_PHONE = "010-7777-8888";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    String superAdminId;
    Long myAccountId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("AS01", "설정테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        superAdminId = "ASSUPER";
        Employee employee = new Employee(academy, "본사관리자");
        em.persist(employee);
        Account adminAccount = Account.forEmployee(employee, superAdminId,
                passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'SUPER_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId()).executeUpdate();

        // 앱 사용자 2명 (같은 기기를 나눠 쓰는 상황을 만들기 위해)
        myAccountId = createGuardian("설정학부모", PARENT_PHONE);
        createGuardian("다른학부모", OTHER_PHONE);
        em.flush();
    }

    private Long createGuardian(String name, String phone) {
        ParentGuardian guardian = new ParentGuardian(name, phone, null);
        em.persist(guardian);
        Account account = Account.forGuardian(guardian, phone, passwordEncoder.encode(PASSWORD));
        em.persist(account);
        em.flush();
        return account.getId();
    }

    private String token(String loginId, String path) throws Exception {
        String body = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private String appToken(String loginId) throws Exception {
        return token(loginId, "/api/v1/app/auth/login");
    }

    private String adminToken() throws Exception {
        return token(superAdminId, "/api/v1/admin/auth/login");
    }

    private long createTerms(String code, String version, boolean required) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/app-config/terms")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","version":"%s","title":"%s 약관","content":"본문 %s","required":%b}"""
                                .formatted(code, version, code, version, required)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    // ── 약관 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 동의하면 그 시점의 약관 버전이 함께 남는다 — 이게 이 기능의 존재 이유다")
    void agreementKeepsVersion() throws Exception {
        long termsId = createTerms("SERVICE", "v1.0", true);

        mvc.perform(post("/api/v1/app/settings/terms/{id}", termsId)
                        .header("Authorization", appToken(PARENT_PHONE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agreed":true}"""))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/app-config/terms/agreements/{accountId}", myAccountId)
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("SERVICE"))
                .andExpect(jsonPath("$.data[0].version").value("v1.0"))
                .andExpect(jsonPath("$.data[0].agreed").value(true));
    }

    @Test
    @DisplayName("★ 약관이 개정되면 다시 물어본다 — 새 버전에 대한 동의는 없는 상태다")
    void revisedTermsRequireNewAgreement() throws Exception {
        long v1 = createTerms("SERVICE", "v1.0", true);
        String token = appToken(PARENT_PHONE);

        mvc.perform(post("/api/v1/app/settings/terms/{id}", v1)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agreed":true}""")).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/settings/terms").header("Authorization", token))
                .andExpect(jsonPath("$.data[0].agreed").value(true));

        // 개정 — 문구를 고치는 게 아니라 버전을 올린다
        createTerms("SERVICE", "v2.0", true);
        em.clear();

        mvc.perform(get("/api/v1/app/settings/terms").header("Authorization", token))
                .andExpect(jsonPath("$.data[0].version").value("v2.0"))
                // 미동의: null이면 "아직 응답 없음"이다
                .andExpect(jsonPath("$.data[0].agreed").doesNotExist());
    }

    @Test
    @DisplayName("같은 code+version을 다시 등록할 수 없다 — 덮어쓰려는 시도다")
    void duplicateTermsVersionRejected() throws Exception {
        createTerms("PRIVACY", "v1.0", true);

        mvc.perform(post("/api/v1/admin/app-config/terms")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"PRIVACY","version":"v1.0","title":"중복","content":"본문"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TERMS_VERSION_DUPLICATED"));
    }

    @Test
    @DisplayName("★ 필수 약관은 철회할 수 없다 — 서비스를 쓰면서 이용약관 미동의는 모순이다")
    void requiredTermsCannotBeRevoked() throws Exception {
        long termsId = createTerms("SERVICE", "v1.0", true);

        mvc.perform(post("/api/v1/app/settings/terms/{id}", termsId)
                        .header("Authorization", appToken(PARENT_PHONE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agreed":false}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUIRED_TERMS_CANNOT_BE_REVOKED"));
    }

    @Test
    @DisplayName("선택 약관은 철회된다 — 철회도 이력으로 남는다")
    void optionalTermsCanBeRevoked() throws Exception {
        long termsId = createTerms("MARKETING", "v1.0", false);
        String token = appToken(PARENT_PHONE);

        for (boolean agreed : new boolean[]{true, false}) {
            mvc.perform(post("/api/v1/app/settings/terms/{id}", termsId)
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"agreed":%b}""".formatted(agreed))).andExpect(status().isOk());
            em.flush();
        }

        // 현재 상태는 철회
        mvc.perform(get("/api/v1/app/settings/terms").header("Authorization", token))
                .andExpect(jsonPath("$.data[0].agreed").value(false));

        // 이력은 2건 다 남는다
        Long rows = em.createQuery("""
                SELECT COUNT(a) FROM TermAgreement a WHERE a.account.id = :id
                """, Long.class).setParameter("id", myAccountId).getSingleResult();
        assertThat(rows).isEqualTo(2);
    }

    // ── 알림 수신 ────────────────────────────────────────────────

    @Test
    @DisplayName("★ 설정을 한 번도 안 만졌으면 전부 수신이다 — 행이 없으면 on(opt-out)")
    void defaultIsEnabled() throws Exception {
        mvc.perform(get("/api/v1/app/settings/notifications")
                        .header("Authorization", appToken(PARENT_PHONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.enabled == false)]").isEmpty());
    }

    @Test
    @DisplayName("★ 미등원 알림은 끌 수 없다 — 학생이 안 온 것을 학부모가 안 받겠다고 할 수 없다")
    void mandatoryNotificationCannotBeDisabled() throws Exception {
        mvc.perform(put("/api/v1/app/settings/notifications/{event}", "MISSING_ATTENDANCE")
                        .header("Authorization", appToken(PARENT_PHONE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUIRED_NOTIFICATION_CANNOT_BE_DISABLED"));
    }

    @Test
    @DisplayName("끌 수 없는 알림은 mandatory로 표시된다 — 앱이 토글을 비활성으로 그려야 한다")
    void mandatoryFlagIsExposed() throws Exception {
        mvc.perform(get("/api/v1/app/settings/notifications")
                        .header("Authorization", appToken(PARENT_PHONE)))
                .andExpect(jsonPath("$.data[?(@.event == 'MISSING_ATTENDANCE')].mandatory")
                        .value(true));
    }

    @Test
    @DisplayName("일반 알림은 껐다 켰다 된다")
    void optionalNotificationToggles() throws Exception {
        String token = appToken(PARENT_PHONE);

        mvc.perform(put("/api/v1/app/settings/notifications/{event}", "APPROVAL_REJECTED")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"enabled":false}""")).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/settings/notifications").header("Authorization", token))
                .andExpect(jsonPath("$.data[?(@.event == 'APPROVAL_REJECTED')].enabled").value(false));
    }

    // ── FCM 토큰 ─────────────────────────────────────────────────

    @Test
    @DisplayName("★ 같은 기기를 다른 계정이 쓰면 토큰 주인이 바뀐다 — 안 그러면 이전 사용자에게 남의 자녀 알림이 간다")
    void tokenMovesToNewOwner() throws Exception {
        String deviceToken = "fcm-token-same-device";

        mvc.perform(post("/api/v1/app/settings/push-token")
                .header("Authorization", appToken(PARENT_PHONE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","platform":"IOS"}""".formatted(deviceToken)))
                .andExpect(status().isOk());
        em.flush();

        // 같은 기기에서 로그아웃 후 다른 계정 로그인 → 같은 토큰이 다시 올라온다
        mvc.perform(post("/api/v1/app/settings/push-token")
                .header("Authorization", appToken(OTHER_PHONE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","platform":"IOS"}""".formatted(deviceToken)))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        // 행이 두 개로 늘지 않고, 이전 사용자에게는 남지 않는다
        Long rows = em.createQuery("""
                SELECT COUNT(p) FROM PushToken p WHERE p.token = :t AND p.deleted = false
                """, Long.class).setParameter("t", deviceToken).getSingleResult();
        assertThat(rows).isEqualTo(1);

        Long stillMine = em.createQuery("""
                SELECT COUNT(p) FROM PushToken p
                WHERE p.account.id = :id AND p.deleted = false
                """, Long.class).setParameter("id", myAccountId).getSingleResult();
        assertThat(stillMine).isZero();
    }

    @Test
    @DisplayName("한 계정에 기기 여러 대가 붙는다")
    void multipleDevicesPerAccount() throws Exception {
        String token = appToken(PARENT_PHONE);
        for (String device : new String[]{"fcm-phone", "fcm-tablet"}) {
            mvc.perform(post("/api/v1/app/settings/push-token")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"token":"%s","platform":"ANDROID"}""".formatted(device)))
                    .andExpect(status().isOk());
            em.flush();
        }

        Long rows = em.createQuery("""
                SELECT COUNT(p) FROM PushToken p WHERE p.account.id = :id AND p.deleted = false
                """, Long.class).setParameter("id", myAccountId).getSingleResult();
        assertThat(rows).isEqualTo(2);
    }

    @Test
    @DisplayName("토큰 해제는 soft delete다 — 만료 단말 목록에서 언제부터 안 쓰였는지 봐야 한다")
    void removeTokenIsSoftDelete() throws Exception {
        String token = appToken(PARENT_PHONE);
        mvc.perform(post("/api/v1/app/settings/push-token")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"fcm-to-remove","platform":"IOS"}""")).andExpect(status().isOk());
        em.flush();

        mvc.perform(delete("/api/v1/app/settings/push-token")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"fcm-to-remove"}""")).andExpect(status().isOk());
        em.flush();
        em.clear();

        Long alive = em.createQuery("""
                SELECT COUNT(p) FROM PushToken p WHERE p.token = 'fcm-to-remove' AND p.deleted = false
                """, Long.class).getSingleResult();
        assertThat(alive).isZero();

        Long total = em.createQuery("""
                SELECT COUNT(p) FROM PushToken p WHERE p.token = 'fcm-to-remove'
                """, Long.class).getSingleResult();
        assertThat(total).isEqualTo(1);
    }
}
