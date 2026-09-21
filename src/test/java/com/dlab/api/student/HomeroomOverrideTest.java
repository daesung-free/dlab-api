package com.dlab.api.student;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.consult.service.ConsultService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.service.ClassService;
import com.dlab.domain.user.service.HomeroomOverrideService;
import com.dlab.domain.user.service.HomeroomResolver;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담임 예외 지정.
 *
 * <p>지키려는 것 — <b>학생 기준인 곳은 예외 담임을 볼 것</b>(승인 이양·상담 담당),
 * <b>반 담임은 그대로일 것</b>, <b>반을 옮기면 풀릴 것</b>, <b>사유 없이·담임이 스스로 바꾸지 못할 것</b>.
 */
@SpringBootTest
@Transactional
class HomeroomOverrideTest {

    @Autowired HomeroomOverrideService overrideService;
    @Autowired HomeroomResolver resolver;
    @Autowired ClassService classService;
    @Autowired ConsultService consultService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Teacher classHomeroom;
    Teacher overrideTeacher;
    ClassMaster class1;
    ClassMaster class2;
    StudentEnrollment student;
    AuthPrincipal branchAdmin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        classHomeroom = new Teacher(bundang, "김반담임", "010-0000-0001");
        overrideTeacher = new Teacher(bundang, "이예외", "010-0000-0002");
        em.persist(classHomeroom);
        em.persist(overrideTeacher);
        class1 = new ClassMaster(bundang, (short) 2085, "N수 1반", ClassType.FIXED, classHomeroom);
        class2 = new ClassMaster(bundang, (short) 2085, "N수 2반", ClassType.FIXED, overrideTeacher);
        em.persist(class1);
        em.persist(class2);
        Student s = new Student("DL-H1", "예외학생", "010-3333-0000");
        em.persist(s);
        student = new StudentEnrollment(s, bundang, (short) 2085, "2085-0001", null, GradeType.N_SU);
        em.persist(student);
        em.flush();

        branchAdmin = new AuthPrincipal(1L, "branch", bundang.getId(), Set.of(Role.BRANCH_ADMIN), false, false);
        classService.assignStudent(class1.getId(), student.getId(), branchAdmin);
        em.flush();
    }

    @Test
    @DisplayName("예외 지정이 없으면 반 담임이다")
    void defaultsToClassHomeroom() {
        assertThat(resolver.of(student)).isEqualTo(classHomeroom);
    }

    @Test
    @DisplayName("★ 예외 지정하면 그 학생의 담임(승인 이양 대상)이 바뀐다 — 반 담임은 그대로다")
    void overrideChangesStudentHomeroomOnly() {
        overrideService.override(branchAdmin, student.getId(), overrideTeacher.getId(), "학부모 요청");
        em.flush();

        assertThat(resolver.of(student)).isEqualTo(overrideTeacher);
        // ★ 반 담임은 그대로 — 반공지·반설문 권한은 반 기준이라 예외 학생 때문에 넓어지면 안 된다
        assertThat(em.find(ClassMaster.class, class1.getId()).getHomeroomTeacher()).isEqualTo(classHomeroom);
    }

    @Test
    @DisplayName("★ 상담 현황을 담임으로 거르면 예외 지정된 학생은 지정된 선생님 쪽에 나온다")
    void consultFilterFollowsOverride() {
        overrideService.override(branchAdmin, student.getId(), overrideTeacher.getId(), "학부모 요청");
        em.flush();

        assertThat(consultService.status(branchAdmin, bundang.getId(), overrideTeacher.getId()))
                .extracting(r -> r.enrollmentId()).contains(student.getId());
        assertThat(consultService.status(branchAdmin, bundang.getId(), classHomeroom.getId()))
                .extracting(r -> r.enrollmentId()).doesNotContain(student.getId());
    }

    @Test
    @DisplayName("★★ 반을 옮기면 예외가 풀린다 — 남겨두면 옛 담임이 조용히 따라다닌다")
    void classChangeClearsOverride() {
        overrideService.override(branchAdmin, student.getId(), classHomeroom.getId(), "임시");
        em.flush();

        classService.assignStudent(class2.getId(), student.getId(), branchAdmin);
        em.flush();

        assertThat(student.getHomeroomOverride()).isNull();
        assertThat(resolver.of(student)).isEqualTo(overrideTeacher);   // 2반 담임
    }

    @Test
    @DisplayName("사유 없이는 지정할 수 없다 — 권한이 따라 움직이는 값이다")
    void requiresReason() {
        assertThatThrownBy(() -> overrideService.override(branchAdmin, student.getId(),
                overrideTeacher.getId(), " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("사유");
    }

    @Test
    @DisplayName("★ 담임은 스스로 지정할 수 없다 — 학생을 넘기거나 가져오면 책임 소재가 흐려진다")
    void teacherCannotOverride() {
        AuthPrincipal teacher = new AuthPrincipal(2L, "t", bundang.getId(), Set.of(Role.TEACHER), false, false);

        assertThatThrownBy(() -> overrideService.override(teacher, student.getId(),
                overrideTeacher.getId(), "제가 맡을게요"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다른 지점 선생님에게는 맡길 수 없다")
    void rejectsOtherBranchTeacher() {
        Academy mokdong = new Academy("45", "목동", LocalTime.of(9, 0));
        em.persist(mokdong);
        Teacher other = new Teacher(mokdong, "박목동", "010-0000-0003");
        em.persist(other);
        em.flush();

        assertThatThrownBy(() -> overrideService.override(branchAdmin, student.getId(),
                other.getId(), "착오"))
                .hasMessageContaining("다른 지점");
    }

    @Test
    @DisplayName("해제하면 반 담임으로 돌아간다")
    void clearRestoresClassHomeroom() {
        overrideService.override(branchAdmin, student.getId(), overrideTeacher.getId(), "학부모 요청");
        overrideService.clear(branchAdmin, student.getId());
        em.flush();

        assertThat(resolver.of(student)).isEqualTo(classHomeroom);
    }
}
