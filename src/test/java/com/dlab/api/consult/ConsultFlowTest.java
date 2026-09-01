package com.dlab.api.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.consult.entity.ConsultLog;
import com.dlab.domain.consult.entity.ConsultMethod;
import com.dlab.domain.consult.entity.ConsultTag;
import com.dlab.domain.consult.entity.ConsultType;
import com.dlab.domain.consult.service.ConsultService;
import com.dlab.domain.consult.service.ConsultService.ConsultStatusRow;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
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
 * 상담 일지 (F-4.11-4).
 *
 * <p>리포트(성적·이행률·신상기록부 결합)는 범위 밖이다 — 그쪽 도메인 대기.
 */
@SpringBootTest
@Transactional
class ConsultFlowTest {

    @Autowired ConsultService consultService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Teacher homeroom;
    Teacher other;
    ClassMaster class1;
    StudentEnrollment minji;      // 1반 배정
    StudentEnrollment seojun;     // 미배정
    AuthPrincipal admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        homeroom = new Teacher(bundang, "박담임", "010-1111-1111");
        other = new Teacher(bundang, "최선생", "010-2222-2222");
        em.persist(homeroom);
        em.persist(other);

        class1 = new ClassMaster(bundang, (short) 2026, "1반", ClassType.FIXED, homeroom);
        em.persist(class1);

