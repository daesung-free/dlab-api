package com.dlab.api.meal;

import com.dlab.domain.meal.entity.MealClosure;
import com.dlab.domain.meal.entity.MealOrderWindow;
import com.dlab.domain.meal.entity.MealPolicy;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 앱 급식 신청 (A-9).
 *
 * <p>지키려는 것 — <b>가능일을 서버가 내려줄 것</b>, <b>접수기간 밖이면 못 넣을 것</b>,
 * <b>마감 지난 건 취소 못 할 것</b>, <b>학부모가 남의 자녀로 신청 못 할 것</b>.
 *
 * <p>날짜는 {@link Clock} 기준이라 <b>충분히 미래의 달</b>을 쓴다 — 오늘에 붙으면
 * 마감(D-3) 검사에 걸려 테스트가 날짜에 따라 흔들린다.
 */
@SpringBootTest
@Transactional
class AppMealFlowTest {

    private static final String PASSWORD = "meal-password-1234";
    private static final short YEAR = 2026;

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Student myChild;
    Student otherChild;
    String studentPhone = "010-6000-0001";
    String parentPhone = "010-6000-0002";

    /** 마감 D-n. 아래 {@link MealPolicy}에 넣는 값과 같아야 한다. */
    static final int DEADLINE_DAYS = 3;

    /** 대상 월(다음 달). ⚠️ 달만 띄운다고 마감을 피할 수 있는 게 아니다 — 아래 참고. */
    YearMonth targetMonth;
    LocalDate weekday1;
    LocalDate weekday2;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("ML01", "급식테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        myChild = new Student("MLSTU001", "내자녀", studentPhone);
        em.persist(myChild);
        em.persist(new StudentEnrollment(myChild, academy, YEAR, "2026-0001", null, GradeType.N_SU));
        Account studentAccount = Account.forStudent(myChild, studentPhone,
                passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);

        otherChild = new Student("MLSTU002", "남의자녀", "010-6000-0009");
        em.persist(otherChild);
        em.persist(new StudentEnrollment(otherChild, academy, YEAR, "2026-0002", null, GradeType.N_SU));

        ParentGuardian guardian = new ParentGuardian("내학부모", parentPhone, null);
        em.persist(guardian);
        em.persist(new StudentGuardianLink(myChild, guardian, (short) 1, true));
        em.persist(Account.forGuardian(guardian, parentPhone, passwordEncoder.encode(PASSWORD)));

        // 마감 3일
        em.persist(new MealPolicy(academy, YEAR, (short) DEADLINE_DAYS));

        // 다음 달을 대상으로 하고 접수기간은 오늘을 포함하게 연다
        targetMonth = YearMonth.from(LocalDate.now(clock)).plusMonths(1);
        em.persist(new MealOrderWindow(academy, YEAR, targetMonth,
                LocalDate.now(clock).minusDays(3), LocalDate.now(clock).plusDays(10)));

        weekday1 = firstWeekdaysOf(targetMonth, 0);
        weekday2 = firstWeekdaysOf(targetMonth, 1);
        em.flush();
    }

