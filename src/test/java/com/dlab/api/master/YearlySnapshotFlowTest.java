package com.dlab.api.master;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.master.entity.AdmissionType;
import com.dlab.domain.master.entity.CourseType;
import com.dlab.domain.master.entity.Curriculum;
import com.dlab.domain.master.entity.DepartmentMaster;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
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
 * 전년도 복사 (F-4.10-1).
 *
 * <p>핵심은 "몇 건 복사됐나"가 아니라 <b>복사된 행이 새 연도를 가리키느냐</b>다 —
 * 상벌점 규칙이 옛 연도 항목을 그대로 참조하면 다음 해에 조용히 망가진다.
 */
@SpringBootTest
@Transactional
class YearlySnapshotFlowTest {

    private static final String PASSWORD = "snapshot-test-password-1234";
    private static final short FROM = 2026;
    private static final short TO = 2027;

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Long academyId;
    Long oldPenaltyItemId;
    Long oldCourseTypeId;
    Long oldClassId;
    Teacher activeTeacher;
    Teacher resignedTeacher;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("YS01", "복사테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "상위관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "YSADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "SUPER_ADMIN");

        seedSourceYear();
        academyId = academy.getId();
    }

    /** 2026년 기초 데이터를 만든다. 담임은 재직자·퇴사자 각각 하나씩 둔다. */
    private void seedSourceYear() {
        activeTeacher = new Teacher(academy, "재직담임", "010-1111-1111");
        em.persist(activeTeacher);
        resignedTeacher = new Teacher(academy, "퇴사담임", "010-2222-2222");
        resignedTeacher.resign(LocalDate.of(2026, 12, 31));
        em.persist(resignedTeacher);

        em.persist(new DepartmentMaster(academy, FROM, "자연계열"));
        em.persist(new AdmissionType(academy, FROM, "일반전형", (short) 1));

        CourseType courseType = new CourseType(academy, FROM, "종합반", (short) 1);
        em.persist(courseType);
        em.flush();
        oldCourseTypeId = courseType.getId();

        ClassMaster first = new ClassMaster(academy, FROM, "1반", ClassType.FIXED, activeTeacher);
        first.assignCourseType(courseType);
        em.persist(first);
        em.flush();
        oldClassId = first.getId();
        em.persist(new Curriculum(academy, FROM, "수학 심화", first, (short) 1));
        em.persist(new ClassMaster(academy, FROM, "2반", ClassType.FIXED, resignedTeacher));
        em.persist(new ApprovalItem(academy, FROM, RequestType.FIREWALL_UNLOCK,
                ApproverType.PARENT, (short) ApprovalItem.FIREWALL_TIMEOUT_MINUTES, ApproverType.TEACHER));

        PenaltyItem item = new PenaltyItem(academy, FROM, "지각", -5, PenaltyCategory.DEMERIT);
        em.persist(item);
        PenaltyRule rule = new PenaltyRule(academy, FROM, PenaltyTriggerType.ATTENDANCE, "LATE", item);
        rule.activate();
        em.persist(rule);

        em.createNativeQuery("""
                        INSERT INTO period_master (academy_id, year, period_no, name, start_time, end_time)
                        VALUES (:academyId, :year, 1, '1교시', TIME '09:00', TIME '10:00')
                        """)
                .setParameter("academyId", academy.getId()).setParameter("year", FROM)
                .executeUpdate();

        em.flush();
        oldPenaltyItemId = item.getId();
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
                                {"loginId":"YSADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private org.springframework.test.web.servlet.ResultActions copy(short from, short to) throws Exception {
        return mvc.perform(post("/api/v1/admin/masters/yearly-copy")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"academyId":%d,"fromYear":%d,"toYear":%d}""".formatted(academyId, from, to)));
    }

    @Test
    @DisplayName("전년도 기초 데이터가 표별 건수와 함께 복사된다")
    void copiesEveryMaster() throws Exception {
        copy(FROM, TO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toYear").value((int) TO))
                .andExpect(jsonPath("$.data.copied.department").value(1))
                .andExpect(jsonPath("$.data.copied.admissionType").value(1))
                .andExpect(jsonPath("$.data.copied.courseType").value(1))
                .andExpect(jsonPath("$.data.copied.curriculum").value(1))
                .andExpect(jsonPath("$.data.copied.period").value(1))
                .andExpect(jsonPath("$.data.copied.class").value(2))
                .andExpect(jsonPath("$.data.copied.approvalItem").value(1))
                .andExpect(jsonPath("$.data.copied.penaltyItem").value(1))
                .andExpect(jsonPath("$.data.copied.penaltyRule").value(1));
    }

    @Test
    @DisplayName("★ 복사된 상벌점 규칙은 새 연도 항목을 가리킨다 (단순 INSERT SELECT였다면 실패)")
    void penaltyRuleReferenceIsRemapped() throws Exception {
        copy(FROM, TO).andExpect(status().isOk());
        em.flush();
        em.clear();

        PenaltyRule copied = em.createQuery("""
                SELECT r FROM PenaltyRule r JOIN FETCH r.penaltyItem
                WHERE r.academy.id = :academyId AND r.year = :year
                """, PenaltyRule.class)
                .setParameter("academyId", academyId).setParameter("year", TO)
                .getSingleResult();

        // 옛 항목을 그대로 참조하면 옛 연도를 손대는 순간 새 연도가 깨진다
        assertThat(copied.getPenaltyItem().getId()).isNotEqualTo(oldPenaltyItemId);
        assertThat(copied.getPenaltyItem().getYear()).isEqualTo(TO);
        assertThat(copied.getPenaltyItem().getItemName()).isEqualTo("지각");
    }

    @Test
    @DisplayName("★ 복사본은 copied_from_id로 원본을 가리킨다 — 신규 생성분과 구분할 유일한 근거(S-4)")
    void copiesAreTraceableToSource() throws Exception {
        copy(FROM, TO).andExpect(status().isOk());
        em.flush();
        em.clear();

        // 복사되는 6개 표 전부. 하나라도 NULL이면 그 표는 복사본 여부를 알 수 없다.
        for (String table : new String[]{"department_master", "class_master", "period_master",
                "approval_item", "penalty_item", "penalty_rule",
                "course_type", "admission_type", "curriculum"}) {
            Number untracked = (Number) em.createNativeQuery("""
                            SELECT COUNT(*) FROM %s
                            WHERE academy_id = :academyId AND year = :year AND copied_from_id IS NULL
                            """.formatted(table))
                    .setParameter("academyId", academyId).setParameter("year", TO)
                    .getSingleResult();
            assertThat(untracked.intValue())
                    .withFailMessage("%s에 원본을 못 가리키는 복사본이 있다", table)
                    .isZero();
        }

        // 원본(2026년)은 신규 생성분이므로 계속 NULL이어야 한다
        Number sourceTracked = (Number) em.createNativeQuery("""
                        SELECT COUNT(*) FROM department_master
                        WHERE academy_id = :academyId AND year = :year AND copied_from_id IS NOT NULL
                        """)
                .setParameter("academyId", academyId).setParameter("year", FROM)
                .getSingleResult();
        assertThat(sourceTracked.intValue()).isZero();
    }

    @Test
    @DisplayName("★ 복사된 반은 새 연도 과정을 가리킨다 — 반이 과정을 참조하는 두 번째 갈아끼움")
    void classCourseTypeIsRemapped() throws Exception {
        copy(FROM, TO).andExpect(status().isOk());
        em.flush();
        em.clear();

        ClassMaster copied = em.createQuery("""
                SELECT c FROM ClassMaster c JOIN FETCH c.courseType
                WHERE c.academy.id = :academyId AND c.year = :year AND c.name = '1반'
                """, ClassMaster.class)
                .setParameter("academyId", academyId).setParameter("year", TO)
                .getSingleResult();

        assertThat(copied.getCourseType().getId()).isNotEqualTo(oldCourseTypeId);
        assertThat(copied.getCourseType().getYear()).isEqualTo(TO);
        assertThat(copied.getCourseType().getName()).isEqualTo("종합반");
    }

    @Test
    @DisplayName("★ 복사된 커리큘럼은 새 연도 반을 가리킨다 — 세 번째 갈아끼움")
    void curriculumClassIsRemapped() throws Exception {
        copy(FROM, TO).andExpect(status().isOk());
        em.flush();
        em.clear();

        Curriculum copied = em.createQuery("""
                SELECT c FROM Curriculum c JOIN FETCH c.classMaster
                WHERE c.academy.id = :academyId AND c.year = :year
                """, Curriculum.class)
                .setParameter("academyId", academyId).setParameter("year", TO)
                .getSingleResult();

        assertThat(copied.getClassMaster().getId()).isNotEqualTo(oldClassId);
        assertThat(copied.getClassMaster().getYear()).isEqualTo(TO);
        assertThat(copied.getClassMaster().getName()).isEqualTo("1반");
    }

    @Test
    @DisplayName("★ 복사된 규칙은 꺼진 상태다 — 연도가 바뀌었다고 자동 부여가 켜지면 안 된다")
    void copiedRuleIsInactive() throws Exception {
        copy(FROM, TO).andExpect(status().isOk());
        em.flush();
        em.clear();

        PenaltyRule copied = em.createQuery("""
                SELECT r FROM PenaltyRule r WHERE r.academy.id = :academyId AND r.year = :year
                """, PenaltyRule.class)
                .setParameter("academyId", academyId).setParameter("year", TO)
                .getSingleResult();

        assertThat(copied.isActive()).isFalse();
    }

    @Test
    @DisplayName("★ 퇴사한 담임은 비운다 — 남겨두면 그 반의 승인 요청이 아무에게도 안 간다")
    void resignedHomeroomIsCleared() throws Exception {
        copy(FROM, TO).andExpect(status().isOk());
        em.flush();
        em.clear();

        var copiedClasses = em.createQuery("""
                SELECT c FROM ClassMaster c LEFT JOIN FETCH c.homeroomTeacher
                WHERE c.academy.id = :academyId AND c.year = :year ORDER BY c.name
                """, ClassMaster.class)
                .setParameter("academyId", academyId).setParameter("year", TO)
                .getResultList();

        assertThat(copiedClasses).hasSize(2);
        assertThat(copiedClasses.get(0).getHomeroomTeacher().getId()).isEqualTo(activeTeacher.getId());
        assertThat(copiedClasses.get(1).getHomeroomTeacher()).isNull();
    }

    @Test
    @DisplayName("★ 이미 데이터가 있는 연도로는 복사할 수 없다 — 두 번 돌면 기초 데이터가 두 벌이 된다")
    void rejectsNonEmptyTarget() throws Exception {
        copy(FROM, TO).andExpect(status().isOk());
        em.flush();

        copy(FROM, TO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SNAPSHOT_TARGET_NOT_EMPTY"));
    }

    @Test
    @DisplayName("원본과 대상 연도가 같으면 거부한다")
    void rejectsSameYear() throws Exception {
        copy(FROM, FROM).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 되돌릴 수 없는 대량 생성이라 지점 관리자는 실행할 수 없다")
    void branchAdminCannotCopy() throws Exception {
        Employee branchAdmin = new Employee(academy, "지점관리자");
        em.persist(branchAdmin);
        Account account = Account.forEmployee(branchAdmin, "YSBRC", passwordEncoder.encode(PASSWORD));
        em.persist(account);
        em.flush();
        grantRole(account.getId(), "BRANCH_ADMIN");

        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"YSBRC","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        String branchToken = "Bearer "
                + objectMapper.readTree(body).path("data").path("accessToken").asString();

        mvc.perform(post("/api/v1/admin/masters/yearly-copy")
                        .header("Authorization", branchToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"fromYear":%d,"toYear":%d}"""
                                .formatted(academyId, FROM, TO)))
                .andExpect(status().isForbidden());
    }
}
