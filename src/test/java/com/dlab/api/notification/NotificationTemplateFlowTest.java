package com.dlab.api.notification;

import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.entity.ReviewStatus;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
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
 * 알림 템플릿 관리 (실행가이드 P1-12).
 *
 * <p>지키려는 것 — <b>발송 가능 여부가 세 축의 AND일 것</b>,
 * <b>승인된 문구를 고치면 재심사로 돌아갈 것</b>, <b>빈 문구를 확정할 수 없을 것</b>.
 */
@SpringBootTest
@Transactional
class NotificationTemplateFlowTest {

    private static final String PASSWORD = "template-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long alimtalkTemplateId;
    Long fcmTemplateId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("NT01", "템플릿테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        createAdmin(academy, "NTSUPER", "SUPER_ADMIN");
        createAdmin(academy, "NTBRANCH", "BRANCH_ADMIN");
        em.flush();

        // V1 seed — 미등원은 알림톡, 승인반려는 FCM
        alimtalkTemplateId = templateId("MISSING_ATTENDANCE");
        fcmTemplateId = templateId("APPROVAL_REJECTED");
    }

    private void createAdmin(Academy academy, String loginId, String role) {
        Employee employee = new Employee(academy, loginId);
        em.persist(employee);
        Account account = Account.forEmployee(employee, loginId, passwordEncoder.encode(PASSWORD));
        em.persist(account);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :role
                        """)
                .setParameter("accountId", account.getId())
                .setParameter("role", role).executeUpdate();
    }

    private Long templateId(String eventCode) {
        return em.createQuery("""
                SELECT t.id FROM NotificationTemplate t WHERE t.eventCode = :e
                """, Long.class)
                .setParameter("e", com.dlab.domain.notification.entity.NotificationEvent.valueOf(eventCode))
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

    private void updateContent(Long id, String title, String body, boolean confirmed)
            throws Exception {
        mvc.perform(patch("/api/v1/admin/notification-templates/{id}/content", id)
                        .header("Authorization", token("NTSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titleTemplate":"%s","bodyTemplate":"%s","contentConfirmed":%b}"""
                                .formatted(title, body, confirmed)))
                .andExpect(status().isOk());
        em.flush();
    }

    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("목록이 매핑표처럼 나온다 — 이벤트·채널·수신자·발송가능")
    void listLooksLikeMappingTable() throws Exception {
        mvc.perform(get("/api/v1/admin/notification-templates")
                        .header("Authorization", token("NTSUPER")))
                .andExpect(status().isOk())
                // 개수를 박지 않는다 — 이벤트가 늘 때마다 무관한 테스트가 깨진다.
                // 여기서 볼 것은 "매핑표에 필요한 칸이 다 있느냐"다
                .andExpect(jsonPath("$.data.length()")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$.data[0].event").exists())
                .andExpect(jsonPath("$.data[0].channel").exists())
                .andExpect(jsonPath("$.data[0].recipientType").exists())
                .andExpect(jsonPath("$.data[0].sendable").exists());
    }

    @Test
    @DisplayName("★ 승인 요청 알림의 수신자는 ROUTED다 — 승인 주체 설정에 따라 갈린다")
    void approvalRequestIsRouted() throws Exception {
        mvc.perform(get("/api/v1/admin/notification-templates")
                        .header("Authorization", token("NTSUPER")))
                .andExpect(jsonPath("$.data[?(@.event == 'APPROVAL_REQUEST_CREATED')].recipientType")
                        .value("ROUTED"));
    }

    @Test
    @DisplayName("★ 발송 가능은 세 축의 AND다 — 활성 · 문구확정 · 심사통과")
    void sendableRequiresAllThree() throws Exception {
        String token = token("NTSUPER");

        // seed 상태: 문구 비어 있고 미확정, 알림톡이라 DRAFT → 발송 불가
        assertThat(sendable(alimtalkTemplateId)).isFalse();

        // 문구 확정만 해도 아직 심사가 안 됐다
        updateContent(alimtalkTemplateId, "{studentName} 미등원", "{studentName} 학생이 등원하지 않았습니다", true);
        assertThat(sendable(alimtalkTemplateId)).isFalse();

        // 심사 제출 → 아직 결과 없음
        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/submit", alimtalkTemplateId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kakaoTemplateCode":"DLAB_MISS_001"}""")).andExpect(status().isOk());
        em.flush();
        assertThat(sendable(alimtalkTemplateId)).isFalse();

        // 승인 → 이제 발송 가능
        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/result", alimtalkTemplateId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"approved":true}""")).andExpect(status().isOk());
        em.flush();
        assertThat(sendable(alimtalkTemplateId)).isTrue();
    }

    @Test
    @DisplayName("★ 승인된 알림톡 문구를 고치면 DRAFT로 돌아간다 — 카카오는 승인받은 문구만 허용한다")
    void editingApprovedContentResetsReview() throws Exception {
        String token = token("NTSUPER");
        updateContent(alimtalkTemplateId, "{studentName} 미등원", "{studentName} 본문", true);
        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/submit", alimtalkTemplateId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kakaoTemplateCode":"DLAB_MISS_001"}""")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/result", alimtalkTemplateId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"approved":true}""")).andExpect(status().isOk());
        em.flush();
        assertThat(reviewStatus(alimtalkTemplateId)).isEqualTo(ReviewStatus.APPROVED);

        // 문구 수정 → 재심사 대상
        updateContent(alimtalkTemplateId, "{studentName} 미등원 안내", "{studentName} 본문 수정", true);
        em.clear();

        assertThat(reviewStatus(alimtalkTemplateId)).isEqualTo(ReviewStatus.DRAFT);
        assertThat(sendable(alimtalkTemplateId)).isFalse();
    }

    @Test
    @DisplayName("문구가 그대로면 승인 상태가 유지된다 — 확정 플래그만 다시 눌러도 재심사가 되면 안 된다")
    void sameContentKeepsApproval() throws Exception {
        String token = token("NTSUPER");
        updateContent(alimtalkTemplateId, "제목", "{studentName} 본문", true);
        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/submit", alimtalkTemplateId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kakaoTemplateCode":"C1"}""")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/result", alimtalkTemplateId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"approved":true}""")).andExpect(status().isOk());
        em.flush();

        updateContent(alimtalkTemplateId, "제목", "{studentName} 본문", true);
        em.clear();

        assertThat(reviewStatus(alimtalkTemplateId)).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    @DisplayName("★ 빈 문구는 확정할 수 없다 — 확정되면 내용 없는 알림이 실제로 나간다")
    void cannotConfirmEmptyContent() throws Exception {
        mvc.perform(patch("/api/v1/admin/notification-templates/{id}/content", fcmTemplateId)
                        .header("Authorization", token("NTSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titleTemplate":"","bodyTemplate":"","contentConfirmed":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_TEMPLATE_CONTENT_EMPTY"));
    }

    @Test
    @DisplayName("★ FCM 템플릿은 심사 대상이 아니다 — 심사 목록에 섞이면 왜 안 넘어가는지 헤맨다")
    void fcmCannotBeSubmitted() throws Exception {
        updateContent(fcmTemplateId, "제목", "{studentName} 본문", true);

        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/submit", fcmTemplateId)
                        .header("Authorization", token("NTSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kakaoTemplateCode":"X1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_REVIEW_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("FCM은 심사 없이 문구만 확정하면 발송 가능해진다")
    void fcmSendableAfterContentConfirmed() throws Exception {
        assertThat(sendable(fcmTemplateId)).isFalse();
        updateContent(fcmTemplateId, "{studentName} 반려", "{studentName} 학생의 신청이 반려되었습니다", true);
        em.clear();
        assertThat(sendable(fcmTemplateId)).isTrue();
    }

    @Test
    @DisplayName("심사 반려되면 사유가 남는다 — 없으면 뭘 고쳐야 할지 모른다")
    void rejectionKeepsNote() throws Exception {
        String token = token("NTSUPER");
        updateContent(alimtalkTemplateId, "제목", "{studentName} 본문", true);
        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/submit", alimtalkTemplateId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kakaoTemplateCode":"C1"}""")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/notification-templates/{id}/review/result", alimtalkTemplateId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approved":false,"note":"광고성 문구 포함"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewNote").value("광고성 문구 포함"));
    }

    @Test
    @DisplayName("심사 진행 중 목록에는 알림톡만 나온다")
    void inReviewListExcludesFcm() throws Exception {
        mvc.perform(get("/api/v1/admin/notification-templates/in-review")
                        .header("Authorization", token("NTSUPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.channel == 'FCM_PUSH')]").isEmpty());
    }

    @Test
    @DisplayName("★ 지점 관리자는 못 만진다 — 전 지점 공통 문구가 같이 바뀐다")
    void branchAdminCannotEdit() throws Exception {
        mvc.perform(get("/api/v1/admin/notification-templates")
                        .header("Authorization", token("NTBRANCH")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("같은 이벤트로 템플릿을 두 개 만들 수 없다 — 발송 시 무엇을 쓸지 정할 수 없다")
    void duplicateEventRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/notification-templates")
                        .header("Authorization", token("NTSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"MISSING_ATTENDANCE","channel":"FCM_PUSH"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_TEMPLATE_DUPLICATED"));
    }

    @Test
    @DisplayName("★★ 알림톡은 심사를 통과해야 발송 가능하다 — NOT_REQUIRED 로 새어나가면 카카오가 거절한다")
    void alimtalkNeedsApprovalEvenIfNotRequired() throws Exception {
        updateContent(alimtalkTemplateId, "{studentName} 미등원", "{studentName} 본문", true);
        em.clear();

        // 마이그레이션 순서 때문에 알림톡이 NOT_REQUIRED 로 들어간 적이 있다.
        // 그 상태를 강제로 만들어도 발송 가능이 되면 안 된다 —
        // 화면은 "나감"인데 카카오에서 거절되고 원인이 우리 쪽에 안 남는다
        em.createQuery("UPDATE NotificationTemplate t SET t.reviewStatus = :s WHERE t.id = :id")
                .setParameter("s", ReviewStatus.NOT_REQUIRED)
                .setParameter("id", alimtalkTemplateId)
                .executeUpdate();
        em.clear();

        assertThat(sendable(alimtalkTemplateId)).isFalse();
    }

    @Test
    @DisplayName("★ 본문에 학생명 자리가 없으면 확정되지 않는다 — 다자녀 학부모가 누구 얘긴지 모른다")
    void confirmRequiresStudentNamePlaceholder() throws Exception {
        mvc.perform(patch("/api/v1/admin/notification-templates/{id}/content", alimtalkTemplateId)
                        .header("Authorization", token("NTSUPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titleTemplate":"미등원","bodyTemplate":"학생이 등원하지 않았습니다",\
                                 "contentConfirmed":true}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("확정 전 초안은 학생명 없이도 저장된다 — 쓰다 만 문구까지 막으면 작성이 불편하다")
    void draftDoesNotRequirePlaceholder() throws Exception {
        updateContent(alimtalkTemplateId, "미등원", "아직 쓰는 중", false);
    }

    private boolean sendable(Long id) {
        return em.find(NotificationTemplate.class, id).isSendable();
    }

    private ReviewStatus reviewStatus(Long id) {
        return em.find(NotificationTemplate.class, id).getReviewStatus();
    }
}