    /**
     * 그 달의 n번째 <b>신청 가능한</b> 평일. 주말은 급식 가능일에서 빠지므로 평일만 고른다.
     *
     * <p>★ <b>마감(D-n)이 지난 날은 건너뛴다.</b> "다음 달이니 넉넉하다"가 성립하지 않는다 —
     * 월말에 돌리면 다음 달 초가 이미 마감이다(8/30에 9/1은 D-1). 실제로 8월 말에
     * CI가 이 이유로 6건 깨졌다. {@code isBeforeDeadline}이
     * <i>오늘 ≤ 이용일 − n</i>이므로 <b>오늘 + n</b>부터 신청할 수 있고, 하루 여유를 둔다.
     */
    private LocalDate firstWeekdaysOf(YearMonth month, int skip) {
        LocalDate earliest = LocalDate.now(clock).plusDays(DEADLINE_DAYS + 1L);
        LocalDate date = month.atDay(1);
        if (date.isBefore(earliest)) {
            date = earliest;
        }
        int found = 0;
        while (true) {
            if (date.getDayOfWeek().getValue() <= 5) {
                if (found == skip) {
                    return date;
                }
                found++;
            }
            date = date.plusDays(1);
        }
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

    private ResultActions apply(String phone, Long studentId, LocalDate... dates) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < dates.length; i++) {
            if (i > 0) {
                items.append(",");
            }
            items.append("""
                    {"date":"%s","mealType":"LUNCH"}""".formatted(dates[i]));
        }
        var request = post("/api/v1/app/meals/orders")
                .header("Authorization", token(phone))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"selections":[%s]}""".formatted(items));
        if (studentId != null) {
            request = request.param("studentId", String.valueOf(studentId));
        }
        return mvc.perform(request);
    }

    // ── 메뉴(가능일) ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 가능일을 서버가 내려준다 — 앱이 자체 판정하면 임시공휴일마다 배포해야 한다")
    void menuComesFromServer() throws Exception {
        mvc.perform(get("/api/v1/app/meals/menu")
                        .header("Authorization", token(studentPhone))
                        .param("month", targetMonth.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowOpen").value(true))
                .andExpect(jsonPath("$.data.deadlineDays").value(3))
                .andExpect(jsonPath("$.data.availableDates").isNotEmpty());
    }

    @Test
    @DisplayName("★ 중단일은 가능일에서 빠지고 사유와 함께 내려간다")
    void closureExcludedFromAvailableDates() throws Exception {
        em.persist(new MealClosure(academy, YEAR, weekday1, "급식업체 휴무"));
        em.flush();

        mvc.perform(get("/api/v1/app/meals/menu")
                        .header("Authorization", token(studentPhone))
                        .param("month", targetMonth.toString()))
                .andExpect(jsonPath("$.data.closures.length()").value(1))
                .andExpect(jsonPath("$.data.closures[0].reason").value("급식업체 휴무"))
                .andExpect(jsonPath("$.data.availableDates[?(@ == '%s')]".formatted(weekday1))
                        .isEmpty());
    }

    // ── 신청 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 한 달치를 한 번에 신청하고, 응답에 itemId가 채워진다 — 없으면 바로 취소를 못 한다")
    void applyBatch() throws Exception {
        apply(studentPhone, null, weekday1, weekday2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                // addItem이 메모리에만 넣어서 flush 전에는 id가 비어 있다
                .andExpect(jsonPath("$.data.items[0].itemId").isNumber());
    }

    @Test
    @DisplayName("★ 접수기간이 안 열렸으면 신청할 수 없다 — 미등록도 닫힘이다")
    void windowClosed() throws Exception {
        YearMonth other = targetMonth.plusMonths(1);   // 접수기간을 안 만든 달
        LocalDate date = firstWeekdaysOf(other, 0);

        mvc.perform(post("/api/v1/app/meals/orders")
                        .header("Authorization", token(studentPhone))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selections":[{"date":"%s","mealType":"LUNCH"}]}""".formatted(date)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEAL_WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("같은 날 같은 끼니를 두 번 신청할 수 없다")
    void duplicateRejected() throws Exception {
        apply(studentPhone, null, weekday1).andExpect(status().isOk());
        em.flush();

        apply(studentPhone, null, weekday1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEAL_ALREADY_APPLIED"));
    }

    @Test
    @DisplayName("★ 전부-아니면-전무다 — 한 건만 어긋나도 전체가 거부된다")
    void allOrNothing() throws Exception {
        apply(studentPhone, null, weekday1).andExpect(status().isOk());
        em.flush();

        // weekday1은 중복, weekday2는 정상 → 전체 거부
        apply(studentPhone, null, weekday1, weekday2).andExpect(status().isConflict());
        em.flush();
        em.clear();

        // weekday2가 들어가지 않았어야 한다
        mvc.perform(get("/api/v1/app/meals/orders")
                        .header("Authorization", token(studentPhone))
                        .param("month", targetMonth.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    // ── 조회 · 취소 ──────────────────────────────────────────────

    @Test
    @DisplayName("신청 전에는 내역이 비어 있다 — 404가 아니라 null이다")
    void emptyOrderIsNull() throws Exception {
        mvc.perform(get("/api/v1/app/meals/orders")
                        .header("Authorization", token(studentPhone))
                        .param("month", targetMonth.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("★ 항목마다 취소 가능 여부가 함께 나온다 — 앱이 버튼을 비활성으로 그려야 한다")
    void cancelableFlag() throws Exception {
        apply(studentPhone, null, weekday1).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/meals/orders")
                        .header("Authorization", token(studentPhone))
                        .param("month", targetMonth.toString()))
                // 다음 달이라 마감(D-3) 전이다
                .andExpect(jsonPath("$.data.items[0].cancelable").value(true));
    }

    @Test
    @DisplayName("취소하면 내역에서 빠진다 — 달력에 취소한 날이 신청된 것처럼 보이면 안 된다")
    void cancelRemovesFromList() throws Exception {
        String body = apply(studentPhone, null, weekday1, weekday2)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        long itemId = objectMapper.readTree(body).path("data").path("items").get(0)
                .path("itemId").asLong();

        mvc.perform(delete("/api/v1/app/meals/orders/items/{id}", itemId)
                .header("Authorization", token(studentPhone))).andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/app/meals/orders")
                        .header("Authorization", token(studentPhone))
                        .param("month", targetMonth.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    // ── 학부모 ───────────────────────────────────────────────────

    @Test
    @DisplayName("학부모는 자녀를 지정해서 신청한다")
    void parentAppliesForChild() throws Exception {
        apply(parentPhone, myChild.getId(), weekday1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    @DisplayName("★ 남의 자녀로는 신청할 수 없다")
    void parentCannotApplyForOthers() throws Exception {
        apply(parentPhone, otherChild.getId(), weekday1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MY_CHILD"));
    }

    @Test
    @DisplayName("학부모가 자녀를 안 지정하면 누구 것인지 알 수 없다")
    void parentMustSpecifyChild() throws Exception {
        apply(parentPhone, null, weekday1).andExpect(status().isBadRequest());
    }
}
