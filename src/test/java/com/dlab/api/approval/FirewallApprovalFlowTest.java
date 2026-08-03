package com.dlab.api.approval;

import com.dlab.domain.approval.entity.*;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 방화벽 해제 신청 → 승인 전체 흐름.
 *
 * <p>이 프로젝트의 도메인 본질(학생 행위 → 승인 → 자동 처리)이 실제로 도는지 확인한다.
 * 특히 <b>승인 케이스 판별</b>과 <b>중복 승인 차단</b>은 깨져도 로그인·조회는 멀쩡해서
 * 테스트가 없으면 운영에서야 드러난다.
 */
@SpringBootTest
@Transactional
class FirewallApprovalFlowTest {

    private static final String PASSWORD = "flow-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long enrollmentId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("FW01", "흐름테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Teacher teacher = new Teacher(academy, "담임쌤", "010-1000-0000");
        em.persist(teacher);
        Account teacherAccount = Account.forTeacher(teacher, "FWT", passwordEncoder.encode(PASSWORD));
        em.persist(teacherAccount);
        em.flush();
        // RBAC 5단계 role은 V1 seed로 들어가 있다. 담당선생님 = TEACHER.
        grantRole(teacherAccount.getId(), "TEACHER");

        // 반 배정 → 여기서 승인 에스컬레이션 대상이 자동으로 결정된다
        ClassMaster classMaster = new ClassMaster(academy, (short) 2026, "1반", ClassType.FIXED, teacher);
        em.persist(classMaster);

        Student student = new Student("FWSTU01", "신청학생", "010-2000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, academy, (short) 2026, "0001", "RF0001", GradeType.N_SU);
        em.persist(enrollment);
        em.persist(new ClassAssignment(academy, enrollment, classMaster, ClassType.FIXED));

        Account studentAccount = Account.forStudent(student, "FWSTU", passwordEncoder.encode(PASSWORD));
        studentAccount.approve();   // 승인된 학생만 로그인 가능
        em.persist(studentAccount);

        ParentGuardian guardian = new ParentGuardian("학부모", "010-3000-0000", "F");
        em.persist(guardian);
        em.persist(new StudentGuardianLink(student, guardian, (short) 1));
        em.persist(Account.forGuardian(guardian, "FWPAR", passwordEncoder.encode(PASSWORD)));

        // 승인 정책: 학부모 1차 → 10분 → 담당선생님
        em.persist(new ApprovalItem(academy, (short) 2026, RequestType.FIREWALL_UNLOCK,
                ApproverType.PARENT, (short) ApprovalItem.FIREWALL_TIMEOUT_MINUTES, ApproverType.TEACHER));
        em.flush();

        enrollmentId = enrollment.getId();
    }

    private void grantRole(Long accountId, String roleName) {
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :roleName
                        """)
                .setParameter("accountId", accountId)
                .setParameter("roleName", roleName)
                .executeUpdate();
    }

    private String token(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private long createRequest() throws Exception {
        String body = mvc.perform(post("/api/v1/app/firewall/requests")
                        .header("Authorization", token("FWSTU"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedMinutes":60,"reason":"인강 수강"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("approvalRequestId").asLong();
    }

    @Test
    @DisplayName("학생이 신청하면 학부모 승인 대기 목록에 뜬다")
    void requestAppearsInGuardianQueue() throws Exception {
        createRequest();

        mvc.perform(get("/api/v1/app/approvals").header("Authorization", token("FWPAR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].studentName").value("신청학생"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("신청은 담당선생님 대기 목록에도 동시에 뜬다 (반배정에서 자동 결정)")
    void requestAppearsInTeacherQueue() throws Exception {
        createRequest();

        mvc.perform(get("/api/v1/admin/approvals").header("Authorization", token("FWT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].studentName").value("신청학생"));
    }

    @Test
    @DisplayName("★ 타임아웃 전 학부모 승인 → PARENT_IN_TIME")
    void parentApprovesInTime() throws Exception {
        long id = createRequest();

        mvc.perform(post("/api/v1/app/approvals/{id}/approve", id)
                        .header("Authorization", token("FWPAR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.resolutionCase").value("PARENT_IN_TIME"));
    }

    @Test
    @DisplayName("★ 타임아웃 전 담당선생님이 먼저 승인 → STAFF_BEFORE_TIMEOUT (문구가 달라야 함)")
    void teacherApprovesBeforeTimeout() throws Exception {
        long id = createRequest();

        mvc.perform(post("/api/v1/admin/approvals/{id}/approve", id)
                        .header("Authorization", token("FWT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionCase").value("STAFF_BEFORE_TIMEOUT"));
    }

    @Test
    @DisplayName("★ 한쪽이 승인하면 다른 쪽은 409 — 중복 승인 차단")
    void secondApprovalIsRejected() throws Exception {
        long id = createRequest();

        mvc.perform(post("/api/v1/app/approvals/{id}/approve", id)
                        .header("Authorization", token("FWPAR")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/approvals/{id}/approve", id)
                        .header("Authorization", token("FWT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_ALREADY_PROCESSED"));
    }

    @Test
    @DisplayName("해제 시간 상한(300분)을 넘으면 거부한다")
    void requestedMinutesUpperBound() throws Exception {
        mvc.perform(post("/api/v1/app/firewall/requests")
                        .header("Authorization", token("FWSTU"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedMinutes":301,"reason":"너무 김"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("학생은 승인할 수 없다")
    void studentCannotApprove() throws Exception {
        long id = createRequest();

        mvc.perform(post("/api/v1/app/approvals/{id}/approve", id)
                        .header("Authorization", token("FWSTU")))
                .andExpect(status().isForbidden());
    }
}
