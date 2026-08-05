package com.dlab.api.clazz;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 반 관리 · 배정.
 *
 * <p>핵심은 <b>반 배정이 승인 에스컬레이션으로 이어지는지</b>다 — 담임을 지정하고 학생을
 * 배정하면 그 학생의 방화벽 신청이 그 담임에게 가야 한다. 이게 끊기면 승인이 학부모 단독으로
 * 굴러가고 타임아웃 에스컬레이션이 무의미해진다.
 */
@SpringBootTest
@Transactional
class ClassAssignmentFlowTest {

    private static final String PASSWORD = "class-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    Long teacherId;
    Long enrollmentId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("CL01", "반테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Teacher teacher = new Teacher(academy, "담임쌤", "010-1000-0000");
        em.persist(teacher);

        // 반 관리는 지점 관리자 권한
        Employee admin = new Employee(academy, "행정쌤");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "CLADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);

        Student student = new Student("CLSTU01", "배정학생", "010-2000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, academy, (short) 2026, "0001", "RFC001", GradeType.N_SU);
        em.persist(enrollment);

        Account studentAccount = Account.forStudent(student, "CLSTU", passwordEncoder.encode(PASSWORD));
        studentAccount.approve();
        em.persist(studentAccount);

        em.persist(new ApprovalItem(academy, (short) 2026, RequestType.FIREWALL_UNLOCK,
                ApproverType.PARENT, (short) ApprovalItem.FIREWALL_TIMEOUT_MINUTES, ApproverType.TEACHER));
        em.flush();

        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        academyId = academy.getId();
        teacherId = teacher.getId();
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
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private long createClass(Long homeroomTeacherId) throws Exception {
        String homeroom = homeroomTeacherId == null ? "null" : homeroomTeacherId.toString();
        String body = mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"1반","classType":"FIXED","homeroomTeacherId":%s}"""
                                .formatted(academyId, homeroom)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("반을 만들고 담임을 지정한다")
    void createClassWithHomeroom() throws Exception {
        long classId = createClass(teacherId);

        mvc.perform(get("/api/v1/admin/classes").header("Authorization", token("CLADM"))
                        .param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(classId))
                .andExpect(jsonPath("$.data[0].homeroomTeacherName").value("담임쌤"));
    }

    @Test
    @DisplayName("같은 연도에 같은 이름의 반은 만들 수 없다")
    void duplicateClassNameRejected() throws Exception {
        createClass(teacherId);

        mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"1반","classType":"FIXED","homeroomTeacherId":null}"""
                                .formatted(academyId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("학생을 배정하면 반 명단에 뜬다")
    void assignStudent() throws Exception {
        long classId = createClass(teacherId);

        mvc.perform(post("/api/v1/admin/classes/{id}/students", classId)
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(enrollmentId)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/classes/{id}/students", classId)
                        .header("Authorization", token("CLADM")))
                .andExpect(jsonPath("$.data[0].studentName").value("배정학생"));
    }

    @Test
    @DisplayName("★ 반 배정이 승인 에스컬레이션 대상으로 이어진다")
    void assignmentDrivesApprovalEscalation() throws Exception {
        long classId = createClass(teacherId);
        mvc.perform(post("/api/v1/admin/classes/{id}/students", classId)
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(enrollmentId)))
                .andExpect(status().isOk());

        // 학생이 방화벽 해제를 신청하면, 배정된 반의 담임이 에스컬레이션 대상이 돼야 한다
        mvc.perform(post("/api/v1/app/firewall/requests")
                        .header("Authorization", token("CLSTU"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedMinutes":60,"reason":"인강"}"""))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        Long escalationTeacherId = em.createQuery("""
                        SELECT r.escalationTeacher.id FROM ApprovalRequest r
                        WHERE r.enrollment.id = :id
                        """, Long.class)
                .setParameter("id", enrollmentId)
                .getSingleResult();

        org.assertj.core.api.Assertions.assertThat(escalationTeacherId).isEqualTo(teacherId);
    }

    @Test
    @DisplayName("★ 재배정하면 이전 배정은 이력으로 남고 현재는 하나만 유지된다")
    void reassignKeepsHistory() throws Exception {
        long first = createClass(teacherId);
        mvc.perform(post("/api/v1/admin/classes/{id}/students", first)
                .header("Authorization", token("CLADM"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"enrollmentId":%d}""".formatted(enrollmentId)));

        // 2반 신설 후 재배정
        String body = mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"2반","classType":"FIXED","homeroomTeacherId":null}"""
                                .formatted(academyId)))
                .andReturn().getResponse().getContentAsString();
        long second = objectMapper.readTree(body).path("data").path("id").asLong();

        mvc.perform(post("/api/v1/admin/classes/{id}/students", second)
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(enrollmentId)))
                .andExpect(status().isOk());
        em.flush();

        Long activeCount = em.createQuery("""
                        SELECT COUNT(a) FROM ClassAssignment a
                        WHERE a.enrollment.id = :id AND a.active = true
                        """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        Long totalCount = em.createQuery("""
                        SELECT COUNT(a) FROM ClassAssignment a WHERE a.enrollment.id = :id
                        """, Long.class).setParameter("id", enrollmentId).getSingleResult();

        // 현재 배정은 1개, 이력은 남아 있어야 한다 — 덮어쓰면 작년 반을 알 수 없다
        org.assertj.core.api.Assertions.assertThat(activeCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(totalCount).isEqualTo(2);
    }

    @Test
    @DisplayName("권한 없는 계정은 반을 만들 수 없다")
    void studentCannotCreateClass() throws Exception {
        mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", token("CLSTU"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"몰래반","classType":"FIXED","homeroomTeacherId":null}"""
                                .formatted(academyId)))
                .andExpect(status().isForbidden());
    }
}
