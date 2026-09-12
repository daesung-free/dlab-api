package com.dlab.api.lecture;

import com.dlab.domain.lecture.entity.ApplicationStatus;
import com.dlab.domain.lecture.entity.LectureApplication;
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

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 특강 (F-4.10-4 · F-4.7 · A-15).
 *
 * <p>지키려는 것 — <b>노출과 상태가 별개 축일 것</b>, <b>정원이 차면 대기로 들어갈 것</b>,
 * <b>확정자가 빠지면 대기 1번이 올라갈 것</b>, <b>출석부에 대기·취소자가 없을 것</b>.
 */
@SpringBootTest
@Transactional
class LectureFlowTest {

    private static final String PASSWORD = "lecture-password-1234";
    private static final short YEAR = 2026;

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    String studentA = "010-1000-0001";
    String studentB = "010-1000-0002";
    String studentC = "010-1000-0003";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("LC01", "특강테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        academyId = academy.getId();

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "LCADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId()).executeUpdate();

        createStudent(academy, "LCSTU001", "학생A", studentA);
        createStudent(academy, "LCSTU002", "학생B", studentB);
        createStudent(academy, "LCSTU003", "학생C", studentC);
        em.flush();
    }

    private void createStudent(Academy academy, String code, String name, String phone) {
        Student student = new Student(code, name, phone);
        em.persist(student);
        em.persist(new StudentEnrollment(student, academy, YEAR,
                "2026-" + code.substring(code.length() - 4), null, GradeType.N_SU));
        Account account = Account.forStudent(student, phone, passwordEncoder.encode(PASSWORD));
        account.approve();
        em.persist(account);
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
        return token("LCADM", "/api/v1/admin/auth/login");
    }

    private String appToken(String phone) throws Exception {
        return token(phone, "/api/v1/app/auth/login");
    }

