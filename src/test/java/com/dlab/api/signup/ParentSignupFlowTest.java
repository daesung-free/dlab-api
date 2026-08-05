package com.dlab.api.signup;

import com.dlab.common.verification.SmsSender;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 학부모 가입 · 자녀 연결 (앱 요구사항 A-2).
 *
 * <p>지키려는 것은 넷이다 — <b>인증 없이는 가입 불가</b>, <b>학생당 학부모 1인</b>,
 * <b>자녀는 여러 명</b>, <b>남의 자녀는 못 본다</b>.
 */
@SpringBootTest
@Transactional
class ParentSignupFlowTest {

    private static final String PARENT_PHONE = "010-1111-2222";
    private static final String OTHER_PHONE = "010-3333-4444";
    private static final String PASSWORD = "parent-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;
    /** 발송은 목업이지만, 인증번호를 알아내려면 가로채야 한다. */
    @MockitoBean SmsSender smsSender;

    MockMvc mvc;
    String firstChildCode;
    String secondChildCode;
    String linkedChildCode;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        redis.delete(redis.keys("verify:*"));

        Academy academy = new Academy("PS01", "학부모가입테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        firstChildCode = persistStudent(academy, "PSSTU001", "첫째");
        secondChildCode = persistStudent(academy, "PSSTU002", "둘째");

        // 이미 다른 학부모가 붙어 있는 학생
        linkedChildCode = persistStudent(academy, "PSSTU003", "남의자녀");
        Student linked = em.createQuery(
                        "SELECT s FROM Student s WHERE s.uniqueCode = :code", Student.class)
                .setParameter("code", linkedChildCode).getSingleResult();
        ParentGuardian existing = new ParentGuardian("기존학부모", "010-9999-9999", null);
        em.persist(existing);
        em.persist(new StudentGuardianLink(linked, existing, (short) 1));

        em.flush();
    }

    private String persistStudent(Academy academy, String code, String name) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        em.persist(new StudentEnrollment(student, academy, (short) 2026,
                "2026-" + code.substring(code.length() - 4), null, GradeType.N_SU));
        return code;
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────

