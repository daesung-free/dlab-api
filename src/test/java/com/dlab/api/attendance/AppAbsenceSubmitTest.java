package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 앱 사유 제출 (P1-08 · 시연 S-6).
 *
 * <p>지키려는 것 — <b>제출은 학생만</b>(학부모는 승인자다), <b>같은 날 같은 유형은 한 번</b>,
 * <b>취소는 승인 전까지만</b>, <b>반려당하면 다시 낼 수 있을 것</b>.
 */
@SpringBootTest
@Transactional
class AppAbsenceSubmitTest {

    private static final String PASSWORD = "absence-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApprovalService approvalService;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    StudentEnrollment enrollment;
    Account guardianAccount;
    LocalDate today;
    short year;

    String studentPhone = "010-5000-0001";
    String parentPhone = "010-5000-0002";

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);
        year = (short) today.getYear();

        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("AB01", "사유제출지점", LocalTime.of(9, 0));
        em.persist(academy);

        // 승인 정책이 없으면 제출 자체가 안 된다 — 라우팅이 여기서 나온다
        em.persist(new ApprovalItem(academy, year,
                RequestType.ABSENCE_REASON, ApproverType.PARENT, null, null));

        Student student = new Student("ABSTU001", "김민지", studentPhone);
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, year, "2026-0001", null,
                GradeType.HIGH3);
        em.persist(enrollment);

        Account studentAccount = Account.forStudent(student, studentPhone,
                passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);

        ParentGuardian guardian = new ParentGuardian("김보호", parentPhone, null);
        em.persist(guardian);
        em.persist(new StudentGuardianLink(student, guardian, (short) 1, true));
        guardianAccount = Account.forGuardian(guardian, parentPhone,
                passwordEncoder.encode(PASSWORD));
        em.persist(guardianAccount);

        em.flush();
    }

    // ── 픽스처 ───────────────────────────────────────────

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private ResultActions submit(String token, LocalDate date, String type, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/app/attendance/absence-reasons")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(date, type)));
    }

    private ResultActions submitAbsence(String token, LocalDate date) throws Exception {
        return submit(token, date, "ABSENCE", """
                {"date":"%s","type":"%s","reasonText":"병원 진료"}""");
    }

    private Long submittedId(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private ApprovalRequest approvalOf(Long reasonId) {
        return em.find(AbsenceReason.class, reasonId).getApprovalRequest();
    }

    // ── 제출 ────────────────────────────────────────────

    @Test
    @DisplayName("학생이 제출하면 승인 대기로 라우팅된다 — 관리자 등록과 같은 엔진이다")
    void studentSubmitGoesThroughApproval() throws Exception {
        submitAbsence(token(studentPhone), today)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasonType").value("ABSENCE"))
                .andExpect(jsonPath("$.data.period").value("종일"))
                .andExpect(jsonPath("$.data.approvalStatus").value("PENDING"));
    }

    @Test
    @DisplayName("★ 학부모는 사유를 못 낸다 — 학부모가 이 신청의 승인자다")
    void guardianCannotSubmit() throws Exception {
        submitAbsence(token(parentPhone), today).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 외출은 시작·종료가 모두 있어야 한다 — 없으면 복귀 태깅과 대조할 수 없다")
    void outingNeedsBothTimes() throws Exception {
        submit(token(studentPhone), today, "OUTING", """
                {"date":"%s","type":"%s","reasonText":"병원","startTime":"13:00"}""")
                .andExpect(status().isBadRequest());

        submit(token(studentPhone), today, "OUTING", """
                {"date":"%s","type":"%s","reasonText":"병원",\
                "startTime":"13:00","endTime":"15:00"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value("13:00 ~ 15:00"));
    }

    @Test
    @DisplayName("★ 같은 날 같은 유형은 두 번 못 낸다")
    void sameDaySameTypeIsRejected() throws Exception {
        String token = token(studentPhone);
        submitAbsence(token, today).andExpect(status().isOk());

        submitAbsence(token, today).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("사후 제출도 받는다 — 아파서 결석한 다음 날 내는 게 가장 흔한 흐름이다")
    void pastDateIsAccepted() throws Exception {
        submitAbsence(token(studentPhone), today.minusDays(2))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            ★ 대기 중인 신청이 있으면 다른 날짜도 못 낸다 — 승인 엔진이 유형당 1건만 \
            대기시킨다(현재 동작 고정. 실무상 2건 이상이 필요하면 엔진 쪽을 바꿔야 한다)""")
    void onlyOnePendingRequestAtATime() throws Exception {
        String token = token(studentPhone);
        submitAbsence(token, today).andExpect(status().isOk());

        // 날짜가 달라도 막힌다. 사유 중복이 아니라 승인 요청 유니크 제약 때문이다
        submitAbsence(token, today.plusDays(3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("처리 대기중")));
    }

    // ── 취소 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 대기 중이면 취소된다 — 승인 요청도 함께 취소 상태가 된다")
    void pendingCanBeCanceled() throws Exception {
        String token = token(studentPhone);
        Long reasonId = submittedId(submitAbsence(token, today).andExpect(status().isOk()));
        Long approvalId = approvalOf(reasonId).getId();

        mvc.perform(delete("/api/v1/app/attendance/absence-reasons/{id}", reasonId)
                        .header("Authorization", token))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        assertThat(em.find(ApprovalRequest.class, approvalId).getStatus())
                .isEqualTo(ApprovalStatus.CANCELED);
        // 취소한 건은 조회에서도 빠진다
        mvc.perform(get("/api/v1/app/attendance/absence-reasons")
                        .header("Authorization", token)
                        .param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("★ 승인된 신청은 취소할 수 없다 — 승인받고 없던 일로 만드는 경로가 열린다")
    void approvedCannotBeCanceled() throws Exception {
        String token = token(studentPhone);
        Long reasonId = submittedId(submitAbsence(token, today).andExpect(status().isOk()));
        em.flush();

        approvalService.approve(approvalOf(reasonId).getId(), guardianAccount.getId());
        em.flush();
        em.clear();

        mvc.perform(delete("/api/v1/app/attendance/absence-reasons/{id}", reasonId)
                        .header("Authorization", token))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("취소한 뒤에는 같은 날 다시 낼 수 있다")
    void canResubmitAfterCancel() throws Exception {
        String token = token(studentPhone);
        Long reasonId = submittedId(submitAbsence(token, today).andExpect(status().isOk()));

        mvc.perform(delete("/api/v1/app/attendance/absence-reasons/{id}", reasonId)
                .header("Authorization", token)).andExpect(status().isOk());
        em.flush();
        em.clear();

        submitAbsence(token, today).andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 반려당하면 사유를 고쳐 다시 낼 수 있다 — 막으면 그 날짜는 영영 신청 불가다")
    void canResubmitAfterReject() throws Exception {
        String token = token(studentPhone);
        Long reasonId = submittedId(submitAbsence(token, today).andExpect(status().isOk()));
        em.flush();

        approvalService.reject(approvalOf(reasonId).getId(), guardianAccount.getId(), "사유 불충분");
        em.flush();
        em.clear();

        submitAbsence(token, today).andExpect(status().isOk());
    }

    @Test
    @DisplayName("남의 신청은 취소할 수 없다")
    void cannotCancelOthers() throws Exception {
        Student other = new Student("ABSTU002", "박서준", "010-5000-0009");
        em.persist(other);
        StudentEnrollment otherEnrollment = new StudentEnrollment(other, academy, year,
                "2026-0002", null, GradeType.HIGH3);
        em.persist(otherEnrollment);
        AbsenceReason theirs = new AbsenceReason(academy, otherEnrollment, today,
                com.dlab.domain.attendance.entity.AbsenceReasonType.ABSENCE, "병원");
        em.persist(theirs);
        em.flush();

        mvc.perform(delete("/api/v1/app/attendance/absence-reasons/{id}", theirs.getId())
                        .header("Authorization", token(studentPhone)))
                .andExpect(status().isForbidden());
    }
}
