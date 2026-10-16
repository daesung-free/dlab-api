package com.dlab.api.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.lecture.entity.LectureStatus;
import com.dlab.domain.lecture.entity.LectureType;
import com.dlab.domain.lecture.service.LectureService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.service.ClassService;
import com.dlab.domain.user.service.StaffAccountService;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 강사 (1016 피드백 2-13).
 *
 * <h2>★★ 지키려는 것 — 외부 강사가 담임으로 새지 않을 것</h2>
 * 외부 강사는 <b>로그인 계정이 없다.</b> 그런데 담임은 곧 <b>방화벽 승인 에스컬레이션
 * 대상</b>이라({@code ApprovalService.resolveEscalationTarget}), 로그인하지 못하는 사람이
 * 담임이면 <b>학부모가 응답하지 않을 때 넘길 사람이 없어 신청이 그대로 멈춘다.</b>
 *
 * <p>⚠️ 그리고 그 증상은 <b>신청이 들어온 뒤에야</b> 드러난다 — 담임을 지정하는 순간에는
 * 아무 오류도 나지 않는다. 그래서 지정 시점에 막는다.
 */
@SpringBootTest
@Transactional
class ExternalTeacherTest {

    @Autowired StaffAccountService staffAccountService;
    @Autowired ClassService classService;
    @Autowired LectureService lectureService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2095;

    Academy academy;
    AuthPrincipal admin;
    Teacher internal;

    @BeforeEach
    void setUp() {
        academy = new Academy("XT1", "외부강사테스트", LocalTime.of(9, 0));
        em.persist(academy);
        internal = new Teacher(academy, "내부선생님", "010-0000-0001");
        em.persist(internal);
        em.flush();
        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    private Teacher external() {
        Teacher t = staffAccountService.createExternalTeacher(academy.getId(),
                "외부강사", "010-0000-0002", "○○학원", admin);
        em.flush();
        return t;
    }

    @Test
    @DisplayName("★ 외부 강사는 계정 없이 사람만 만들어진다 — 소속이 남는다")
    void externalTeacherIsCreatedWithoutAccount() {
        Teacher t = external();

        assertThat(t.isExternal()).isTrue();
        assertThat(t.getAffiliation()).isEqualTo("○○학원");
        // 계정이 만들어지지 않았다 — 외부에 학생 정보 접근 계정을 만들지 않는 것이 요점이다
        Long accounts = em.createQuery(
                        "SELECT COUNT(a) FROM Account a WHERE a.teacher.id = :id", Long.class)
                .setParameter("id", t.getId()).getSingleResult();
        assertThat(accounts).isZero();
    }

    @Test
    @DisplayName("★★ 외부 강사를 반 담임으로 지정할 수 없다 — 승인을 넘겨받을 계정이 없다")
    void externalTeacherCannotBeHomeroom() {
        Teacher ext = external();
        var clazz = classService.create(academy.getId(), YEAR, "A반", ClassType.FIXED,
                internal.getId(), null, null, admin);
        em.flush();

        assertThatThrownBy(() ->
                classService.assignHomeroom(clazz.getId(), ext.getId(), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("외부 강사");

        // 내부 강사는 그대로 된다 — 막은 것은 외부뿐이다
        assertThatCode(() -> classService.assignHomeroom(clazz.getId(), internal.getId(), admin))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 외부 강사는 특강 담당으로는 세울 수 있다 — 그게 이 기능의 목적이다")
    void externalTeacherCanLeadLecture() {
        Teacher ext = external();
        var lecture = lectureService.create(academy.getId(), YEAR, LectureType.LECTURE,
                "외부강사 특강", null, admin);
        em.flush();

        lectureService.update(lecture.getId(), null, null, null, null, null, null, null, null,
                null, com.dlab.common.web.Patch.of(ext.getId()), null, admin);
        em.flush();

        assertThat(lecture.getTeacher().getId()).isEqualTo(ext.getId());
        // 담당이 있으므로 접수를 열 수 있다
        assertThatCode(() ->
                lectureService.changeStatus(lecture.getId(), LectureStatus.OPEN, admin))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 접수가 열린 특강은 담당 강사를 비울 수 없다 — 미지정인 채로 학생에게 열려 있었다")
    void openLectureCannotClearTeacher() {
        var lecture = lectureService.create(academy.getId(), YEAR, LectureType.LECTURE,
                "열린 특강", null, admin);
        lectureService.update(lecture.getId(), null, null, null, null, null, null, null, null,
                null, com.dlab.common.web.Patch.of(internal.getId()), null, admin);
        lectureService.changeStatus(lecture.getId(), LectureStatus.OPEN, admin);
        em.flush();

        // OPEN 검증이 changeStatus 에만 있어서, 열어 둔 뒤 여기서 비우면 그대로 통과했다
        assertThatThrownBy(() ->
                lectureService.update(lecture.getId(), null, null, null, null, null, null,
                        null, null, null, com.dlab.common.web.Patch.cleared(), null, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("비울 수 없습니다");

        assertThat(lecture.getTeacher()).isNotNull();
    }

    @Test
    @DisplayName("접수를 닫은 특강은 담당 강사를 비울 수 있다 — 준비 중으로 되돌리는 흐름이다")
    void draftLectureCanClearTeacher() {
        var lecture = lectureService.create(academy.getId(), YEAR, LectureType.LECTURE,
                "준비 중 특강", null, admin);
        lectureService.update(lecture.getId(), null, null, null, null, null, null, null, null,
                null, com.dlab.common.web.Patch.of(internal.getId()), null, admin);
        em.flush();

        assertThatCode(() ->
                lectureService.update(lecture.getId(), null, null, null, null, null, null,
                        null, null, null, com.dlab.common.web.Patch.cleared(), null, admin))
                .doesNotThrowAnyException();
        assertThat(lecture.getTeacher()).isNull();
    }
}