    @Test
    @DisplayName("★ 아무도 신청하지 않은 특강만 지운다 — 신청 이력을 지우지 않는다")
    void deleteOnlyWhenNobodyApplied() throws Exception {
        String token = adminToken();

        // 만들어만 두고 아무도 신청하지 않은 것 — 지워진다
        long empty = openLecture(null);
        mvc.perform(delete("/api/v1/admin/lectures/{id}", empty)
                        .header("Authorization", token))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/lectures")
                        .header("Authorization", token)
                        .param("academyId", String.valueOf(academyId))
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d)]".formatted(empty)).isEmpty());

        // 신청자가 있으면 막힌다 — 지우면 그 학생의 신청 이력이 사라진다
        long applied = openLecture(5);
        apply(studentA, applied);
        em.flush();

        mvc.perform(delete("/api/v1/admin/lectures/{id}", applied)
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 출결이 찍힌 회차는 못 지운다 — 그날 누가 왔는지가 사라진다")
    void cannotDeleteSessionWithAttendance() throws Exception {
        String token = adminToken();
        long lectureId = openLecture(5);

        String body = mvc.perform(post("/api/v1/admin/lectures/{id}/sessions", lectureId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionDate":"2026-10-01","startTime":"19:00","endTime":"21:00"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(body).path("data").path("id").asLong();
        em.flush();

        // 출결 전에는 지워진다 — 날짜를 잘못 넣었을 때 되돌릴 수단이다
        mvc.perform(delete("/api/v1/admin/lectures/sessions/{id}", sessionId)
                        .header("Authorization", token))
                .andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/lectures/{id}/sessions", lectureId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d)]".formatted(sessionId)).isEmpty());
    }

    @Test
    @DisplayName("특강 유형은 관리자가 추가하고, 등록할 때 붙였다 나중에 바꿀 수 있다")
    void lectureCategory() throws Exception {
        String token = adminToken();

        // 유형이 고정값이 아니라 행이라는 것이 이 기능의 요지다
        long 단과 = createCategory(token, "단과");
        long 실전 = createCategory(token, "실전");

        // 등록 한 번으로 유형까지 붙는다 — 두 번에 나뉘면 두 번째가 실패했을 때
        // 유형 없는 특강이 남는다
        String body = mvc.perform(post("/api/v1/admin/lectures")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"name":"유형특강","categoryId":%d}"""
                                .formatted(academyId, YEAR, 단과)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(단과))
                .andExpect(jsonPath("$.data.categoryName").value("단과"))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("data").path("id").asLong();
        em.flush();

        mvc.perform(patch("/api/v1/admin/lectures/{id}", id)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d}""".formatted(실전)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryName").value("실전"));
    }

    @Test
    @DisplayName("유형 없이도 특강을 연다 — 마스터가 비어 있다고 개설이 막히면 안 된다")
    void lectureWithoutCategory() throws Exception {
        mvc.perform(post("/api/v1/admin/lectures")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"name":"유형없는특강"}"""
                                .formatted(academyId, YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").doesNotExist());
    }

    private long createCategory(String token, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/lecture-categories")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"name":"%s"}"""
                                .formatted(academyId, YEAR, name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    /** 특강 생성 → 정원 설정 → OPEN → 노출. 신청 가능한 상태로 만든다. */
    private long openLecture(Integer capacity) throws Exception {
        String token = adminToken();
        String body = mvc.perform(post("/api/v1/admin/lectures")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"name":"수능특강"}"""
                                .formatted(academyId, YEAR)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("data").path("id").asLong();
        em.flush();

        if (capacity != null) {
            mvc.perform(patch("/api/v1/admin/lectures/{id}", id)
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"capacity":%d}""".formatted(capacity))).andExpect(status().isOk());
        }
        mvc.perform(put("/api/v1/admin/lectures/{id}/status", id)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"OPEN"}""")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/lectures/{id}/visible", id)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"visible":true}""")).andExpect(status().isOk());
        em.flush();
        return id;
    }

    private String apply(String phone, long lectureId) throws Exception {
        String body = mvc.perform(post("/api/v1/app/lectures/{id}/apply", lectureId)
                        .header("Authorization", appToken(phone)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return body;
    }

    private long applicationId(String json) {
        return objectMapper.readTree(json).path("data").path("applicationId").asLong();
    }

    private String applicationStatus(String json) {
        return objectMapper.readTree(json).path("data").path("status").asString();
    }

    // ── 노출 · 상태 ──────────────────────────────────────────────

    @Test
    @DisplayName("★ 노출과 상태는 별개 축이다 — OPEN으로 열어도 노출을 켜야 앱에 뜬다")
    void visibleIsSeparateFromStatus() throws Exception {
        String token = adminToken();
        String body = mvc.perform(post("/api/v1/admin/lectures")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"name":"숨긴특강"}"""
                                .formatted(academyId, YEAR)))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("data").path("id").asLong();

        mvc.perform(put("/api/v1/admin/lectures/{id}/status", id)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"OPEN"}""")).andExpect(status().isOk());
        em.flush();

        // OPEN이지만 visible=false라 앱 목록에 없다
        mvc.perform(get("/api/v1/app/lectures").header("Authorization", appToken(studentA)))
                .andExpect(jsonPath("$.data").isEmpty());

        mvc.perform(put("/api/v1/admin/lectures/{id}/visible", id)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"visible":true}""")).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/lectures").header("Authorization", appToken(studentA)))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("DRAFT 상태에는 신청할 수 없다 — 준비 중인 특강이다")
    void cannotApplyToDraft() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/lectures")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"name":"준비중"}"""
                                .formatted(academyId, YEAR)))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("data").path("id").asLong();
        em.flush();

        mvc.perform(post("/api/v1/app/lectures/{id}/apply", id)
                        .header("Authorization", appToken(studentA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LECTURE_NOT_ACCEPTING"));
    }

    // ── 신청 · 대기 ──────────────────────────────────────────────

    @Test
    @DisplayName("정원이 남으면 확정으로 들어간다")
    void applyWithinCapacity() throws Exception {
        long id = openLecture(2);
        assertThat(applicationStatus(apply(studentA, id))).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("★ 정원이 차면 대기로 들어간다 — 실패가 아니다")
    void applyBeyondCapacityBecomesWaitlisted() throws Exception {
        long id = openLecture(1);
        assertThat(applicationStatus(apply(studentA, id))).isEqualTo("APPLIED");
        assertThat(applicationStatus(apply(studentB, id))).isEqualTo("WAITLISTED");
    }

    @Test
    @DisplayName("정원이 없으면 무제한이다 — 설명회는 정원을 안 두는 경우가 있다")
    void noCapacityMeansUnlimited() throws Exception {
        long id = openLecture(null);
        assertThat(applicationStatus(apply(studentA, id))).isEqualTo("APPLIED");
        assertThat(applicationStatus(apply(studentB, id))).isEqualTo("APPLIED");
        assertThat(applicationStatus(apply(studentC, id))).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("같은 특강에 두 번 신청할 수 없다")
    void cannotApplyTwice() throws Exception {
        long id = openLecture(5);
        apply(studentA, id);

        mvc.perform(post("/api/v1/app/lectures/{id}/apply", id)
                        .header("Authorization", appToken(studentA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LECTURE_ALREADY_APPLIED"));
    }

    @Test
    @DisplayName("★ 취소했다면 다시 신청할 수 있다 — 유니크가 취소분까지 막으면 영영 못 넣는다")
    void canReapplyAfterCancel() throws Exception {
        long id = openLecture(5);
        long applicationId = applicationId(apply(studentA, id));

        mvc.perform(delete("/api/v1/app/lectures/applications/{id}", applicationId)
                .header("Authorization", appToken(studentA))).andExpect(status().isOk());
        em.flush();

        assertThat(applicationStatus(apply(studentA, id))).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("★ 확정자가 취소하면 대기 1번이 자동 승격된다 — 수동이면 자리가 빈 채로 남는다")
    void cancelPromotesFirstWaiting() throws Exception {
        long id = openLecture(1);
        long first = applicationId(apply(studentA, id));
        long second = applicationId(apply(studentB, id));
        long third = applicationId(apply(studentC, id));

        mvc.perform(delete("/api/v1/app/lectures/applications/{id}", first)
                .header("Authorization", appToken(studentA))).andExpect(status().isOk());
        em.flush();
        em.clear();

        // 가장 오래 기다린 B가 올라가고 C는 그대로 대기
        assertThat(em.find(LectureApplication.class, second).getStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
        assertThat(em.find(LectureApplication.class, third).getStatus())
                .isEqualTo(ApplicationStatus.WAITLISTED);
    }

    @Test
    @DisplayName("대기자가 취소하면 아무도 승격되지 않는다 — 자리가 난 게 아니다")
    void cancelingWaitlistedPromotesNobody() throws Exception {
        long id = openLecture(1);
        apply(studentA, id);
        long second = applicationId(apply(studentB, id));
        long third = applicationId(apply(studentC, id));

        mvc.perform(delete("/api/v1/app/lectures/applications/{id}", second)
                .header("Authorization", appToken(studentB))).andExpect(status().isOk());
        em.flush();
        em.clear();

        assertThat(em.find(LectureApplication.class, third).getStatus())
                .isEqualTo(ApplicationStatus.WAITLISTED);
    }

    @Test
    @DisplayName("★ 남의 신청은 취소할 수 없다")
    void cannotCancelOthers() throws Exception {
        long id = openLecture(5);
        long applicationId = applicationId(apply(studentA, id));

        mvc.perform(delete("/api/v1/app/lectures/applications/{id}", applicationId)
                        .header("Authorization", appToken(studentB)))
                .andExpect(status().isForbidden());
    }

    // ── 관리자 명단 · 정원 ───────────────────────────────────────

    @Test
    @DisplayName("명단은 확정 → 대기 순으로 나오고 대기 순번은 신청 시각 순이다")
    void rosterOrder() throws Exception {
        long id = openLecture(1);
        apply(studentA, id);
        apply(studentB, id);
        apply(studentC, id);

        mvc.perform(get("/api/v1/admin/lectures/{id}/applications", id)
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.data[0].studentName").value("학생A"))
                .andExpect(jsonPath("$.data[1].studentName").value("학생B"))
                .andExpect(jsonPath("$.data[2].studentName").value("학생C"));
    }

    @Test
    @DisplayName("★ 확정 인원보다 적은 정원으로 줄일 수 없다 — 누구를 뺄지 정할 방법이 없다")
    void cannotShrinkCapacityBelowConfirmed() throws Exception {
        long id = openLecture(3);
        apply(studentA, id);
        apply(studentB, id);

        mvc.perform(patch("/api/v1/admin/lectures/{id}", id)
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capacity":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LECTURE_CAPACITY_BELOW_CONFIRMED"));
    }

    @Test
    @DisplayName("관리자는 정원을 넘겨서라도 수동 승격할 수 있다 — 운영 판단을 존중한다")
    void adminCanPromoteBeyondCapacity() throws Exception {
        long id = openLecture(1);
        apply(studentA, id);
        long second = applicationId(apply(studentB, id));

        mvc.perform(post("/api/v1/admin/lectures/applications/{id}/promote", second)
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
    }

    // ── 출석부 ───────────────────────────────────────────────────

    @Test
    @DisplayName("★ 출석 대상은 확정자만이다 — 대기·취소자가 출석부에 있으면 안 된다")
    void attendanceTargetsAreConfirmedOnly() throws Exception {
        long id = openLecture(1);
        apply(studentA, id);
        apply(studentB, id);   // 대기
        long third = applicationId(apply(studentC, id));
        mvc.perform(delete("/api/v1/app/lectures/applications/{id}", third)
                .header("Authorization", appToken(studentC))).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/lectures/{id}/attendance-targets", id)
                        .header("Authorization", adminToken()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].studentName").value("학생A"));
    }

    @Test
    @DisplayName("출석 체크는 덮어쓴다 — 강사가 손으로 넣는 값이라 정정이 잦다")
    void attendanceIsUpsert() throws Exception {
        long id = openLecture(5);
        long applicationId = applicationId(apply(studentA, id));
        String token = adminToken();

        String sessionBody = mvc.perform(post("/api/v1/admin/lectures/{id}/sessions", id)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionDate":"2026-09-01","startTime":"14:00","endTime":"16:00"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(sessionBody).path("data").path("id").asLong();
        em.flush();

        for (String status : new String[]{"ABSENT", "PRESENT"}) {
            mvc.perform(put("/api/v1/admin/lectures/sessions/{id}/attendances", sessionId)
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"applicationId":%d,"status":"%s"}""".formatted(applicationId, status)))
                    .andExpect(status().isOk());
            em.flush();
        }

        mvc.perform(get("/api/v1/admin/lectures/sessions/{id}/attendances", sessionId)
                        .header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PRESENT"));
    }

    @Test
    @DisplayName("회차 번호는 1부터 자동 증가한다")
    void sessionNoAutoIncrements() throws Exception {
        long id = openLecture(5);
        String token = adminToken();
        for (String date : new String[]{"2026-09-01", "2026-09-08"}) {
            mvc.perform(post("/api/v1/admin/lectures/{id}/sessions", id)
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"sessionDate":"%s"}""".formatted(date))).andExpect(status().isOk());
            em.flush();
        }

        mvc.perform(get("/api/v1/admin/lectures/{id}/sessions", id).header("Authorization", token))
                .andExpect(jsonPath("$.data[0].sessionNo").value(1))
                .andExpect(jsonPath("$.data[1].sessionNo").value(2));
    }

    // ── 권한 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("학부모 계정은 신청할 수 없다 — 경로만 알면 호출은 된다")
    void guardianCannotApply() throws Exception {
        long id = openLecture(5);

        ParentGuardian guardian = new ParentGuardian("학부모", "010-9999-1111", null);
        em.persist(guardian);
        em.persist(Account.forGuardian(guardian, "010-9999-1111", passwordEncoder.encode(PASSWORD)));
        em.flush();

        mvc.perform(post("/api/v1/app/lectures/{id}/apply", id)
                        .header("Authorization", appToken("010-9999-1111")))
                .andExpect(status().isForbidden());
    }
}
