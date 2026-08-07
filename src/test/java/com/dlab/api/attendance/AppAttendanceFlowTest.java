package com.dlab.api.attendance;

import com.dlab.domain.attendance.entity.*;
import com.dlab.domain.penalty.entity.*;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 앱 출결·상벌점 조회 (A-18).
 *
 * <p>지키려는 것 — <b>남의 자녀를 못 볼 것</b>, <b>확정 전을 결석으로 보여주지 않을 것</b>,
 * <b>퇴실이 마지막 하원일 것</b>, <b>벌점 부호가 유지될 것</b>.
 */
@SpringBootTest
@Transactional
class AppAttendanceFlowTest {

    private static final String PASSWORD = "attendance-password-1234";
    private static final short YEAR = 2026;
    private static final LocalDate DAY = LocalDate.of(2026, 8, 3);

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Student myChild;
    Student otherChild;
    StudentEnrollment myEnrollment;
    String studentPhone = "010-3000-0001";
    String parentPhone = "010-3000-0002";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("AT01", "출결조회테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        myChild = new Student("ATSTU001", "내자녀", studentPhone);
        em.persist(myChild);
        myEnrollment = new StudentEnrollment(myChild, academy, YEAR, "2026-0001", null, GradeType.N_SU);
        em.persist(myEnrollment);
        Account studentAccount = Account.forStudent(myChild, studentPhone,
                passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);

        // 다른 집 자녀 — 학부모가 이 ID를 넣어도 막혀야 한다
        otherChild = new Student("ATSTU002", "남의자녀", "010-3000-0009");
        em.persist(otherChild);
        em.persist(new StudentEnrollment(otherChild, academy, YEAR, "2026-0002", null, GradeType.N_SU));

        ParentGuardian guardian = new ParentGuardian("내학부모", parentPhone, null);
        em.persist(guardian);
        em.persist(new StudentGuardianLink(myChild, guardian, (short) 1, true));
        em.persist(Account.forGuardian(guardian, parentPhone, passwordEncoder.encode(PASSWORD)));

        em.flush();
    }

    private void tag(AttendanceEventType type, int hour, int minute) {
        AttendanceTaggingLog log = new AttendanceTaggingLog(academy, myEnrollment, type,
                AttendanceSource.KIOSK_NFC,
                DAY.atTime(hour, minute).toInstant(java.time.ZoneOffset.UTC), DAY);
        em.persist(log);
    }

    private void confirmDay(DailyStatus status, boolean excused, Integer studyMinutes) {
        AttendanceDailyStatus daily = new AttendanceDailyStatus(academy, myEnrollment, DAY, status);
        if (excused) {
            daily.reconfirm(status, true, Instant.now());
        }
        if (studyMinutes != null) {
            daily.recordStudyMinutes(studyMinutes, Instant.now());
        }
        em.persist(daily);
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

    private org.springframework.test.web.servlet.ResultActions getDaily(String token, Long studentId)
            throws Exception {
        var request = get("/api/v1/app/attendance")
                .header("Authorization", token)
                .param("from", DAY.minusDays(3).toString())
                .param("to", DAY.plusDays(3).toString());
        if (studentId != null) {
            request = request.param("studentId", String.valueOf(studentId));
        }
        return mvc.perform(request);
    }

    // ── 학생 본인 ────────────────────────────────────────────────

    @Test
    @DisplayName("학생은 studentId 없이 본인 출결을 본다")
    void studentSeesOwn() throws Exception {
        tag(AttendanceEventType.CHECK_IN, 8, 50);
        tag(AttendanceEventType.CHECK_OUT, 22, 10);
        confirmDay(DailyStatus.PRESENT, false, 600);
        em.flush();

        getDaily(token(studentPhone), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].finalStatus").value("PRESENT"))
                .andExpect(jsonPath("$.data[0].studyMinutes").value(600))
                .andExpect(jsonPath("$.data[0].events.length()").value(2));
    }

    @Test
    @DisplayName("★ 퇴실은 마지막 하원이다 — 외출·복귀가 섞여 있어 첫 건을 쓰면 안 된다")
    void lastOutIsFinalCheckOut() throws Exception {
        tag(AttendanceEventType.CHECK_IN, 8, 50);
        tag(AttendanceEventType.OUTING, 12, 0);
        tag(AttendanceEventType.RETURN, 13, 0);
        tag(AttendanceEventType.CHECK_OUT, 18, 0);   // 조기 하원 후
        tag(AttendanceEventType.CHECK_IN, 19, 0);    // 재등원
        tag(AttendanceEventType.CHECK_OUT, 22, 10);  // 실제 퇴실
        em.flush();

        getDaily(token(studentPhone), null)
                .andExpect(jsonPath("$.data[0].inAt").value(org.hamcrest.Matchers.startsWith("2026-08-03T08:50")))
                .andExpect(jsonPath("$.data[0].outAt").value(org.hamcrest.Matchers.startsWith("2026-08-03T22:10")));
    }

    @Test
    @DisplayName("★ 확정 전에는 finalStatus가 비어 있다 — 당일을 결석으로 보여주면 안 된다")
    void unconfirmedDayHasNullStatus() throws Exception {
        tag(AttendanceEventType.CHECK_IN, 8, 50);
        em.flush();

        getDaily(token(studentPhone), null)
                .andExpect(jsonPath("$.data.length()").value(1))
                // ABSENT가 아니라 아예 없어야 한다
                .andExpect(jsonPath("$.data[0].finalStatus").doesNotExist())
                .andExpect(jsonPath("$.data[0].events.length()").value(1));
    }

    @Test
    @DisplayName("★ 태깅이 없어도 확정 행이 있으면 그날이 나온다 — 결석이 사라지면 안 된다")
    void confirmedAbsenceAppearsWithoutTags() throws Exception {
        confirmDay(DailyStatus.ABSENT, false, null);
        em.flush();

        getDaily(token(studentPhone), null)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].finalStatus").value("ABSENT"))
                .andExpect(jsonPath("$.data[0].events").isEmpty());
    }

