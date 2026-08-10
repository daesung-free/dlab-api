package com.dlab.api.routine;

import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.routine.entity.RoutineResultStatus;
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

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 데일리 루틴 (F-4.11-1 · A-11).
 *
 * <p>지키려는 것 — <b>가채점과 검수 점수가 따로 남을 것</b>,
 * <b>검수 중인 점수가 학생에게 안 보일 것</b>, <b>전월 복사가 덮어쓰지 않을 것</b>,
 * <b>그리드가 빈 줄로라도 나올 것</b>.
 */
@SpringBootTest
@Transactional
class DailyRoutineFlowTest {

    private static final String PASSWORD = "routine-password-1234";
    private static final short YEAR = 2026;
    private static final LocalDate DAY = LocalDate.of(2026, 8, 10);

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    Long enrollmentId;
    String studentPhone = "010-4000-0001";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("RT01", "루틴테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        academyId = academy.getId();

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "RTADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId()).executeUpdate();

        Student student = new Student("RTSTU001", "루틴학생", studentPhone);
        em.persist(student);
        StudentEnrollment enrollment =
                new StudentEnrollment(student, academy, YEAR, "2026-0001", null, GradeType.N_SU);
        em.persist(enrollment);
        enrollmentId = enrollment.getId();
        Account studentAccount = Account.forStudent(student, studentPhone,
                passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);
        em.flush();
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

    private String adminToken() throws Exception {
        return token("RTADM", "/api/v1/admin/auth/login");
    }

    private String appToken() throws Exception {
        return token(studentPhone, "/api/v1/app/auth/login");
    }

    /** 그달 루틴 하나 생성. 만점 100, 권장. */
    private long createRoutine(int month, String name, int maxScore) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/routines")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"month":%d,"name":"%s","subject":"국어",
                                 "maxScore":%d,"recommended":true}"""
                                .formatted(academyId, YEAR, month, name, maxScore)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private void saveResult(long routineId, RoutineResultStatus status,
                            Integer selfScore, Integer reviewedScore) throws Exception {
        mvc.perform(put("/api/v1/admin/routines/{id}/results", routineId)
                        .header("Authorization", adminToken())
                        .param("date", DAY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"results":[{"enrollmentId":%d,"status":"%s","selfScore":%s,"reviewedScore":%s}]}"""
                                .formatted(enrollmentId, status,
                                        selfScore == null ? "null" : selfScore,
                                        reviewedScore == null ? "null" : reviewedScore)))
                .andExpect(status().isOk());
        em.flush();
    }

    private DailyRoutineResult result(long routineId) {
        em.clear();
        return em.createQuery("""
                SELECT r FROM DailyRoutineResult r
                WHERE r.routine.id = :rid AND r.enrollment.id = :eid
                """, DailyRoutineResult.class)
                .setParameter("rid", routineId).setParameter("eid", enrollmentId)
                .getSingleResult();
    }

    // ── 세팅 · 전월 복사 ─────────────────────────────────────────

    @Test
    @DisplayName("월별로 루틴을 만들고 조회한다")
    void createAndList() throws Exception {
        createRoutine(8, "국어 데일리테스트", 100);

        mvc.perform(get("/api/v1/admin/routines")
                        .header("Authorization", adminToken())
                        .param("academyId", String.valueOf(academyId))
                        .param("year", String.valueOf(YEAR))
                        .param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("국어 데일리테스트"))
                .andExpect(jsonPath("$.data[0].recommended").value(true));
    }

    @Test
    @DisplayName("전월 복사 — 원본을 가리켜 어디서 왔는지 남는다")
    void copyFromPreviousMonth() throws Exception {
        long source = createRoutine(7, "7월 루틴", 50);

        mvc.perform(post("/api/v1/admin/routines/copy-from-previous-month")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"month":8}""".formatted(academyId, YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.copied").value(1));
        em.flush();

        mvc.perform(get("/api/v1/admin/routines")
                        .header("Authorization", adminToken())
                        .param("academyId", String.valueOf(academyId))
                        .param("year", String.valueOf(YEAR))
                        .param("month", "8"))
                .andExpect(jsonPath("$.data[0].name").value("7월 루틴"))
                .andExpect(jsonPath("$.data[0].copiedFromId").value((int) source));
    }

    @Test
    @DisplayName("★ 대상 월에 이미 루틴이 있으면 복사를 거부한다 — 덮어쓰면 손으로 고친 게 사라진다")
    void copyRefusesNonEmptyMonth() throws Exception {
        createRoutine(7, "7월 루틴", 50);
        createRoutine(8, "8월에 이미 있는 루틴", 50);

        mvc.perform(post("/api/v1/admin/routines/copy-from-previous-month")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"month":8}""".formatted(academyId, YEAR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROUTINE_TARGET_MONTH_NOT_EMPTY"));
    }

    @Test
    @DisplayName("전월에 복사할 게 없으면 404다")
    void copyRefusesEmptySource() throws Exception {
        mvc.perform(post("/api/v1/admin/routines/copy-from-previous-month")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"month":8}""".formatted(academyId, YEAR)))
                .andExpect(status().isNotFound());
    }

    // ── 그리드 · 결과 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 아직 입력 안 한 학생도 빈 줄로 나온다 — 안 그러면 그리드가 텅 비어 시작을 못 한다")
    void gridShowsEmptyRows() throws Exception {
        long routineId = createRoutine(8, "국어", 100);

        mvc.perform(get("/api/v1/admin/routines/{id}/results", routineId)
                        .header("Authorization", adminToken())
                        .param("date", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].studentName").value("루틴학생"))
                // 저장된 적 없는 행이라 id가 없다
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[0].status").value("PLANNED"));
    }

    @Test
    @DisplayName("★ 가채점과 검수 점수가 따로 남는다 — 덮어쓰면 학생이 몇 점이라 했는지 사라진다")
    void selfScoreAndReviewedScoreAreSeparate() throws Exception {
        long routineId = createRoutine(8, "국어", 100);

        // 학생 가채점 80점으로 제출
        saveResult(routineId, RoutineResultStatus.SUBMITTED, 80, null);
        assertThat(result(routineId).getSelfScore()).isEqualTo((short) 80);

        // 교사 검수 결과 75점
        saveResult(routineId, RoutineResultStatus.REVIEWED, null, 75);

        DailyRoutineResult saved = result(routineId);
        // 가채점이 그대로 남아 있어야 대조가 된다
        assertThat(saved.getSelfScore()).isEqualTo((short) 80);
        assertThat(saved.getReviewedScore()).isEqualTo((short) 75);
        assertThat(saved.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("만점을 넘는 점수는 거부한다 — 통과시키면 통계가 조용히 틀어진다")
    void scoreOverMaxRejected() throws Exception {
        long routineId = createRoutine(8, "국어", 100);

        mvc.perform(put("/api/v1/admin/routines/{id}/results", routineId)
                        .header("Authorization", adminToken())
                        .param("date", DAY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"results":[{"enrollmentId":%d,"status":"REVIEWED","reviewedScore":120}]}"""
                                .formatted(enrollmentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ROUTINE_SCORE_OUT_OF_RANGE"));
    }

    // ── 앱 노출 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 검수만 된 점수는 학생에게 안 보인다 — 반 전체를 채점한 뒤 한 번에 연다")
    void reviewedScoreHiddenUntilPublished() throws Exception {
        long routineId = createRoutine(8, "국어", 100);
        saveResult(routineId, RoutineResultStatus.REVIEWED, 80, 75);

        mvc.perform(get("/api/v1/app/routines/today")
                        .header("Authorization", appToken())
                        .param("date", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("REVIEWED"))
                // 점수는 아직 가려져 있다
                .andExpect(jsonPath("$.data[0].score").doesNotExist());
    }

    @Test
    @DisplayName("일괄 공개하면 학생에게 점수가 보인다")
    void publishRevealsScore() throws Exception {
        long routineId = createRoutine(8, "국어", 100);
        saveResult(routineId, RoutineResultStatus.REVIEWED, 80, 75);

        mvc.perform(post("/api/v1/admin/routines/{id}/results/publish", routineId)
                        .header("Authorization", adminToken())
                        .param("date", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(1));
        em.flush();

        mvc.perform(get("/api/v1/app/routines/today")
                        .header("Authorization", appToken())
                        .param("date", DAY.toString()))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[0].score").value(75));
    }

    @Test
    @DisplayName("검수 안 된 것은 일괄 공개 대상이 아니다")
    void publishOnlyReviewed() throws Exception {
        long routineId = createRoutine(8, "국어", 100);
        saveResult(routineId, RoutineResultStatus.SUBMITTED, 80, null);

        mvc.perform(post("/api/v1/admin/routines/{id}/results/publish", routineId)
                        .header("Authorization", adminToken())
                        .param("date", DAY.toString()))
                .andExpect(jsonPath("$.data.published").value(0));
    }

    @Test
    @DisplayName("결과가 없어도 오늘의 루틴에는 나온다 — '아직 안 한 항목'이다")
    void routineAppearsWithoutResult() throws Exception {
        createRoutine(8, "국어", 100);

        mvc.perform(get("/api/v1/app/routines/today")
                        .header("Authorization", appToken())
                        .param("date", DAY.toString()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PLANNED"))
                .andExpect(jsonPath("$.data[0].recommended").value(true));
    }

    @Test
    @DisplayName("다른 달 루틴은 오늘의 루틴에 안 나온다")
    void otherMonthRoutineExcluded() throws Exception {
        createRoutine(7, "7월 루틴", 100);

        mvc.perform(get("/api/v1/app/routines/today")
                        .header("Authorization", appToken())
                        .param("date", DAY.toString()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("★ 앱에는 채점 API가 없다 — 오프라인 시험지 기반이라 입력은 교사 웹에서만")
    void appHasNoScoringEndpoint() throws Exception {
        long routineId = createRoutine(8, "국어", 100);

        mvc.perform(put("/api/v1/app/routines/{id}/results", routineId)
                        .header("Authorization", appToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
