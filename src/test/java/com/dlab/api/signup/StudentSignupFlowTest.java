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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 학생 자가가입 (앱 A-2 · F-4.12-1).
 *
 * <p>지키려는 것은 넷이다 — <b>승인 전 로그인 차단</b>, <b>중복 신청 차단(대기 중인 것 포함)</b>,
 * <b>인증 없이는 가입 불가</b>, <b>사람+등록 건 2단이 함께 생성</b>.
 */
@SpringBootTest
@Transactional
class StudentSignupFlowTest {

    private static final String PHONE = "010-5555-6666";
    private static final String PASSWORD = "student-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;
    @MockitoBean SmsSender smsSender;

    MockMvc mvc;
    Academy academy;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        redis.delete(redis.keys("verify:*"));

        academy = new Academy("SS01", "학생가입테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();
    }

    private String verifyPhone(String phone) throws Exception {
        mvc.perform(post("/api/v1/app/signup/phone-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s"}""".formatted(phone)))
                .andExpect(status().isOk());

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

    private ResultActions signup(String token) throws Exception {
        return mvc.perform(post("/api/v1/app/signup/student")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"verificationToken":"%s","name":"김학생","password":"%s",
                         "academyId":%d,"grade":"HIGH3","track":"SCIENCE","schoolName":"분당고"}"""
                        .formatted(token, PASSWORD, academy.getId())));
    }

    // ─────────────────────────────────────────── 가입

    @Test
    @DisplayName("★ 가입하면 승인 대기(PENDING)다 — 학부모와 달리 즉시 이용할 수 없다")
    void signsUpAsPending() throws Exception {
        signup(verifyPhone(PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value(PHONE))
                .andExpect(jsonPath("$.data.pendingApproval").value(true))
                .andExpect(jsonPath("$.data.uniqueCode").isNotEmpty());
        em.flush();
        em.clear();

        AccountStatus status = em.createQuery("""
                SELECT a.status FROM Account a WHERE a.loginId = :phone
                """, AccountStatus.class).setParameter("phone", PHONE).getSingleResult();
        assertThat(status).isEqualTo(AccountStatus.PENDING);
    }

    @Test
    @DisplayName("★ 승인 전에는 로그인 자체가 거부된다 — '승인 대기 화면'을 열어주지 않는다")
    void cannotLoginBeforeApproval() throws Exception {
        signup(verifyPhone(PHONE)).andExpect(status().isOk());
        em.flush();

        mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(PHONE, PASSWORD)))
                .andExpect(jsonPath("$.error.code").value("SIGNUP_PENDING"));
    }

    @Test
    @DisplayName("사람 + 등록 건이 함께 만들어지고 학번이 채번된다")
    void createsStudentAndEnrollment() throws Exception {
        signup(verifyPhone(PHONE)).andExpect(status().isOk());
        em.flush();
        em.clear();

        StudentEnrollment enrollment = em.createQuery("""
                SELECT e FROM StudentEnrollment e
                WHERE e.academy.id = :academyId AND e.deleted = false
                """, StudentEnrollment.class)
                .setParameter("academyId", academy.getId()).getSingleResult();

        assertThat(enrollment.getStudent().getName()).isEqualTo("김학생");
        assertThat(enrollment.getStudentNo()).isNotBlank();
        assertThat(enrollment.getGrade()).isEqualTo(GradeType.HIGH3);
        assertThat(enrollment.isCurrent()).isTrue();
        // 카드는 아직 없다 — 발급은 등원 시점 운영 절차다
        assertThat(enrollment.getRfidNo()).isNull();
    }

    @Test
    @DisplayName("★ 인증 토큰 없이는 가입할 수 없다")
    void cannotSignupWithoutVerification() throws Exception {
        signup("made-up-token")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PHONE_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("★ 승인 대기 중인 신청이 있으면 재신청이 막힌다 — 대기 목록에 같은 사람이 쌓인다")
    void rejectsDuplicateWhilePending() throws Exception {
        signup(verifyPhone(PHONE)).andExpect(status().isOk());
        em.flush();

        signup(verifyPhone(PHONE))
                .andExpect(jsonPath("$.error.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("지점을 지정하지 않으면 가입할 수 없다")
    void academyIsRequired() throws Exception {
        mvc.perform(post("/api/v1/app/signup/student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s","name":"김학생","password":"%s","grade":"HIGH3"}"""
                                .formatted(verifyPhone(PHONE), PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지점 목록은 인증 없이 열린다 — 가입 시점엔 토큰이 없다")
    void academyListIsOpen() throws Exception {
        mvc.perform(get("/api/v1/app/signup/academies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == '학생가입테스트지점')]").exists());
    }
}
