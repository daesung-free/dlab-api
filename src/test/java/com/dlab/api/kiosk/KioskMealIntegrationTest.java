package com.dlab.api.kiosk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.meal.entity.MealApplication;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 키오스크 급식 조회 (DSA 3.30 · 3.31).
 *
 * <p><b>여기서 틀리면 급식이 조용히 뚫린다.</b> 키오스크는 호출 실패·필드 누락 시
 * 전부 허용으로 폴백하므로, 잘못된 응답이 에러가 아니라 <b>무단 배식</b>으로 나타난다.
 */
@SpringBootTest
@Transactional
class KioskMealIntegrationTest {

    private static final String CLIENT_ID = "kiosk-meal-client";
    private static final String SECRET = "kiosk-meal-secret";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired com.dlab.domain.kiosk.service.DsaTokenService tokenService;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    String token;
    Academy bundang;
    StudentEnrollment minji;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        today = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        BranchConfig config = new BranchConfig(bundang.getId());
        config.issueKioskCredential(CLIENT_ID, SECRET);
        em.persist(config);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", "ABC001", GradeType.HIGH3);
        em.persist(minji);
        em.flush();
        em.clear();

        bundang = em.find(Academy.class, bundang.getId());
        minji = em.find(StudentEnrollment.class, minji.getId());

        token = tokenService.issue("31", CLIENT_ID, md5Secret()).token();
    }

    private String md5Secret() throws Exception {
        String raw = LocalDate.now(clock)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + SECRET;
        byte[] digest = java.security.MessageDigest.getInstance("MD5")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private ResultActions applyYn(String mealGb) throws Exception {
        return mvc.perform(post("/kiosk/getMealApplyYN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\""
                                + ",\"meal_gb\":\"" + mealGb + "\"}"))
                .andExpect(status().isOk());
    }

    private MealApplication apply(LocalDate date, MealType type) {
        MealApplication application = new MealApplication(minji, date, type);
        em.persist(application);
        em.flush();
        return application;
    }

    @Test
    @DisplayName("★ 신청 안 했으면 N — 기본값이 허용으로 기울면 안 된다")
    void notAppliedIsN() throws Exception {
        applyYn("L").andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.meal_yn").value("N"));
    }

    @Test
    @DisplayName("신청했으면 Y. meal_yn은 최상위다(키오스크가 extra에서 꺼낸다)")
    void appliedIsY() throws Exception {
        apply(today, MealType.LUNCH);

        applyYn("L").andExpect(jsonPath("$.meal_yn").value("Y"))
                .andExpect(jsonPath("$.data.meal_yn").doesNotExist());
    }

    @Test
    @DisplayName("★ 점심과 저녁은 별개다 — 점심 신청이 저녁 통과로 새면 안 된다")
    void lunchAndDinnerAreIndependent() throws Exception {
        apply(today, MealType.LUNCH);

        applyYn("L").andExpect(jsonPath("$.meal_yn").value("Y"));
        applyYn("D").andExpect(jsonPath("$.meal_yn").value("N"));
    }

    @Test
    @DisplayName("★ 취소분은 N이다")
    void canceledApplicationIsNotCounted() throws Exception {
        MealApplication application = apply(today, MealType.LUNCH);
        applyYn("L").andExpect(jsonPath("$.meal_yn").value("Y"));

        application.cancel(Instant.now(clock));
        em.flush();

        applyYn("L").andExpect(jsonPath("$.meal_yn").value("N"));
    }

    @Test
    @DisplayName("★ 어제 신청은 오늘 통과가 아니다")
    void yesterdayApplicationDoesNotPassToday() throws Exception {
        apply(today.minusDays(1), MealType.LUNCH);

        applyYn("L").andExpect(jsonPath("$.meal_yn").value("N"));
    }

    @Test
    @DisplayName("★ 알 수 없는 meal_gb는 901 — 허용으로 기울지 않는다")
    void unknownMealGbIsParameterError() throws Exception {
        applyYn("X").andExpect(jsonPath("$.code").value(DsaCode.INVALID_PARAMETER.value()));
    }

    @Test
    @DisplayName("★ 등록되지 않은 카드는 101 — 모르는 학생을 통과시키지 않는다")
    void unknownCardIsRejected() throws Exception {
        mvc.perform(post("/kiosk/getMealApplyYN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rfid_no\":\"NOPE\""
                                + ",\"meal_gb\":\"L\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_KEY.value()));
    }

    @Test
    @DisplayName("★ getMealApplyStdInfo의 day는 '일'만 — 전체 날짜면 키오스크가 조용히 버린다")
    void monthlyRowsCarryDayOfMonthOnly() throws Exception {
        LocalDate fifth = today.withDayOfMonth(5);
        apply(fifth, MealType.LUNCH);
        apply(fifth, MealType.DINNER);

        mvc.perform(post("/kiosk/getMealApplyStdInfo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"month\":\""
                                + YearMonth.from(today) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].day").value("5"))
                .andExpect(jsonPath("$.data[0].std_no").value("2026-0001"))
                // 목록은 공용 화면이라 이름을 마스킹한다
                .andExpect(jsonPath("$.data[0].std_nm").value("김*지"))
                // ★ 응답 meal_gb는 L/D가 아니라 "점심"/"저녁"이다 — 키오스크가 contains로 본다
                .andExpect(jsonPath("$.data[?(@.meal_gb=='점심')]").exists())
                .andExpect(jsonPath("$.data[?(@.meal_gb=='저녁')]").exists());
    }
}