    /** 인증번호를 받아 확인까지 마치고 인증 토큰을 돌려준다. */
    private String verifyPhone(String phone) throws Exception {
        mvc.perform(post("/api/v1/app/signup/phone-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s"}""".formatted(phone)))
                .andExpect(status().isOk());

        // 한 테스트에서 같은 번호로 두 번 인증할 수 있다(재가입 시도). 마지막 값이 유효한 번호다.
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(smsSender, atLeastOnce()).sendVerificationCode(eq(phone), code.capture());
        String latest = code.getAllValues().get(code.getAllValues().size() - 1);

        String body = mvc.perform(post("/api/v1/app/signup/phone-verifications/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s"}""".formatted(phone, latest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("verificationToken").asString();
    }

    private ResultActions signup(String token, String childCode) throws Exception {
        return mvc.perform(post("/api/v1/app/signup/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"verificationToken":"%s","name":"학부모","password":"%s","studentUniqueCode":"%s"}"""
                        .formatted(token, PASSWORD, childCode)));
    }

    private String loginToken(String phone) throws Exception {
        String body = mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(phone, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    // ─────────────────────────────────────────────────────────────
    // 가입
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("휴대폰 인증 + 학생 고유ID로 즉시 가입된다 — 승인 절차가 없다(학생과 다름)")
    void signsUpImmediately() throws Exception {
        signup(verifyPhone(PARENT_PHONE), firstChildCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value(PARENT_PHONE));
        em.flush();
        em.clear();

        AccountStatus status = em.createQuery("""
                SELECT a.status FROM Account a WHERE a.loginId = :phone
                """, AccountStatus.class).setParameter("phone", PARENT_PHONE).getSingleResult();
        // 학생이라면 PENDING이어야 하지만 학부모는 즉시 ACTIVE다
        assertThat(status).isEqualTo(AccountStatus.ACTIVE);

        // 그대로 로그인까지 된다
        loginToken(PARENT_PHONE);
    }

    @Test
    @DisplayName("★ 인증 토큰 없이는 가입할 수 없다 — 번호만 받으면 인증을 건너뛸 수 있다")
    void cannotSignupWithoutVerification() throws Exception {
        signup("made-up-token", firstChildCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PHONE_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("★ 인증 토큰은 한 번만 쓸 수 있다 — 재사용되면 토큰 하나로 여러 계정이 생긴다")
    void verificationTokenIsSingleUse() throws Exception {
        String token = verifyPhone(PARENT_PHONE);
        signup(token, firstChildCode).andExpect(status().isOk());
        em.flush();

        signup(token, secondChildCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PHONE_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("인증번호가 틀리면 토큰이 나오지 않는다")
    void wrongCodeRejected() throws Exception {
        mvc.perform(post("/api/v1/app/signup/phone-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"phone":"%s"}""".formatted(PARENT_PHONE))).andExpect(status().isOk());
        verify(smsSender).sendVerificationCode(eq(PARENT_PHONE), any());

        mvc.perform(post("/api/v1/app/signup/phone-verifications/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"000000"}""".formatted(PARENT_PHONE)))
                // 확률상 실제 번호와 같을 수 있지만 6자리라 무시할 수준이다
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 학생 고유ID로는 가입할 수 없다")
    void unknownStudentCodeRejected() throws Exception {
        signup(verifyPhone(PARENT_PHONE), "NO-SUCH-CODE")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_CODE_NOT_FOUND"));
    }

    @Test
    @DisplayName("★ 이미 학부모가 있는 학생에는 연결할 수 없다 — 학부모 최대 1인(I-12 0803)")
    void studentCanHaveOnlyOneGuardian() throws Exception {
        signup(verifyPhone(PARENT_PHONE), linkedChildCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GUARDIAN_ALREADY_LINKED"));
    }

    // ─────────────────────────────────────────────────────────────
    // 자녀 연결 · 전환
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 자녀는 여러 명 연결된다 — 계정을 자녀 수만큼 나누지 않는다")
    void oneAccountManyChildren() throws Exception {
        signup(verifyPhone(PARENT_PHONE), firstChildCode).andExpect(status().isOk());
        em.flush();
        String token = loginToken(PARENT_PHONE);

        mvc.perform(post("/api/v1/app/me/children")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentUniqueCode":"%s"}""".formatted(secondChildCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("둘째"));
        em.flush();

        mvc.perform(get("/api/v1/app/me/children").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("첫째"))
                .andExpect(jsonPath("$.data[0].active").value(true));
    }

    @Test
    @DisplayName("같은 자녀를 두 번 연결할 수 없다")
    void cannotLinkSameChildTwice() throws Exception {
        signup(verifyPhone(PARENT_PHONE), firstChildCode).andExpect(status().isOk());
        em.flush();

        mvc.perform(post("/api/v1/app/me/children")
                        .header("Authorization", loginToken(PARENT_PHONE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentUniqueCode":"%s"}""".formatted(firstChildCode)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHILD_ALREADY_LINKED"));
    }

    @Test
    @DisplayName("★ 남의 자녀는 목록에 없다 — 자녀 전환은 클라이언트 상태일 뿐이라 서버가 매번 확인해야 한다")
    void otherParentsChildrenAreHidden() throws Exception {
        signup(verifyPhone(PARENT_PHONE), firstChildCode).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/me/children").header("Authorization", loginToken(PARENT_PHONE)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("첫째"));
    }

    @Test
    @DisplayName("같은 번호로 두 번 가입할 수 없다 — 알림톡이 어느 계정으로 갈지 모호해진다")
    void duplicatePhoneRejected() throws Exception {
        signup(verifyPhone(PARENT_PHONE), firstChildCode).andExpect(status().isOk());
        em.flush();

        signup(verifyPhone(PARENT_PHONE), secondChildCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("학생 계정은 자녀 목록을 볼 수 없다 — 경로만 알면 호출은 가능하다")
    void studentAccountCannotUseChildrenApi() throws Exception {
        Student student = em.createQuery(
                        "SELECT s FROM Student s WHERE s.uniqueCode = :code", Student.class)
                .setParameter("code", firstChildCode).getSingleResult();
        Account studentAccount = Account.forStudent(student, OTHER_PHONE,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);
        em.flush();

        mvc.perform(get("/api/v1/app/me/children").header("Authorization", loginToken(OTHER_PHONE)))
                .andExpect(status().isForbidden());
    }
}
