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
    @Autowired com.dlab.domain.notification.service.NotificationService notificationService;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Academy otherAcademy;
    Long manualTemplateId;
    Long eventTemplateId;
    Long classId;
    ClassMaster classMaster;
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
        classMaster = new ClassMaster(academy, year, "1반", ClassType.FIXED, null);
        em.persist(classMaster);
        classId = classMaster.getId();

        enrollmentWithGuardian =
                createStudent(academy, "MS-A", "학부모있음", "010-9876-5432", classMaster);
        enrollmentWithoutGuardian =
                createStudent(academy, "MS-B", "학부모없음", null, classMaster);
        otherAcademyEnrollment =
                createStudent(otherAcademy, "MS-C", "타지점생", "010-9876-1111", null);

        em.flush();

        // 마이그레이션 seed. ★ 급식 마감·등록기간은 student_specific = FALSE 라
        //    학생명 없이도 확정돼 있다(V20261023090000) — 아래 공통 안내 테스트 참고
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
        // ★ 보호자 수신이므로 「보호자 연락처가 없다」다 — ★ 「계정이 없다」가 아니다.
        //   번호만 있어도 받으므로(#290) 계정 유무는 이유가 될 수 없고, 담당자가 할 일은
        //   앱 가입 안내가 아니라 ★ 연락처 등록이다
        assertThat(root.path("blocked").get(0).path("reason").asString())
                .isEqualTo("NO_GUARDIAN");
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

    /**
     * ⚠️ <b>토큰이 같은 지점이어야 한다.</b> 전에는 타지점 관리자 토큰을 써서
     * <b>「다른 지점 학생입니다」로 400</b> 이 났고, 그래도 {@code isBadRequest()} 만
     * 단정해 <b>통과했다</b> — 보려던 것(보호자가 없어 대상이 0명)을 보지 못했다.
     */
    @Test
    @DisplayName("대상이 아무도 없으면 미리보기 자체가 거부된다 — 0명에게 보내는 previewId 를 만들지 않는다")
    void emptyTargetIsRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/manual/preview")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"scope":"SELECTED","enrollmentIds":[%d]}"""
                                .formatted(manualTemplateId, enrollmentWithoutGuardian)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MANUAL_SEND_NO_TARGET"));
    }

    /**
     * ★★ 0명일 때 <b>왜 0명인지</b>가 응답에 있어야 한다.
     *
     * <p>사유 없이 「발송 대상이 없습니다」만 주면 담당자는 <b>무엇을 고쳐야 하는지 알
     * 방법이 없다</b> — 연락처를 넣어야 하는지, 앱 가입을 안내해야 하는지, 수신거부를
     * 푼 것인지, 이관 허용목록 때문인지가 <b>전부 같은 문장으로 보인다.</b>
     * 2026-10-27 에 프런트가 그 자리에서 막혀 「계정이 없어서」와 「허용목록 때문」을
     * 구분하지 못했다.
     */
    @Test
    @DisplayName("★★ 0명일 때 제외 사유가 응답에 담긴다 — 없으면 무엇을 고쳐야 할지 모른다")
    void emptyTargetTellsWhy() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/manual/preview")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"scope":"SELECTED","enrollmentIds":[%d]}"""
                                .formatted(manualTemplateId, enrollmentWithoutGuardian)))
                .andExpect(status().isBadRequest())
                // ★ 사유별 인원 — 화면이 「연락처 없음 1명」처럼 한 줄로 쓴다
                .andExpect(jsonPath("$.error.data.summary").isMap())
                // ★★ 보호자 수신인데 「앱 가입이 필요합니다」를 주면 담당자가 엉뚱한 일을
                //    한다 — 보호자는 번호만 있어도 받으므로(#290) 할 일은 연락처 등록이다.
                //    2026-10-29 운영에서 NO_ACCOUNT 가 나가던 것을 프런트가 발견했다.
                // ★★ 보호자 수신인데 「앱 가입이 필요합니다」를 주면 담당자가 엉뚱한 일을
                //    한다 — 보호자는 번호만 있어도 받으므로(#290) 할 일은 연락처 등록이다.
                //    2026-10-29 운영에서 NO_ACCOUNT 가 나가던 것을 프런트가 발견했다.
                .andExpect(jsonPath("$.error.data.summary.NO_GUARDIAN").exists())
                .andExpect(jsonPath("$.error.data.summary.NO_ACCOUNT").doesNotExist())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("연락처")))
                .andExpect(jsonPath("$.error.data.blocked[0].reason").exists())
                .andExpect(jsonPath("$.error.data.blocked[0].message").exists())
                // ★ 메시지에 사유가 들어가 토스트만 봐도 방향이 잡힌다
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.not("발송 대상이 없습니다.")));
    }

    @Test
    @DisplayName("★ 애초에 고른 학생이 없으면 조건을 보라고 말한다 — 수신 설정을 뒤지게 하지 않는다")
    void noCandidateSaysCheckFilters() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/manual/preview")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"scope":"SELECTED","enrollmentIds":[999999]}"""
                                .formatted(manualTemplateId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("조건")));
    }

    // ──────────────────────────────────────────────────────────────
    //  ★★ 공통 안내 — 학생명을 요구하지 않고, 한 사람에게 한 통만 간다
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 공통 안내는 학생명 없이도 발송 가능하다 — 그동안 막혀 있었다")
    void commonNoticeIsSendableWithoutStudentName() throws Exception {
        // 급식 마감 문구에는 학생명이 없다. 「모든 템플릿에 학생명 필수」 규칙에 걸려
        // content_confirmed 를 올릴 수 없었고, 그래서 문구를 등록해도 보낼 수 없었다.
        String body = mvc.perform(get("/api/v1/admin/notification-templates")
                        .header("Authorization", token("MSBRANCH"))
                        .param("manual", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var meal = objectMapper.readTree(body).path("data").valueStream()
                .filter(n -> "MEAL_APPLY_DEADLINE".equals(n.path("event").asString()))
                .findFirst().orElseThrow();

        assertThat(meal.path("bodyTemplate").asString()).doesNotContain("studentName");
        assertThat(meal.path("studentSpecific").asBoolean())
                .as("공통 안내로 표시돼야 한다 — 화면이 이 값으로 「받는 사람」을 설명한다")
                .isFalse();
        assertThat(meal.path("contentConfirmed").asBoolean())
                .as("학생명이 없어도 확정돼 있어야 한다 — 공통 안내다").isTrue();
    }

    @Test
    @DisplayName("★★ 공통 안내는 학부모 한 명에게 한 통만 — 자녀가 둘이면 두 통이 가던 것")
    void commonNoticeIsNotDuplicatedPerChild() throws Exception {
        // 같은 학부모에게 자녀 둘을 연결한다
        Student second = new Student("MS-TWIN", "둘째", "010-1234-7777");
        em.persist(second);
        StudentEnrollment twin = new StudentEnrollment(second, academy,
                (short) LocalDate.now().getYear(), "MS-TWIN", null, GradeType.N_SU);
        em.persist(twin);
        em.persist(new ClassAssignment(academy, twin, classMaster, ClassType.FIXED));
        // ★ 첫째의 학부모를 그대로 연결한다 — 계정이 하나다
        ParentGuardian shared = em.createQuery("""
                SELECT l.guardian FROM StudentGuardianLink l
                 WHERE l.student.uniqueCode = :c
                """, ParentGuardian.class).setParameter("c", "MS-A")
                .getSingleResult();
        em.persist(new StudentGuardianLink(second, shared, (short) 1));
        em.flush();

        long mealTemplate = templateId("MEAL_APPLY_DEADLINE");
        String preview = preview("""
                {"templateId":%d,"scope":"CLASS","classId":%d,
                 "variables":{"월":"11","날짜":"10월 28일"}}"""
                .formatted(mealTemplate, classId));

        var recipients = objectMapper.readTree(preview).path("data").path("recipients");
        long sharedCount = recipients.valueStream()
                .filter(n -> n.path("receiverName").asString().equals(shared.getName()))
                .count();

        assertThat(sharedCount)
                .as("같은 학부모에게 공통 안내가 두 통 가면 안 된다 — 건당 과금이고 "
                        + "학부모에게는 같은 문장이 반복된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 학생 개인 알림은 자녀마다 보낸다 — 거기서 묶으면 한 자녀 알림이 사라진다")
    void studentSpecificNoticeStillGoesPerChild() throws Exception {
        Student second = new Student("MS-TWIN2", "둘째2", "010-1234-7778");
        em.persist(second);
        StudentEnrollment twin = new StudentEnrollment(second, academy,
                (short) LocalDate.now().getYear(), "MS-TWIN2", null, GradeType.N_SU);
        em.persist(twin);
        em.persist(new ClassAssignment(academy, twin, classMaster, ClassType.FIXED));
        ParentGuardian shared = em.createQuery("""
                SELECT l.guardian FROM StudentGuardianLink l
                 WHERE l.student.uniqueCode = :c
                """, ParentGuardian.class).setParameter("c", "MS-A")
                .getSingleResult();
        em.persist(new StudentGuardianLink(second, shared, (short) 1));
        em.flush();

        // 등록비 미납은 자녀마다 금액·사실이 다르다 — 두 통이 맞다
        String preview = preview(classScopeBody());
        var recipients = objectMapper.readTree(preview).path("data").path("recipients");
        long sharedCount = recipients.valueStream()
                .filter(n -> n.path("receiverName").asString().equals(shared.getName()))
                .count();

        assertThat(sharedCount)
                .as("학생 개인 알림은 자녀 수만큼 가야 한다")
                .isEqualTo(2);
    }

    // ── 수신 거부 ──────────────────────────────────────────────

    /** 이 계정이 이 이벤트를 끈 것으로 만든다. */
    private void optOut(Long accountId,
                        com.dlab.domain.notification.entity.NotificationEvent event) {
        Account account = em.find(Account.class, accountId);
        em.persist(new com.dlab.domain.appconfig.entity.NotificationPreference(
                account, event, false));
        em.flush();
    }

    private Long guardianAccountId(String studentName) {
        return em.createQuery("""
                        SELECT a.id FROM Account a
                        WHERE a.guardian.name = :name
                        """, Long.class)
                .setParameter("name", studentName + "모")
                .getSingleResult();
    }

    private long logCountFor(Long accountId) {
        em.flush();
        return em.createQuery("""
                        SELECT COUNT(l) FROM NotificationLog l WHERE l.recipient.id = :id
                        """, Long.class)
                .setParameter("id", accountId)
                .getSingleResult();
    }

    @Test
    @DisplayName("★★ 미리보기에서 빠진 사람과 실제 발송에서 빠지는 사람이 같다 — 판정이 한 군데다")
    void optOutIsConsistentBetweenPreviewAndSend() throws Exception {
        // 반에 거부하지 않은 학부모를 한 명 더 둔다 — 거부자만 있으면 미리보기 자체가 거부된다
        createStudent(academy, "MS-D", "거부안함", "010-9876-7777", classMaster);

        var event = com.dlab.domain.notification.entity.NotificationEvent.TUITION_UNPAID_NOTICE;
        Long optedOut = guardianAccountId("학부모있음");
        optOut(optedOut, event);
        em.clear();

        // ① 미리보기 — 거부자는 OPTED_OUT 으로 빠진다
        var root = objectMapper.readTree(preview(classScopeBody())).path("data");
        assertThat(root.path("targetCount").asInt()).isEqualTo(1);
        assertThat(root.path("recipients").get(0).path("studentName").asString())
                .isEqualTo("거부안함");
        assertThat(root.path("blocked").toString()).contains("OPTED_OUT");

        // ② 발송 — 거부자에게는 이력이 한 건도 생기지 않는다(미리보기에서 이미 빠졌다)
        mvc.perform(post("/api/v1/admin/notifications/manual/send")
                        .header("Authorization", token("MSBRANCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"previewId":"%s"}""".formatted(
                                root.path("previewId").asString())))
                .andExpect(status().isOk());
        assertThat(logCountFor(optedOut)).isZero();
        assertThat(logCountFor(guardianAccountId("거부안함"))).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 자동 발송 경로도 같은 거부 설정을 본다 — 그동안 이것만 그대로 나갔다")
    void autoDispatchHonoursOptOut() {
        var event = com.dlab.domain.notification.entity.NotificationEvent.TUITION_UNPAID_NOTICE;
        Long optedOut = guardianAccountId("학부모있음");
        optOut(optedOut, event);
        em.clear();

        StudentEnrollment enrollment = em.find(StudentEnrollment.class, enrollmentWithGuardian);
        var log = notificationService.send(
                com.dlab.domain.notification.service.NotificationCommand.forStudent(
                        event, em.find(Account.class, optedOut), enrollment.getStudent(),
                        academy, enrollment.getYear(), java.util.Map.of("월", "11"), null));

        // ★ 조용히 버리지 않는다 — 왜 안 갔는지가 이력에 남아야 문의에 답할 수 있다
        assertThat(log.getStatus())
                .isEqualTo(com.dlab.domain.notification.entity.NotificationStatus.SKIPPED);
        assertThat(log.getFailReason()).contains("수신 거부");
    }
}