        minji = enroll("김민지", "2026-0001");
        em.persist(new ClassAssignment(bundang, minji, class1, ClassType.FIXED));
        seojun = enroll("박서준", "2026-0002");
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String name, String stdNo) {
        Student student = new Student("DL-" + stdNo, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, bundang, (short) 2026, stdNo, null, GradeType.HIGH3);
        em.persist(e);
        return e;
    }

    private ConsultLog write(StudentEnrollment target, ConsultType type,
                             LocalDate at, LocalDate nextDue, List<Long> tagIds) {
        ConsultLog log = consultService.write(admin, target.getId(), null, type,
                ConsultMethod.FACE, at, "20분 · 상담실 2", "상담 내용", "주간 복습",
                nextDue, tagIds);
        em.flush();
        return log;
    }

    // ── 일지 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 상담자를 지정하지 않으면 담임이 된다 — 반 배정에서 자동으로 나온다")
    void teacherDefaultsToHomeroom() {
        ConsultLog log = write(minji, ConsultType.REGULAR, today, null, List.of());

        assertThat(log.getTeacher().getId()).isEqualTo(homeroom.getId());
    }

    @Test
    @DisplayName("반 미배정 학생은 상담자가 비어 있다 — 지정해서 쓰면 된다")
    void unassignedStudentHasNoDefaultTeacher() {
        ConsultLog log = write(seojun, ConsultType.LIFE, today, null, List.of());

        assertThat(log.getTeacher()).isNull();
    }

    @Test
    @DisplayName("★ 미래 날짜로는 기록할 수 없다")
    void futureDateIsRejected() {
        assertThatThrownBy(() ->
                write(minji, ConsultType.REGULAR, today.plusDays(1), null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("미래");
    }

    @Test
    @DisplayName("★ 태그를 붙이고 수정 시 통째로 갈아끼운다 — 화면이 선택 목록을 그대로 보낸다")
    void tagsAreReplacedOnUpdate() {
        ConsultTag late = consultService.createTag(admin, null, (short) 2026,
                ConsultType.LIFE, "지각 잦음", (short) 1);
        ConsultTag sleep = consultService.createTag(admin, null, (short) 2026,
                ConsultType.LIFE, "수면 부족", (short) 2);
        em.flush();

        ConsultLog log = write(minji, ConsultType.LIFE, today, null,
                List.of(late.getId(), sleep.getId()));
        assertThat(log.tagList()).hasSize(2);

        consultService.update(admin, log.getId(), ConsultType.LIFE, ConsultMethod.PHONE,
                today, null, "수정", null, true, null, List.of(late.getId()));
        em.flush();
        em.clear();

        ConsultLog after = consultService.findByStudent(admin, minji.getId()).get(0);
        assertThat(after.tagList()).extracting(ConsultTag::getName).containsExactly("지각 잦음");
        assertThat(after.isActionDone()).isTrue();
    }

    @Test
    @DisplayName("학생 이력은 최근 순이다")
    void historyIsNewestFirst() {
        write(minji, ConsultType.REGULAR, today.minusDays(10), null, List.of());
        write(minji, ConsultType.SCORE, today, null, List.of());
        em.clear();

        assertThat(consultService.findByStudent(admin, minji.getId()))
                .extracting(ConsultLog::getConsultType)
                .containsExactly(ConsultType.SCORE, ConsultType.REGULAR);
    }

    @Test
    @DisplayName("삭제는 soft — 상담 이력은 다음 상담의 근거다")
    void deleteIsSoft() {
        ConsultLog log = write(minji, ConsultType.REGULAR, today, null, List.of());
        consultService.delete(admin, log.getId());
        em.flush();
        em.clear();

        assertThat(consultService.findByStudent(admin, minji.getId())).isEmpty();
        assertThat(em.find(ConsultLog.class, log.getId())).isNotNull();
    }

    @Test
    @DisplayName("★ 담임별로 거른다")
    void filtersByTeacher() {
        write(minji, ConsultType.REGULAR, today, null, List.of());   // 담임 = 박담임
        em.clear();

        assertThat(consultService.findByPeriod(admin, null, today.minusDays(1), today,
                homeroom.getId())).hasSize(1);
        assertThat(consultService.findByPeriod(admin, null, today.minusDays(1), today,
                other.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 다른 지점 학생은 상담을 기록할 수 없다")
    void otherAcademyStudentIsRejected() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> consultService.write(ilsanAdmin, minji.getId(), null,
                ConsultType.REGULAR, ConsultMethod.FACE, today, null, "내용", null, null, List.of()))
                .isInstanceOf(BusinessException.class);
    }

    // ── 현황 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 상담을 한 번도 안 한 학생이 목록에 남는다 — 이 화면의 목적이다")
    void neverConsultedStudentStaysInStatus() {
        write(minji, ConsultType.REGULAR, today, null, List.of());
        em.clear();

        List<ConsultStatusRow> rows = consultService.status(admin, null, null);

        assertThat(rows).hasSize(2);
        var never = rows.stream().filter(r -> r.studentNo().equals("2026-0002"))
                .findFirst().orElseThrow();
        assertThat(never.neverConsulted()).isTrue();
        assertThat(never.lastConsultedAt()).isNull();
    }

    @Test
    @DisplayName("★ 최근 상담 하나만 나온다")
    void statusShowsLatestOnly() {
        write(minji, ConsultType.REGULAR, today.minusDays(10), null, List.of());
        write(minji, ConsultType.SCORE, today.minusDays(1), null, List.of());
        em.clear();

        var row = consultService.status(admin, null, null).stream()
                .filter(r -> r.studentNo().equals("2026-0001")).findFirst().orElseThrow();

        assertThat(row.lastConsultType()).isEqualTo(ConsultType.SCORE);
        assertThat(row.lastConsultedAt()).isEqualTo(today.minusDays(1));
    }

    @Test
    @DisplayName("★ 다음 상담 예정일이 지나면 지연 일수를 센다 — 화면이 빨간색으로 띄운다")
    void overdueDaysAreCounted() {
        write(minji, ConsultType.REGULAR, today.minusDays(10), today.minusDays(3), List.of());
        em.clear();

        var row = consultService.status(admin, null, null).stream()
                .filter(r -> r.studentNo().equals("2026-0001")).findFirst().orElseThrow();

        assertThat(row.overdueDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("예정일이 안 지났으면 지연 0")
    void notOverdueYet() {
        write(minji, ConsultType.REGULAR, today, today.plusDays(7), List.of());
        em.clear();

        var row = consultService.status(admin, null, null).stream()
                .filter(r -> r.studentNo().equals("2026-0001")).findFirst().orElseThrow();

        assertThat(row.overdueDays()).isZero();
    }

    @Test
    @DisplayName("★★ 신상기록부 작성 여부는 null이다 — false로 내리면 '작성 안 함'이라는 없는 사실이 생긴다")
    void profileWrittenIsUnknownForNow() {
        var row = consultService.status(admin, null, null).get(0);

        assertThat(row.profileWritten()).isNull();
    }

    @Test
    @DisplayName("★ 담임 필터를 걸면 그 반 학생만 나온다")
    void statusFiltersByHomeroom() {
        List<ConsultStatusRow> rows = consultService.status(admin, null, homeroom.getId());

        assertThat(rows).extracting(ConsultStatusRow::studentNo).containsExactly("2026-0001");
    }

    // ── 태그 마스터 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 꺼둔 태그는 담임 화면에서 빠진다 — 관리자 목록에는 남는다")
    void inactiveTagIsHiddenFromTeachers() {
        ConsultTag tag = consultService.createTag(admin, null, (short) 2026,
                ConsultType.SCORE, "수학 취약", (short) 1);
        em.flush();

        consultService.updateTag(admin, tag.getId(), "수학 취약", ConsultType.SCORE,
                (short) 1, (short) 5, false);
        em.flush();
        em.clear();

        assertThat(consultService.tags(admin, null, (short) 2026, false)).isEmpty();
        assertThat(consultService.tags(admin, null, (short) 2026, true)).hasSize(1);
    }

    @Test
    @DisplayName("태그는 정렬 순서대로 나온다")
    void tagsAreSorted() {
        consultService.createTag(admin, null, (short) 2026, ConsultType.LIFE, "두번째", (short) 2);
        consultService.createTag(admin, null, (short) 2026, ConsultType.LIFE, "첫번째", (short) 1);
        em.flush();
        em.clear();

        assertThat(consultService.tags(admin, null, (short) 2026, false))
                .extracting(ConsultTag::getName).containsExactly("첫번째", "두번째");
    }
}