    @Test
    @DisplayName("사유가 승인된 결석은 excused로 구분된다 — 무단과 같이 보이면 안 된다")
    void excusedAbsenceIsMarked() throws Exception {
        confirmDay(DailyStatus.ABSENT, true, null);
        em.flush();

        getDaily(token(studentPhone), null)
                .andExpect(jsonPath("$.data[0].finalStatus").value("ABSENT"))
                .andExpect(jsonPath("$.data[0].excused").value(true));
    }

    // ── 학부모 ───────────────────────────────────────────────────

    @Test
    @DisplayName("학부모는 자녀를 지정해서 본다")
    void parentSeesOwnChild() throws Exception {
        tag(AttendanceEventType.CHECK_IN, 8, 50);
        confirmDay(DailyStatus.PRESENT, false, 500);
        em.flush();

        getDaily(token(parentPhone), myChild.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].finalStatus").value("PRESENT"));
    }

    @Test
    @DisplayName("★ 남의 자녀 ID를 넣으면 막힌다 — 이걸 빠뜨리면 ID만 바꿔서 남의 출결을 본다")
    void parentCannotSeeOthersChild() throws Exception {
        getDaily(token(parentPhone), otherChild.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MY_CHILD"));
    }

    @Test
    @DisplayName("학부모가 자녀를 안 지정하면 누구 것인지 알 수 없다 — 400으로 안내한다")
    void parentMustSpecifyChild() throws Exception {
        getDaily(token(parentPhone), null)
                .andExpect(status().isBadRequest());
    }

    // ── 상벌점 ───────────────────────────────────────────────────

    @Test
    @DisplayName("★ 벌점은 음수로 내려간다 — 절댓값으로 바꾸면 상쇄가 사라진다")
    void penaltySignIsPreserved() throws Exception {
        PenaltyItem merit = new PenaltyItem(academy, YEAR, "모범학생", 5, PenaltyCategory.MERIT);
        PenaltyItem demerit = new PenaltyItem(academy, YEAR, "지각", -3, PenaltyCategory.DEMERIT);
        em.persist(merit);
        em.persist(demerit);
        em.persist(new PenaltyPoint(academy, myEnrollment, merit, 5, "수업태도",
                PenaltySource.MANUAL, null));
        em.persist(new PenaltyPoint(academy, myEnrollment, demerit, -3, "지각",
                PenaltySource.MANUAL, null));
        em.flush();

        mvc.perform(get("/api/v1/app/attendance/penalties")
                        .header("Authorization", token(studentPhone)))
                .andExpect(status().isOk())
                // 5 + (-3) = 2. 절댓값이면 8이 된다
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("상벌점이 없으면 0이다 — null이 아니라")
    void emptyPenaltyIsZero() throws Exception {
        mvc.perform(get("/api/v1/app/attendance/penalties")
                        .header("Authorization", token(studentPhone)))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("★ 학부모도 남의 자녀 상벌점은 못 본다")
    void parentCannotSeeOthersPenalties() throws Exception {
        mvc.perform(get("/api/v1/app/attendance/penalties")
                        .header("Authorization", token(parentPhone))
                        .param("studentId", String.valueOf(otherChild.getId())))
                .andExpect(status().isForbidden());
    }

    // ── 사유출결 ─────────────────────────────────────────────────

    @Test
    @DisplayName("사유출결이 기간으로 조회된다")
    void absenceReasons() throws Exception {
        em.persist(new AbsenceReason(academy, myEnrollment, DAY,
                AbsenceReasonType.EARLY_LEAVE, "병원 진료"));
        em.flush();

        mvc.perform(get("/api/v1/app/attendance/absence-reasons")
                        .header("Authorization", token(studentPhone))
                        .param("from", DAY.minusDays(1).toString())
                        .param("to", DAY.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reasonType").value("EARLY_LEAVE"))
                .andExpect(jsonPath("$.data[0].reasonText").value("병원 진료"));
    }

    // ── 기간 검증 ────────────────────────────────────────────────

    @Test
    @DisplayName("★ 조회 기간이 1년을 넘으면 거부한다 — 원장 전체를 훑게 된다")
    void rangeIsCapped() throws Exception {
        mvc.perform(get("/api/v1/app/attendance")
                        .header("Authorization", token(studentPhone))
                        .param("from", "2020-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("시작일이 종료일보다 뒤면 거부한다")
    void reversedRangeRejected() throws Exception {
        mvc.perform(get("/api/v1/app/attendance")
                        .header("Authorization", token(studentPhone))
                        .param("from", DAY.toString())
                        .param("to", DAY.minusDays(5).toString()))
                .andExpect(status().isBadRequest());
    }
}
