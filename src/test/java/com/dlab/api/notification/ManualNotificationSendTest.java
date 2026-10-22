package com.dlab.api.notification;

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
 * 직원 수동 발송 (디랩 1016 「직원 발송」).
 *
 * <p>지키려는 것 —
 * <b>미리보기가 아무것도 보내지 않을 것</b>,
 * <b>미리보기를 거치지 않으면 발송이 거부될 것</b>,
 * <b>{@code previewId} 가 한 번만 먹을 것</b>(알림톡은 건당 과금이다),
 * <b>제외된 사람이 드러날 것</b>,
 * <b>번호가 마스킹될 것</b>,
 * <b>다른 지점으로 새지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class ManualNotificationSendTest {

    private static final String PASSWORD = "manual-send-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Academy otherAcademy;
    Long manualTemplateId;
    Long eventTemplateId;
    Long classId;
    Long enrollmentWithGuardian;
    Long enrollmentWithoutGuardian;
    Long otherAcademyEnrollment;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("MS01", "수동발송지점", LocalTime.of(9, 0));
        otherAcademy = new Academy("MS02", "다른지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.persist(otherAcademy);

        createAdmin(academy, "MSBRANCH", "BRANCH_ADMIN");
        createAdmin(otherAcademy, "MSOTHER", "BRANCH_ADMIN");

        short year = (short) LocalDate.now().getYear();
        ClassMaster classMaster = new ClassMaster(academy, year, "1반", ClassType.FIXED, null);
        em.persist(classMaster);
        classId = classMaster.getId();

        enrollmentWithGuardian =
                createStudent(academy, "MS-A", "학부모있음", "010-9876-5432", classMaster);
        enrollmentWithoutGuardian =
                createStudent(academy, "MS-B", "학부모없음", null, classMaster);
        otherAcademyEnrollment =
                createStudent(otherAcademy, "MS-C", "타지점생", "010-9876-1111", null);

        em.flush();

        // 마이그레이션 seed — 등록비 미납만 문구에 학생명이 있어 확정돼 있다
        manualTemplateId = templateId("TUITION_UNPAID_NOTICE");
        eventTemplateId = templateId("MISSING_ATTENDANCE");
    }

    private void createAdmin(Academy target, String loginId, String role) {
        Employee employee = new Employee(target, loginId);
        em.persist(employee);
        Account account = Account.forEmployee(employee, loginId,
                passwordEncoder.encode(PASSWORD), false);
        em.persist(account);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :role
                        """)
                .setParameter("accountId", account.getId())
                .setParameter("role", role).executeUpdate();
    }

    /**
     * @param guardianPhone null 이면 학부모를 만들지 않는다.
     *                      ⚠️ {@code parent_guardian.phone} 이 UNIQUE 라 건마다 달라야 한다
     * @return enrollmentId
     */
    private Long createStudent(Academy target, String code, String name, String guardianPhone,
                               ClassMaster classMaster) {
        short year = (short) LocalDate.now().getYear();
        // ⚠️ student.phone 도 UNIQUE 다 — 고유코드를 섞어 만든다
        Student student = new Student(code, name, "010-1234-" + Math.abs(code.hashCode() % 10000));
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(student, target, year,
                code, null, GradeType.N_SU);
        em.persist(enrollment);
        if (classMaster != null) {
            em.persist(new ClassAssignment(target, enrollment, classMaster, ClassType.FIXED));
        }
        if (guardianPhone != null) {
            ParentGuardian guardian = new ParentGuardian(name + "모", guardianPhone, "F");
            em.persist(guardian);
            em.persist(new StudentGuardianLink(student, guardian, (short) 1));
            em.persist(Account.forGuardian(guardian, "G" + code,
                    passwordEncoder.encode(PASSWORD)));
        }
        em.flush();
        return enrollment.getId();
    }

    private Long templateId(String eventCode) {
        return em.createQuery("""
                        SELECT t.id FROM NotificationTemplate t WHERE t.eventCode = :e
                        """, Long.class)
                .setParameter("e",
                        com.dlab.domain.notification.entity.NotificationEvent.valueOf(eventCode))
                .getSingleResult();
    }

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private String preview(String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/notifications/manual/preview")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String classScopeBody() {
        return """
                {"templateId":%d,"scope":"CLASS","classId":%d,"variables":{"월":"11"}}"""
                .formatted(manualTemplateId, classId);
    }

    private long logCount() {
        em.flush();
        return em.createQuery("SELECT COUNT(l) FROM NotificationLog l", Long.class)
                .getSingleResult();
    }

    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ manual=true 가 수동 발송 템플릿을 가린다 — 발송 불가인 것도 담긴다")
    void manualListIncludesUnsendable() throws Exception {
        String body = mvc.perform(get("/api/v1/admin/notification-templates")
                        .param("manual", "true")
                        .header("Authorization", token("MSBRANCH")))
                .andExpect(status().isOk())
                // 이벤트 트리거 템플릿은 안 나온다
                .andExpect(jsonPath("$.data[?(@.event == 'MISSING_ATTENDANCE')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.event == 'TUITION_UNPAID_NOTICE')]").isNotEmpty())
                // ⚠️ 급식 안내는 심사 전이라 sendable=false 인데 목록에는 있어야 한다 —
                //    빼면 「급식 안내가 왜 없지」가 된다
                .andExpect(jsonPath("$.data[?(@.event == 'MEAL_APPLY_DEADLINE')]").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"manual\":true");
        assertThat(objectMapper.readTree(body).path("data").findValues("sendable").stream()
                .anyMatch(n -> !n.asBoolean())).isTrue();
    }

    @Test
    @DisplayName("★★ 미리보기는 아무것도 보내지 않는다 — 이력이 한 건도 안 생긴다")
    void previewSendsNothing() throws Exception {
        long before = logCount();
        preview(classScopeBody());
        assertThat(logCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("★ 번호는 마스킹해서 준다 — 발송 화면에서 전체 번호를 볼 이유가 없다")
    void phoneIsMasked() throws Exception {
        String body = preview(classScopeBody());
        assertThat(body).contains("010-****-5432").doesNotContain("010-9876-5432");
    }

    @Test
    @DisplayName("★★ 제외된 사람이 드러난다 — 30명이라 하고 28명에게 가면 담당자는 영영 모른다")
    void blockedIsReported() throws Exception {
        String body = preview(classScopeBody());
        var root = objectMapper.readTree(body).path("data");

        assertThat(root.path("targetCount").asInt()).isEqualTo(1);
        assertThat(root.path("blocked").size()).isEqualTo(1);
        assertThat(root.path("blocked").get(0).path("reason").asString())
                .isEqualTo("NO_ACCOUNT");
        // 사유 코드만 주면 화면마다 다른 말로 번역된다
        assertThat(root.path("blocked").get(0).path("message").asString()).isNotBlank();
        assertThat(root.path("blocked").get(0).path("studentName").asString())
                .isEqualTo("학부모없음");
    }

    @Test
    @DisplayName("미리보기 문구에 변수가 채워진다 — 학생명은 서버가 채우므로 누락 목록에 없다")
    void renderedAndMissingVariables() throws Exception {
        var root = objectMapper.readTree(preview(classScopeBody())).path("data");

        assertThat(root.path("rendered").path("body").asString())
                .contains("학부모있음").contains("11");
        assertThat(root.path("missingVariables").size()).isZero();

        // 변수를 안 채우면 그 변수만 드러난다
        var missing = objectMapper.readTree(preview("""
                        {"templateId":%d,"scope":"CLASS","classId":%d,"variables":{}}"""
                .formatted(manualTemplateId, classId))).path("data").path("missingVariables");
        assertThat(missing.size()).isEqualTo(1);
        assertThat(missing.get(0).asString()).isEqualTo("월");
    }

    @Test
    @DisplayName("★★ previewId 는 한 번만 먹는다 — 두 번째는 409 (알림톡은 건당 과금이다)")
    void previewIdIsSingleUse() throws Exception {
        String previewId = objectMapper.readTree(preview(classScopeBody()))
                .path("data").path("previewId").asString();
        String token = token("MSBRANCH");
        String body = """
                {"previewId":"%s"}""".formatted(previewId);

        mvc.perform(post("/api/v1/admin/notifications/manual/send")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requested").value(1));
        em.flush();

        mvc.perform(post("/api/v1/admin/notifications/manual/send")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("★★ 미리보기를 거치지 않으면 발송이 거부된다 — 본 것과 다른 사람에게 가지 않게")
    void sendRequiresPreview() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/manual/send")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"previewId":"%s"}""".formatted(java.util.UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 유효기간 10분이 지나면 다시 미리보기부터 — 그사이 퇴원한 학생이 섞인다")
    void expiredPreviewIsRejected() throws Exception {
        String previewId = objectMapper.readTree(preview(classScopeBody()))
                .path("data").path("previewId").asString();
        em.flush();
        em.createNativeQuery("""
                        UPDATE notification_manual_preview
                        SET expires_at = now() - interval '1 minute'
                        WHERE preview_id = CAST(:id AS uuid)
                        """)
                .setParameter("id", previewId).executeUpdate();
        // ⚠️ 영속성 컨텍스트에 남은 엔티티가 옛 만료시각을 들고 있다 — 비우지 않으면
        //    네이티브 UPDATE 가 조회에 반영되지 않는다
        em.clear();

        mvc.perform(post("/api/v1/admin/notifications/manual/send")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"previewId":"%s"}""".formatted(previewId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 이벤트 트리거 템플릿으로는 수동 발송할 수 없다")
    void eventTemplateCannotBeSentManually() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/manual/preview")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"scope":"BRANCH"}""".formatted(eventTemplateId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★★ 다른 지점으로 새지 않는다 — 요청 지점은 403, 섞인 학생은 제외로 드러난다")
    void academyScopeIsEnforced() throws Exception {
        // 지점 관리자가 남의 지점을 지정하면 거절된다
        mvc.perform(post("/api/v1/admin/notifications/manual/preview")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"academyId":%d,"scope":"BRANCH"}"""
                                .formatted(manualTemplateId, otherAcademy.getId())))
                .andExpect(status().isForbidden());

        // 명단에 타지점 학생을 섞어 넣으면 조용히 빠지지 않고 사유로 나온다
        var root = objectMapper.readTree(preview("""
                {"templateId":%d,"scope":"SELECTED","enrollmentIds":[%d,%d],
                 "variables":{"월":"11"}}"""
                .formatted(manualTemplateId, enrollmentWithGuardian, otherAcademyEnrollment)))
                .path("data");

        assertThat(root.path("targetCount").asInt()).isEqualTo(1);
        assertThat(root.path("blocked").get(0).path("reason").asString())
                .isEqualTo("OTHER_ACADEMY");
    }

    @Test
    @DisplayName("★ 발송은 기존 경로를 탄다 — 심사 미통과라 이력이 SKIPPED 로 남는다")
    void sendGoesThroughExistingGates() throws Exception {
        String previewId = objectMapper.readTree(preview(classScopeBody()))
                .path("data").path("previewId").asString();

        mvc.perform(post("/api/v1/admin/notifications/manual/send")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"previewId":"%s"}""".formatted(previewId)))
                .andExpect(status().isOk())
                // 카카오 심사(E-5) 전이라 실제로는 안 나간다. 그 사실이 숫자로 보여야 한다
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.logIds.length()").value(1));
        em.flush();

        var status = em.createQuery("""
                        SELECT l.status FROM NotificationLog l
                        WHERE l.eventCode = com.dlab.domain.notification.entity
                              .NotificationEvent.TUITION_UNPAID_NOTICE
                        """, com.dlab.domain.notification.entity.NotificationStatus.class)
                .getResultList();
        assertThat(status)
                .containsExactly(com.dlab.domain.notification.entity.NotificationStatus.SKIPPED);
    }

    @Test
    @DisplayName("대상이 아무도 없으면 미리보기 자체가 거부된다 — 0명에게 보내는 previewId 를 만들지 않는다")
    void emptyTargetIsRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/manual/preview")
                        .header("Authorization", token("MSOTHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"scope":"SELECTED","enrollmentIds":[%d]}"""
                                .formatted(manualTemplateId, enrollmentWithoutGuardian)))
                .andExpect(status().isBadRequest());
    }
}
