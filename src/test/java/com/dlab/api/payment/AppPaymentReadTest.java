package com.dlab.api.payment;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.payment.entity.*;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
 * 앱 청구·결제 내역 조회 (A-13).
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>완납 건도 내려간다</b> — 미납만 주면 "낸 것"이 사라져 납부 사실을 확인할 수 없다</li>
 *   <li><b>항목을 나눠 보여준다</b> — 교습비·독서실비가 한 청구 안의 다른 항목이다</li>
 *   <li><b>취소된 거래도 표시한다</b> — 빼면 카드사 명세와 대조가 안 된다</li>
 *   <li><b>남의 청구는 「없다」로 답한다</b> — 403이면 그 청구가 있다는 사실이 새어나간다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class AppPaymentReadTest {

    private static final String PASSWORD = "payment-read-password-1234";
    private static final String STUDENT_PHONE = "010-8100-0001";
    private static final String PARENT_PHONE = "010-8100-0002";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Student child;
    StudentEnrollment enrollment;
    Billing tuition;
    Long otherBillingId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        short year = (short) LocalDate.now().getYear();

        Academy academy = new Academy("PR01", "결제조회지점", LocalTime.of(9, 0));
        em.persist(academy);

        child = new Student("PRSTU001", "결제학생", STUDENT_PHONE);
        em.persist(child);
        enrollment = new StudentEnrollment(child, academy, year, "2026-0001", null, GradeType.N_SU);
        em.persist(enrollment);

        Account studentAccount = Account.forStudent(child, STUDENT_PHONE,
                passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);

        ParentGuardian guardian = new ParentGuardian("결제학부모", PARENT_PHONE, "F");
        em.persist(guardian);
        em.persist(new StudentGuardianLink(child, guardian, (short) 1, true));
        em.persist(Account.forGuardian(guardian, PARENT_PHONE, passwordEncoder.encode(PASSWORD)));

        // N수 750,000 = 교습비 660,000 + 독서실비 90,000. 일부 납부 상태로 둔다
        tuition = new Billing(enrollment, "2026년 10월 교습비", BillingType.TUITION,
                750_000, 0, LocalDate.now().minusDays(1));
        tuition.assignServicePeriod(year, 10);
        tuition.addItem(BillingItemType.TUITION, 660_000, 0);
        tuition.addItem(BillingItemType.STUDY_ROOM, 90_000, 0);
        em.persist(tuition);
        tuition.addPayment(500_000, PaymentMethod.CARD, Instant.now().minusSeconds(3600));
        // 취소된 거래 — 빼지 않고 표시해야 한다
        var canceled = tuition.addPayment(100_000, PaymentMethod.VBANK,
                Instant.now().minusSeconds(7200));
        canceled.cancel(Instant.now());
        tuition.refreshStatus();

        // 완납 건 — 미납만 주면 이것이 사라진다
        Billing meal = new Billing(enrollment, "2026년 9월 급식비", BillingType.MEAL,
                154_000, 0, LocalDate.now().minusDays(10));
        em.persist(meal);
        meal.addPayment(154_000, PaymentMethod.CARD, Instant.now().minusSeconds(86_400));

        // 남의 청구
        Student other = new Student("PRSTU002", "남의학생", "010-8100-0009");
        em.persist(other);
        StudentEnrollment otherEnrollment = new StudentEnrollment(
                other, academy, year, "2026-0002", null, GradeType.N_SU);
        em.persist(otherEnrollment);
        Billing otherBilling = new Billing(otherEnrollment, "남의 교습비", BillingType.TUITION,
                750_000, 0, LocalDate.now());
        em.persist(otherBilling);
        em.flush();
        otherBillingId = otherBilling.getId();
    }

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    @Test
    @DisplayName("★ 완납 건도 함께 내려간다 — 미납만 주면 「낸 것」이 사라진다")
    void includesPaidBillings() throws Exception {
        mvc.perform(get("/api/v1/app/payments/billings")
                        .header("Authorization", token(STUDENT_PHONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // 미납만 보고 싶을 때는 걸러 준다
        mvc.perform(get("/api/v1/app/payments/billings")
                        .header("Authorization", token(STUDENT_PHONE))
                        .param("unpaidOnly", "true"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].unpaidAmount").value(250_000))
                // 기한이 지났는데 남았으면 연체다 — 화면이 빨갛게 칠하는 근거
                .andExpect(jsonPath("$.data[0].overdue").value(true));
    }

    @Test
    @DisplayName("★ 취소된 거래는 빠지지 않고 취소 표시로 내려간다 — 카드사 명세와 대조해야 한다")
    void keepsCanceledTransactions() throws Exception {
        mvc.perform(get("/api/v1/app/payments")
                        .header("Authorization", token(PARENT_PHONE))
                        .param("studentId", String.valueOf(child.getId())))
                .andExpect(status().isOk())
                // 500,000(유효) + 100,000(취소) + 154,000(완납 건) = 3건
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.canceled == true)].amount").value(100_000));
    }

    @Test
    @DisplayName("★ 상세는 항목을 나눠 준다 — 합계만 보면 「무엇에 얼마」에 답할 수 없다")
    void detailSplitsItems() throws Exception {
        mvc.perform(get("/api/v1/app/payments/billings/" + tuition.getId())
                        .header("Authorization", token(STUDENT_PHONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing.billedAmount").value(750_000))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].itemType").value("TUITION"))
                .andExpect(jsonPath("$.data.items[0].billedAmount").value(660_000))
                .andExpect(jsonPath("$.data.items[1].itemType").value("STUDY_ROOM"))
                .andExpect(jsonPath("$.data.items[1].billedAmount").value(90_000))
                .andExpect(jsonPath("$.data.payments.length()").value(2));
    }

    @Test
    @DisplayName("★ 남의 청구는 「없다」로 답한다 — 403이면 그 청구가 있다는 사실이 새어나간다")
    void othersBillingLooksMissing() throws Exception {
        mvc.perform(get("/api/v1/app/payments/billings/" + otherBillingId)
                        .header("Authorization", token(STUDENT_PHONE)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("학부모가 자녀를 안 지정하면 누구 것인지 알 수 없다 — 400으로 안내한다")
    void parentMustSpecifyChild() throws Exception {
        mvc.perform(get("/api/v1/app/payments/billings")
                        .header("Authorization", token(PARENT_PHONE)))
                .andExpect(status().isBadRequest());
    }
}
