package com.dlab.api.clazz;

import com.dlab.domain.approval.entity.*;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.service.ClassService;
import com.dlab.api.admin.clazz.ClassResponse;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
    @Autowired ClassService classService;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    Long teacherId;
    Long enrollmentId;
    Academy academy;
    AuthPrincipal principal;
    int seq;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("CL01", "반테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Teacher teacher = new Teacher(academy, "담임쌤", "010-1000-0000");
        em.persist(teacher);

        // 반 관리는 지점 관리자 권한
        Employee admin = new Employee(academy, "행정쌤");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "CLADM", passwordEncoder.encode(PASSWORD), false);
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
        principal = AuthPrincipal.of(0L, "EMPLOYEE", academyId, java.util.List.of(Role.BRANCH_ADMIN), false);
    }

    /** 학생 한 명(사람 + 등록 건)을 만든다. 학번·고유코드는 겹치면 안 되므로 순번을 붙인다. */
    private StudentEnrollment newEnrollment(String name, GradeType grade, TrackType track,
                                            String schoolName) {
        seq++;
        Student student = new Student("CLX%03d".formatted(seq), name, "010-3000-%04d".formatted(seq));
        student.updateProfile(null, null, null, null, schoolName, null);
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, academy, (short) 2026, "10%02d".formatted(seq), "RFX%03d".formatted(seq), grade);
        enrollment.changeTrack(track);
        em.persist(enrollment);
        return enrollment;
    }

    /** 좌석을 만들어 배정한다. */
    private void assignSeat(StudentEnrollment enrollment, String seatCd) {
        StudyArea area = new StudyArea(academy, "A" + seq, "구역" + seq, (short) 1);
        em.persist(area);
        SeatMaster seat = new SeatMaster(academy, area, seatCd, seatCd + "번", 0, 0);
        em.persist(seat);
        em.persist(new SeatAssignment(academy, seat, enrollment));
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
                                {"academyId":%d,"year":2026,"name":"1반","classType":"FIXED","homeroomTeacherId":%s,"capacity":2}"""
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

    @Test
    @DisplayName("★ 명단에 계열·출신학교·학년·좌석·지점명이 실린다 — 좌석 미배정은 null")
    void memberCarriesScreenFields() throws Exception {
        long classId = createClass(teacherId);

        StudentEnrollment seated = newEnrollment("좌석있음", GradeType.N_SU, TrackType.SCIENCE, "대성고");
        StudentEnrollment unseated = newEnrollment("좌석없음", GradeType.HIGH3, TrackType.HUMANITIES, "분당고");
        assignSeat(seated, "A-01");
        em.flush();

        classService.assignStudent(classId, seated.getId(), principal);
        classService.assignStudent(classId, unseated.getId(), principal);
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/classes/{id}/students", classId)
                        .header("Authorization", token("CLADM")))
                .andExpect(status().isOk())
                // 학번 오름차순이라 먼저 만든 쪽이 앞이다
                .andExpect(jsonPath("$.data[0].studentName").value("좌석있음"))
                .andExpect(jsonPath("$.data[0].grade").value("N_SU"))
                .andExpect(jsonPath("$.data[0].track").value("SCIENCE"))
                .andExpect(jsonPath("$.data[0].schoolName").value("대성고"))
                .andExpect(jsonPath("$.data[0].seatCd").value("A-01"))
                .andExpect(jsonPath("$.data[0].academyName").value("반테스트지점"))
                .andExpect(jsonPath("$.data[0].academyId").value(academyId))
                // 좌석이 없으면 빈 문자열이 아니라 null이다
                .andExpect(jsonPath("$.data[1].studentName").value("좌석없음"))
                .andExpect(jsonPath("$.data[1].track").value("HUMANITIES"))
                .andExpect(jsonPath("$.data[1].seatCd").doesNotExist());
    }

    @Test
    @DisplayName("★ 명단 쿼리 수가 학생 수에 비례하지 않는다 (N+1 방지)")
    void memberListDoesNotScaleWithStudentCount() throws Exception {
        long small = createClass(null);
        long large = createClass2("3반");

        StudentEnrollment one = newEnrollment("한명", GradeType.N_SU, TrackType.SCIENCE, "가고");
        assignSeat(one, "B-01");
        classService.assignStudent(small, one.getId(), principal);

        for (int i = 0; i < 5; i++) {
            StudentEnrollment e = newEnrollment("여럿" + i, GradeType.N_SU, TrackType.SCIENCE, "나고");
            assignSeat(e, "C-0" + i);
            classService.assignStudent(large, e.getId(), principal);
        }
        em.flush();

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        em.clear();
        stats.clear();
        classService.studentsOf(small, principal).forEach(v -> touch(v));
        long oneStudent = stats.getPrepareStatementCount();

        em.clear();
        stats.clear();
        classService.studentsOf(large, principal).forEach(v -> touch(v));
        long fiveStudents = stats.getPrepareStatementCount();

        // 통계가 꺼져 0/0으로 통과하는 것을 막는다
        org.assertj.core.api.Assertions.assertThat(oneStudent).isPositive();
        // 학생이 5배가 돼도 쿼리 수는 그대로다 — 좌석을 행마다 조회하면 여기서 벌어진다
        org.assertj.core.api.Assertions.assertThat(fiveStudents).isEqualTo(oneStudent);
    }

    /** 응답 조립과 같은 접근을 해서 지연 로딩이 숨어 있지 않은지 함께 본다. */
    private void touch(ClassService.ClassMemberView view) {
        ClassResponse.Member.from(view);
    }

    // ── 4-3 배정 해제 · 4-4 정원/인원수 · 4-1 일괄 배정 ──

    @Test
    @DisplayName("★ 반 배정을 해제하면 명단에서 빠지고 이력은 남는다")
    void releaseStudentFromClass() throws Exception {
        long classId = createClass(teacherId);
        classService.assignStudent(classId, enrollmentId, principal);
        em.flush();

        mvc.perform(delete("/api/v1/admin/classes/{classId}/students/{enrollmentId}",
                        classId, enrollmentId)
                        .header("Authorization", token("CLADM")))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/classes/{id}/students", classId)
                        .header("Authorization", token("CLADM")))
                .andExpect(jsonPath("$.data").isEmpty());

        // 행을 지우지 않는다 — 배정은 이력이다
        Long total = em.createQuery("""
                        SELECT COUNT(a) FROM ClassAssignment a WHERE a.enrollment.id = :id
                        """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        org.assertj.core.api.Assertions.assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("배정되지 않은 학생을 해제하면 404다")
    void releaseUnassignedStudentIsNotFound() throws Exception {
        long classId = createClass(teacherId);

        mvc.perform(delete("/api/v1/admin/classes/{classId}/students/{enrollmentId}",
                        classId, enrollmentId)
                        .header("Authorization", token("CLADM")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("★ 목록에 정원과 현재 인원이 실린다 — 배정 없는 반은 0")
    void listCarriesCapacityAndMemberCount() throws Exception {
        long classId = createClass(teacherId);
        long empty = createClass2("빈반");
        classService.assignStudent(classId, enrollmentId, principal);
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/classes").header("Authorization", token("CLADM"))
                        .param("year", "2026"))
                .andExpect(status().isOk())
                // 정렬은 DB 콜레이션에 달렸으므로 순서가 아니라 id로 찾는다
                .andExpect(jsonPath("$.data[?(@.id == %d)].capacity".formatted(classId))
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.data[?(@.id == %d)].memberCount".formatted(classId))
                        .value(org.hamcrest.Matchers.contains(1)))
                // 배정이 없어도 비지 않는다 — 집계에 행이 없을 뿐 인원은 0이다
                .andExpect(jsonPath("$.data[?(@.id == %d)].memberCount".formatted(empty))
                        .value(org.hamcrest.Matchers.contains(0)));
    }

    @Test
    @DisplayName("정원을 수정할 수 있고, 비우려면 clearCapacity를 켠다")
    void updateCapacity() throws Exception {
        long classId = createClass(teacherId);

        mvc.perform(put("/api/v1/admin/classes/{id}", classId)
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capacity":14}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(14));

        mvc.perform(put("/api/v1/admin/classes/{id}", classId)
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clearCapacity":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").doesNotExist());
    }

    @Test
    @DisplayName("★ 일괄 배정 — 정상 건은 배정되고 실패 건은 건별로 사유가 나온다")
    void bulkAssignReportsPerItem() throws Exception {
        long classId = createClass(teacherId);
        StudentEnrollment first = newEnrollment("일괄1", GradeType.N_SU, TrackType.SCIENCE, "가고");
        StudentEnrollment second = newEnrollment("일괄2", GradeType.N_SU, TrackType.SCIENCE, "가고");
        em.flush();

        // 999999는 없는 등록 건이다 — 이 한 건 때문에 나머지가 되돌아가면 안 된다
        String body = mvc.perform(post("/api/v1/admin/classes/{id}/students/bulk", classId)
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentIds":[%d,%d,999999,%d]}"""
                                .formatted(first.getId(), second.getId(), first.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.results[1].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.results[2].status").value("FAILED"))
                // 같은 요청에 두 번 들어온 건은 건너뛴다 — 이력에 의미 없는 줄이 남지 않게
                .andExpect(jsonPath("$.data.results[3].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.memberCount").value(2))
                .andReturn().getResponse().getContentAsString();

        // 실패 건이 있어도 성공분은 실제로 저장된다
        org.assertj.core.api.Assertions.assertThat(
                objectMapper.readTree(body).path("data").path("overCapacity").asBoolean()).isFalse();
        em.flush();
        em.clear();

        mvc.perform(get("/api/v1/admin/classes/{id}/students", classId)
                        .header("Authorization", token("CLADM")))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("★ 정원을 넘겨도 배정은 되고 overCapacity로 알린다 — 초과가 필요한 운영이 있다")
    void bulkAssignAllowsOverCapacity() throws Exception {
        long classId = createClass(teacherId); // 정원 2
        StudentEnrollment a = newEnrollment("초과1", GradeType.N_SU, TrackType.SCIENCE, "가고");
        StudentEnrollment b = newEnrollment("초과2", GradeType.N_SU, TrackType.SCIENCE, "가고");
        StudentEnrollment c = newEnrollment("초과3", GradeType.N_SU, TrackType.SCIENCE, "가고");
        em.flush();

        mvc.perform(post("/api/v1/admin/classes/{id}/students/bulk", classId)
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentIds":[%d,%d,%d]}"""
                                .formatted(a.getId(), b.getId(), c.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedCount").value(3))
                .andExpect(jsonPath("$.data.memberCount").value(3))
                .andExpect(jsonPath("$.data.overCapacity").value(true));
    }

    @Test
    @DisplayName("★ 반 목록 쿼리 수가 반 개수에 비례하지 않는다 (N+1 방지)")
    void classListDoesNotScaleWithClassCount() throws Exception {
        // 반 1개 + 학생 1명
        long only = createClass(teacherId);
        StudentEnrollment one = newEnrollment("한명", GradeType.N_SU, TrackType.SCIENCE, "가고");
        classService.assignStudent(only, one.getId(), principal);
        em.flush();

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        SearchScope scope = SearchScope.of(principal, 2026);
        em.clear();
        stats.clear();
        classService.searchWithMemberCount(scope).forEach(this::touchClass);
        long oneClass = stats.getPrepareStatementCount();

        // 반을 4개 더 만들고 학생도 붙인다
        for (int i = 0; i < 4; i++) {
            long extra = createClass2("추가" + i + "반");
            StudentEnrollment e = newEnrollment("추가학생" + i, GradeType.N_SU, TrackType.SCIENCE, "나고");
            classService.assignStudent(extra, e.getId(), principal);
        }
        em.flush();

        em.clear();
        stats.clear();
        classService.searchWithMemberCount(scope).forEach(this::touchClass);
        long fiveClasses = stats.getPrepareStatementCount();

        org.assertj.core.api.Assertions.assertThat(oneClass).isPositive();
        // 반이 5배가 돼도 쿼리 수는 그대로다 — 반마다 인원을 세면 여기서 벌어진다
        org.assertj.core.api.Assertions.assertThat(fiveClasses).isEqualTo(oneClass);
    }

    /** 응답 조립과 같은 접근을 해서 지연 로딩이 숨어 있지 않은지 함께 본다. */
    private void touchClass(ClassService.ClassSummaryView view) {
        ClassResponse.from(view);
    }

    private long createClass2(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", token("CLADM"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"%s","classType":"FIXED","homeroomTeacherId":null}"""
                                .formatted(academyId, name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }
}
