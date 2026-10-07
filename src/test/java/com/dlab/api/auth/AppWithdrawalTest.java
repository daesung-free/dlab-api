package com.dlab.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.appconfig.repository.PushTokenRepository;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentGuardianLinkRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
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
 * 회원 탈퇴 (App Store 5.1.1(v) · Google Play 필수 요건).
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>탈퇴하면 다시 로그인되지 않는다</b> — 그리고 퇴원으로 끊긴 것과 <b>다른 문구</b>가 나간다</li>
 *   <li><b>들고 있던 토큰이 그 자리에서 무효가 된다</b> — 안 막으면 만료까지 조회가 된다</li>
 *   <li><b>앱이 수집한 것만 지운다</b> — 원생 기록은 남는다(지점 정산·과거 통계가 소급해 바뀐다)</li>
 *   <li><b>두 번 눌러도 성공이다</b> — 두 번째를 실패로 만들면 화면은 "안 됐다"로 보인다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class AppWithdrawalTest {

    private static final String PASSWORD = "withdrawal-password-1234";
    private static final String STUDENT_PHONE = "010-7000-0001";
    private static final String PARENT_PHONE = "010-7000-0002";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired PushTokenRepository pushTokenRepository;
    @Autowired StudentGuardianLinkRepository guardianLinkRepository;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Student child;
    StudentEnrollment enrollment;
    Account studentAccount;
    Account parentAccount;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        short year = (short) LocalDate.now().getYear();

        Academy academy = new Academy("WD01", "탈퇴테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        child = new Student("WDSTU001", "탈퇴학생", STUDENT_PHONE);
        em.persist(child);
        enrollment = new StudentEnrollment(child, academy, year, "2026-0001", null, GradeType.N_SU);
        em.persist(enrollment);

        studentAccount = Account.forStudent(child, STUDENT_PHONE, passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);

        ParentGuardian guardian = new ParentGuardian("탈퇴학부모", PARENT_PHONE, "F");
        em.persist(guardian);
        em.persist(new StudentGuardianLink(child, guardian, (short) 1, true));
        parentAccount = Account.forGuardian(guardian, PARENT_PHONE, passwordEncoder.encode(PASSWORD));
        em.persist(parentAccount);

        em.flush();
    }

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private org.springframework.test.web.servlet.ResultActions withdraw(String accessToken)
            throws Exception {
        return mvc.perform(delete("/api/v1/app/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"앱을 더 쓰지 않습니다"}"""));
    }

    @Test
    @DisplayName("★ 탈퇴하면 다시 로그인되지 않는다 — 퇴원과 다른 문구가 나간다")
    void cannotLoginAfterWithdrawal() throws Exception {
        withdraw(token(STUDENT_PHONE)).andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(STUDENT_PHONE, PASSWORD)))
                .andExpect(status().isForbidden())
                // ★ ACCOUNT_NOT_ACTIVE 가 아니다 — 본인 탈퇴면 "다시 가입하라"가 맞고
                //   퇴원이면 "학원에 문의하라"가 맞다. 같은 코드면 데스크 문의가 늘어난다
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_WITHDRAWN"));
    }

    @Test
    @DisplayName("★ 들고 있던 토큰이 그 자리에서 막힌다 — 안 막으면 만료까지 조회가 된다")
    void existingTokenIsBlockedImmediately() throws Exception {
        String accessToken = token(STUDENT_PHONE);

        // 탈퇴 전에는 조회가 된다
        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        withdraw(accessToken).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("본인 탈퇴와 퇴원 처리가 구분된다 — 상태만 보면 둘 다 WITHDRAWN 이다")
    void distinguishesOwnerWithdrawalFromDeactivation() throws Exception {
        withdraw(token(STUDENT_PHONE));
        em.flush();
        em.clear();

        Account reloaded = accountRepository.findById(studentAccount.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.WITHDRAWN);
        assertThat(reloaded.isWithdrawnByOwner()).isTrue();
    }

    @Test
    @DisplayName("★ 앱이 수집한 것만 지운다 — 원생 기록은 남는다")
    void keepsAcademyRecords() throws Exception {
        String accessToken = token(STUDENT_PHONE);
        mvc.perform(post("/api/v1/app/settings/push-token")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"fcm-withdrawal-test","platform":"ANDROID"}"""))
                .andExpect(status().isOk());
        em.flush();

        withdraw(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removedPushTokens").value(1));
        em.flush();
        em.clear();

        // 푸시 토큰은 사라진다 — 탈퇴한 사람에게 알림이 가는 것이 가장 먼저 보인다
        assertThat(pushTokenRepository.findByAccountIdAndDeletedFalse(studentAccount.getId()))
                .isEmpty();
        // ★ 등록 건은 남는다. 같이 지우면 지점 정산과 과거 통계가 소급해서 바뀐다
        assertThat(em.find(StudentEnrollment.class, enrollment.getId())).isNotNull();
        assertThat(em.find(Student.class, child.getId())).isNotNull();
    }

    @Test
    @DisplayName("★ 학부모가 탈퇴하면 자녀 연결이 끊긴다 — 안 끊으면 승인자가 없는 채 대기한다")
    void guardianWithdrawalUnlinksChildren() throws Exception {
        withdraw(token(PARENT_PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unlinkedChildren").value(1));
        em.flush();
        em.clear();

        assertThat(guardianLinkRepository.findByStudentId(child.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 두 번 눌러도 성공이다 — 실패로 만들면 화면은 「탈퇴가 안 됐다」로 보인다")
    void secondAttemptIsIdempotent() throws Exception {
        String accessToken = token(STUDENT_PHONE);
        withdraw(accessToken).andExpect(jsonPath("$.data.alreadyWithdrawn").value(false));
        em.flush();

        // 토큰이 이미 막혔으므로 재로그인은 불가 — 서비스 멱등성만 직접 확인한다
        var service = context.getBean(com.dlab.domain.user.service.AppWithdrawalService.class);
        var again = service.withdraw(studentAccount.getId(), "다시", null);

        assertThat(again.alreadyWithdrawn()).isTrue();
        assertThat(again.removedPushTokens()).isZero();
    }

    @Test
    @DisplayName("직원 계정은 앱에서 탈퇴할 수 없다 — 계정 해지는 인사 절차다")
    void staffCannotWithdrawFromApp() {
        Academy academy = em.find(StudentEnrollment.class, enrollment.getId()).getAcademy();
        Employee employee = new Employee(academy, "행정쌤");
        em.persist(employee);
        Account staff = Account.forEmployee(employee, "WD-STAFF",
                passwordEncoder.encode(PASSWORD), false);
        em.persist(staff);
        em.flush();

        var service = context.getBean(com.dlab.domain.user.service.AppWithdrawalService.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.withdraw(staff.getId(), null, null))
                .isInstanceOf(com.dlab.common.exception.BusinessException.class);
    }
}
