package com.dlab.api.student;

import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.master.entity.Scholarship;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학생 목록 응답 보강 — 화면이 쓰는 필드가 실제로 채워지는가.
 *
 * <p>여기서 지키는 것은 셋이다.
 * <ol>
 *   <li><b>다른 테이블 값</b>(지점·반·담임·좌석·장학)이 목록에 실려 나온다 —
 *       배정된 학생과 안 된 학생 <b>둘 다</b> 확인한다. 미배정이 오류가 되면 신규 접수생이
 *       목록에서 사라진다</li>
 *   <li><b>새 검색 조건</b>(담임·출신학교·등원일)이 실제로 걸린다</li>
 *   <li><b>★ N+1이 없다</b> — 학생이 늘어도 쿼리 수가 늘지 않는다. 이 화면은 재원생
 *       전체(수백 명)가 대상이라 행마다 조회하면 목록 한 번에 수백 쿼리가 나간다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class StudentListFieldsTest {

    private static final String PASSWORD = "student-list-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Teacher homeroom;
    ClassMaster classMaster;
    StudyArea studyArea;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("SL01", "목록테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "행정쌤");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "SLADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        homeroom = new Teacher(academy, "김담임", "010-1111-2222");
        em.persist(homeroom);
        classMaster = new ClassMaster(academy, (short) 2026, "가온반", ClassType.FIXED, homeroom);
        em.persist(classMaster);

        studyArea = new StudyArea(academy, "A", "A구역", (short) 1);
        em.persist(studyArea);
        em.flush();
    }

    private void grantRole(Long accountId, String roleName) {
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :roleName
                        """)
                .setParameter("accountId", accountId).setParameter("roleName", roleName)
                .executeUpdate();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"SLADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    /** 사람 + 등록 건. 학번은 호출자가 준다 — 채번 경로를 타지 않아야 테스트가 순서에 안 흔들린다. */
    private StudentEnrollment student(String name, String studentNo, String schoolName,
                                      LocalDate birthDate, LocalDate admissionDate) {
        Student s = new Student("SL" + studentNo.replace("-", ""), name, "010-3333-4444");
        s.updateProfile(null, null, birthDate, "M", schoolName, "서울시 강남구 역삼동 12-3");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(
                s, academy, (short) 2026, studentNo, null, GradeType.N_SU);
        e.recordAdmission(admissionDate);
        em.persist(e);
        return e;
    }

    private void assignClass(StudentEnrollment e) {
        em.persist(new ClassAssignment(academy, e, classMaster, ClassType.FIXED));
    }

    private void assignSeat(StudentEnrollment e, String seatCd) {
        SeatMaster seat = new SeatMaster(academy, studyArea, seatCd, seatCd + "번", 1, 1);
        em.persist(seat);
        em.persist(new SeatAssignment(academy, seat, e));
    }

    private void grantScholarship(StudentEnrollment e, String type) {
        em.persist(new Scholarship(academy, e, type, new BigDecimal("30.00")));
    }

    @Test
    @DisplayName("★ 목록에 지점·반·담임·좌석·장학·출신학교가 함께 나온다")
    void listCarriesJoinedFields() throws Exception {
        StudentEnrollment e = student("배정된학생", "2026-0001", "대성고등학교",
                LocalDate.of(2007, 3, 15), LocalDate.of(2026, 3, 2));
        assignClass(e);
        assignSeat(e, "A-01");
        grantScholarship(e, "KICE_50");
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "배정된"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].academyName").value("목록테스트지점"))
                .andExpect(jsonPath("$.data[0].className").value("가온반"))
                .andExpect(jsonPath("$.data[0].homeroomTeacher").value("김담임"))
                .andExpect(jsonPath("$.data[0].seatCd").value("A-01"))
                .andExpect(jsonPath("$.data[0].scholarshipTypes[0]").value("KICE_50"))
                .andExpect(jsonPath("$.data[0].schoolName").value("대성고등학교"))
                .andExpect(jsonPath("$.data[0].admissionDate").value("2026-03-02"));
    }

    @Test
    @DisplayName("★ 반·좌석·장학이 없는 학생도 목록에서 빠지지 않는다 — 미배정은 오류가 아니다")
    void unassignedStudentStillListed() throws Exception {
        student("미배정학생", "2026-0002", null, null, LocalDate.of(2026, 3, 2));
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "미배정"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("미배정학생"))
                .andExpect(jsonPath("$.data[0].className").doesNotExist())
                .andExpect(jsonPath("$.data[0].homeroomTeacher").doesNotExist())
                .andExpect(jsonPath("$.data[0].seatCd").doesNotExist())
                // 장학은 빈 목록이다 — null이면 화면이 매번 방어해야 한다
                .andExpect(jsonPath("$.data[0].scholarshipTypes").isEmpty());
    }

    @Test
    @DisplayName("반은 배정됐지만 담임이 없는 반이면 담임만 비어 나온다")
    void classWithoutHomeroomTeacher() throws Exception {
        ClassMaster noTeacher = new ClassMaster(academy, (short) 2026, "담임없는반",
                ClassType.FIXED, null);
        em.persist(noTeacher);
        StudentEnrollment e = student("담임없는학생", "2026-0003", null, null,
                LocalDate.of(2026, 3, 2));
        em.persist(new ClassAssignment(academy, e, noTeacher, ClassType.FIXED));
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "담임없는"))
                .andExpect(jsonPath("$.data[0].className").value("담임없는반"))
                .andExpect(jsonPath("$.data[0].homeroomTeacher").doesNotExist());
    }

    // ── 새 검색 조건 ──

    @Test
    @DisplayName("담임으로 검색하면 그 담임 반 학생만 나온다")
    void searchByTeacher() throws Exception {
        StudentEnrollment mine = student("우리반학생", "2026-0011", null, null,
                LocalDate.of(2026, 3, 2));
        assignClass(mine);
        student("남의반학생", "2026-0012", null, null, LocalDate.of(2026, 3, 2));
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026")
                        .param("teacherId", String.valueOf(homeroom.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("우리반학생"));
    }

    @Test
    @DisplayName("출신학교는 부분 일치다 — '대성'으로 '대성고등학교'가 걸린다")
    void searchBySchoolName() throws Exception {
        student("대성출신", "2026-0021", "대성고등학교", null, LocalDate.of(2026, 3, 2));
        student("타교출신", "2026-0022", "한빛고등학교", null, LocalDate.of(2026, 3, 2));
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("schoolName", "대성"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("대성출신"));
    }

    @Test
    @DisplayName("★ 등원일 범위는 한쪽만 넣어도 걸린다 — 경계일은 포함이다")
    void searchByAdmissionDateRange() throws Exception {
        student("3월생", "2026-0031", null, null, LocalDate.of(2026, 3, 2));
        student("5월생", "2026-0032", null, null, LocalDate.of(2026, 5, 10));
        em.flush();

        // 양쪽 — 경계(3/2)가 포함돼야 한다
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026")
                        .param("admittedFrom", "2026-03-02").param("admittedTo", "2026-03-31"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("3월생"));

        // 시작만
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("admittedFrom", "2026-04-01"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("5월생"));

        // 끝만
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("admittedTo", "2026-04-01"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("3월생"));
    }

    // ── 응답 형태 ──

    @Test
    @DisplayName("★ 목록 응답은 다른 목록과 같은 { data: [...], meta: {...} } 형태다")
    void listResponseShapeMatchesOtherLists() throws Exception {
        student("형태확인", "2026-0041", null, null, LocalDate.of(2026, 3, 2));
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "형태확인"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                // 옛 형태가 남아 있으면 클라이언트가 둘 다 흡수해야 한다
                .andExpect(jsonPath("$.data.content").doesNotExist());
    }

    // ── 개인정보 ──

    @Test
    @DisplayName("★ 생년월일은 상위 관리자에게만 원본이 나간다 — 그 외에는 연도만")
    void birthDateIsMaskedForNonPrivilegedRoles() throws Exception {
        student("생일학생", "2026-0051", null, LocalDate.of(2007, 3, 15),
                LocalDate.of(2026, 3, 2));
        em.flush();

        // BRANCH_ADMIN = 상위 관리자
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "생일학생"))
                .andExpect(jsonPath("$.data[0].birthDate").value("2007-03-15"))
                .andExpect(jsonPath("$.data[0].masked").value(false));

        // STAFF — 월일이 남으면 주민번호 앞자리가 복원된다
        Employee staff = new Employee(academy, "행정보조");
        em.persist(staff);
        Account staffAccount =
                Account.forEmployee(staff, "SLSTF", passwordEncoder.encode(PASSWORD));
        em.persist(staffAccount);
        em.flush();
        grantRole(staffAccount.getId(), "STAFF");

        String staffBody = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"SLSTF","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        String staffToken = "Bearer "
                + objectMapper.readTree(staffBody).path("data").path("accessToken").asString();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", staffToken)
                        .param("year", "2026").param("keyword", "생일학생"))
                .andExpect(jsonPath("$.data[0].birthDate").value("2007-**-**"))
                .andExpect(jsonPath("$.data[0].phone").value("010-****-4444"))
                // 화면이 마스킹 여부를 알아야 "번호가 잘못 저장됐다"는 오인 문의가 안 생긴다
                .andExpect(jsonPath("$.data[0].masked").value(true));
    }

    // ── N+1 ──

    @Test
    @DisplayName("★★ 학생이 늘어도 목록 조회 쿼리 수가 늘지 않는다 (N+1 방지)")
    void listQueryCountDoesNotGrowWithStudents() throws Exception {
        String token = token();

        // 1명
        StudentEnrollment one = student("한명", "2026-0101", "한빛고", null,
                LocalDate.of(2026, 3, 2));
        assignClass(one);
        assignSeat(one, "B-01");
        grantScholarship(one, "KICE_50");
        em.flush();
        long withOne = countQueries(token);

        // 9명 더 — 전부 반·좌석·장학까지 붙인다. 행마다 조회하면 여기서 27쿼리가 더 나간다
        for (int i = 2; i <= 10; i++) {
            StudentEnrollment e = student("여러명" + i, "2026-01%02d".formatted(i), "한빛고",
                    null, LocalDate.of(2026, 3, 2));
            assignClass(e);
            assignSeat(e, "B-%02d".formatted(i));
            grantScholarship(e, "KICE_50");
        }
        em.flush();
        long withTen = countQueries(token);

        assertThat(withTen)
                .as("학생 1명일 때 %d쿼리, 10명일 때 %d쿼리 — 늘어나면 행마다 조회하고 있다",
                        withOne, withTen)
                .isEqualTo(withOne);
    }

    /**
     * 목록 한 번에 나가는 쿼리 수. 통계는 매번 초기화한다 — 앞선 호출이 섞이면 값이 무의미하다.
     *
     * <p>통계를 <b>런타임에 켠다.</b> {@code @SpringBootTest(properties = ...)}로 켜면
     * 이 테스트만의 Spring 컨텍스트가 하나 더 생기고, 컨텍스트마다 커넥션 풀이 딸려 와
     * 전체 스위트에서 <b>DB 커넥션이 고갈된다</b>({@code too many clients already}).
     */
    private long countQueries(String token) throws Exception {
        Statistics statistics = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        em.flush();
        em.clear();
        statistics.clear();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026").param("size", "100"))
                .andExpect(status().isOk());

        return statistics.getPrepareStatementCount();
    }
}
