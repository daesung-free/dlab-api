package com.dlab.api.home;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.entity.NoticeAuthorType;
import com.dlab.domain.routine.entity.DailyRoutine;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
 * 앱 홈 · 마이페이지 (A-3).
 *
 * <p>고정해두는 것 — <b>확정 전인 오늘은 순공에 안 들어간다</b>,
 * <b>출석률 분모도 확정된 날만</b>, <b>학생 고유ID는 마이페이지에만</b>,
 * <b>학부모는 자녀를 지정해야 하고 남의 자녀는 막힌다</b>.
 *
 * <p>시각이 곧 로직이라 {@code Clock}을 고정한다 — 안 그러면 월초·주초에 결과가 달라진다.
 */
@SpringBootTest
@Transactional
class AppHomeFlowTest {

    private static final String PASSWORD = "home-password-1234";
    private static final short YEAR = 2026;
    /** 목요일. 이번 주 월요일은 8/17, 이번 달 1일은 8/1이다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean Clock clock;
    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    ClassMaster class1;
    Student child;
    StudentEnrollment enrollment;
    String studentPhone = "010-4000-0001";
    String parentPhone = "010-4000-0002";
    Long otherStudentId;

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(
                TODAY.atTime(10, 0).atZone(TimeConfig.KST).toInstant());
        given(clock.getZone()).willReturn(TimeConfig.KST);

        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("HM01", "홈테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        class1 = new ClassMaster(academy, YEAR, "1반", ClassType.FIXED, null);
        em.persist(class1);

        child = new Student("HMSTU001", "내자녀", studentPhone);
        em.persist(child);
        enrollment = new StudentEnrollment(child, academy, YEAR, "2026-0001", null,
                GradeType.N_SU);
        em.persist(enrollment);
        em.persist(new ClassAssignment(academy, enrollment, class1, ClassType.FIXED));

        Account studentAccount = Account.forStudent(child, studentPhone,
                passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);

        // 남의 자녀 — 학부모가 이 ID를 넣어도 막혀야 한다
        Student otherChild = new Student("HMSTU002", "남의자녀", "010-4000-0009");
        em.persist(otherChild);
        em.persist(new StudentEnrollment(otherChild, academy, YEAR, "2026-0002", null,
                GradeType.N_SU));
        otherStudentId = otherChild.getId();

        ParentGuardian guardian = new ParentGuardian("내학부모", parentPhone, null);
        em.persist(guardian);
        em.persist(new StudentGuardianLink(child, guardian, (short) 1, true));
        em.persist(Account.forGuardian(guardian, parentPhone, passwordEncoder.encode(PASSWORD)));

        em.flush();
    }

    // ── 픽스처 ───────────────────────────────────────────

    private void confirm(LocalDate date, DailyStatus status, Integer studyMinutes) {
        AttendanceDailyStatus daily =
                new AttendanceDailyStatus(academy, enrollment, date, status);
        if (studyMinutes != null) {
            daily.recordStudyMinutes(studyMinutes, Instant.now(clock));
        }
        em.persist(daily);
    }

    private void tag(LocalDate date, AttendanceEventType type, int hour) {
        em.persist(new AttendanceTaggingLog(academy, enrollment, type,
                AttendanceSource.KIOSK_NFC,
                date.atTime(hour, 0).toInstant(ZoneOffset.UTC), date));
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

    private org.springframework.test.web.servlet.ResultActions home(String token, Long studentId)
            throws Exception {
        var request = get("/api/v1/app/home").header("Authorization", token);
        if (studentId != null) {
            request = request.param("studentId", String.valueOf(studentId));
        }
        return mvc.perform(request);
    }

    // ── 지표 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 순공시간은 주간·월간이 따로 나오고, 확정된 날만 더한다")
    void studyMinutesAreSummedFromConfirmedDaysOnly() throws Exception {
        confirm(LocalDate.of(2026, 8, 5), DailyStatus.PRESENT, 400);   // 이번 달, 지난 주
        confirm(LocalDate.of(2026, 8, 18), DailyStatus.PRESENT, 600);  // 이번 주
        confirm(LocalDate.of(2026, 8, 19), DailyStatus.LATE, 500);     // 이번 주
        // 오늘은 태깅만 있고 확정 전이다 — 순공이 아직 없다
        tag(TODAY, AttendanceEventType.CHECK_IN, 8);
        em.flush();

        home(token(studentPhone), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.weeklyStudyMinutes").value(1100))
                .andExpect(jsonPath("$.data.metrics.monthlyStudyMinutes").value(1500))
                .andExpect(jsonPath("$.data.metrics.confirmedDays").value(3));
    }

    @Test
    @DisplayName("★ 지각·조퇴는 출석으로 센다 — 결석만 결석이다")
    void lateCountsAsPresent() throws Exception {
        confirm(LocalDate.of(2026, 8, 17), DailyStatus.PRESENT, 600);
        confirm(LocalDate.of(2026, 8, 18), DailyStatus.LATE, 500);
        confirm(LocalDate.of(2026, 8, 19), DailyStatus.EARLY_LEAVE, 300);
        confirm(LocalDate.of(2026, 8, 5), DailyStatus.ABSENT, null);
        em.flush();

        home(token(studentPhone), null)
                .andExpect(status().isOk())
                // 4일 중 결석 1일 → 75%
                .andExpect(jsonPath("$.data.metrics.attendanceRate").value(75));
    }

    @Test
    @DisplayName("★ 확정된 날이 없으면 출석률은 0이 아니라 null — 0%면 결석한 것처럼 보인다")
    void attendanceRateIsNullWhenNothingConfirmed() throws Exception {
        tag(TODAY, AttendanceEventType.CHECK_IN, 8);
        em.flush();

        home(token(studentPhone), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.attendanceRate").doesNotExist())
                .andExpect(jsonPath("$.data.metrics.confirmedDays").value(0));
    }

    // ── 프로필·배너·루틴 ──────────────────────────────────

    @Test
    @DisplayName("프로필에 이름·학번·지점·반이 함께 내려온다")
    void profileIsFilled() throws Exception {
        home(token(studentPhone), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.studentName").value("내자녀"))
                .andExpect(jsonPath("$.data.profile.studentNo").value("2026-0001"))
                .andExpect(jsonPath("$.data.profile.academyName").value("홈테스트지점"))
                .andExpect(jsonPath("$.data.profile.className").value("1반"));
    }

    @Test
    @DisplayName("★ 홈 프로필에는 학생 고유ID가 없다 — 노출 지점은 마이페이지 하나다")
    void homeDoesNotExposeUniqueCode() throws Exception {
        home(token(studentPhone), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.studentUniqueCode").doesNotExist());
    }

    @Test
    @DisplayName("배너는 내게 보이는 공지 중 배너 표시분만 내려온다")
    void bannersComeFromNotice() throws Exception {
        Notice banner = Notice.ofBranch(academy, YEAR, "배너 공지", "본문",
                NoticeAuthorType.EMPLOYEE, 1L);
        banner.markBanner(true);
        em.persist(banner);
        em.persist(Notice.ofBranch(academy, YEAR, "일반 공지", "본문",
                NoticeAuthorType.EMPLOYEE, 1L));
        em.flush();

        home(token(studentPhone), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.banners.length()").value(1))
                .andExpect(jsonPath("$.data.banners[0].title").value("배너 공지"));
    }

    @Test
    @DisplayName("오늘의 데일리 루틴이 함께 내려온다 — 결과 전이면 점수는 비어 있다")
    void todayRoutineIsIncluded() throws Exception {
        em.persist(new DailyRoutine(academy, YEAR, (short) 8, class1,
                "수학 데일리테스트", "수학", (short) 20, true, (short) 1));
        em.flush();

        home(token(studentPhone), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayRoutines.length()").value(1))
                .andExpect(jsonPath("$.data.todayRoutines[0].name").value("수학 데일리테스트"))
                .andExpect(jsonPath("$.data.todayRoutines[0].score").doesNotExist());
    }

    // ── 학부모 ───────────────────────────────────────────

    @Test
    @DisplayName("★ 학부모는 자녀를 지정해야 한다 — 계정 하나에 자녀가 여럿이다")
    void guardianMustSpecifyChild() throws Exception {
        home(token(parentPhone), null).andExpect(status().isBadRequest());

        home(token(parentPhone), child.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.studentName").value("내자녀"));
    }

    @Test
    @DisplayName("★ 남의 자녀 홈은 못 본다")
    void guardianCannotSeeOtherChild() throws Exception {
        home(token(parentPhone), otherStudentId).andExpect(status().isForbidden());
    }

    // ── 마이페이지 ────────────────────────────────────────

    @Test
    @DisplayName("★ 학생 마이페이지에 고유ID가 노출된다 — 학부모 연결의 유일한 수단이다")
    void myPageExposesUniqueCodeToStudent() throws Exception {
        mvc.perform(get("/api/v1/app/me").header("Authorization", token(studentPhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountType").value("STUDENT"))
                .andExpect(jsonPath("$.data.studentUniqueCode").value("HMSTU001"))
                .andExpect(jsonPath("$.data.studentNo").value("2026-0001"))
                .andExpect(jsonPath("$.data.className").value("1반"));
    }

    @Test
    @DisplayName("★ 학부모 마이페이지에는 학생 고유ID가 없다 — 본인 정보와 자녀 수만 본다")
    void myPageForGuardianHasNoUniqueCode() throws Exception {
        mvc.perform(get("/api/v1/app/me").header("Authorization", token(parentPhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountType").value("PARENT"))
                .andExpect(jsonPath("$.data.name").value("내학부모"))
                .andExpect(jsonPath("$.data.childCount").value(1))
                .andExpect(jsonPath("$.data.studentUniqueCode").doesNotExist());
    }
}
