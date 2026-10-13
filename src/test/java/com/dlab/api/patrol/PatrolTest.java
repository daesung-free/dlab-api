package com.dlab.api.patrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.patrol.entity.PatrolMark;
import com.dlab.domain.patrol.service.PatrolService;
import com.dlab.domain.patrol.service.PatrolService.MarkCommand;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
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
 * 순찰 관리 (2026-10-13 디랩 피드백 1-3 — 신규 기능).
 *
 * <p>지금은 <b>종이에 인쇄해 체크한 뒤 DSA 에 다시 입력</b>한다 — 같은 일을 두 번 한다.
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>★★ 회차를 합치지 않는다</b> — 합치면 마지막 순찰만 남는다</li>
 *   <li><b>★★ 좌석이 아니라 학생에 붙인다</b> — 재배치 뒤 기록이 엉뚱한 학생에게 붙는다</li>
 *   <li><b>같은 회차를 다시 찍으면 덮어쓴다</b> — 두 행이면 「그 회차에 무엇이었나」에 답이 둘이다</li>
 *   <li><b>★ 남의 지점 학생은 거절한다</b> — 학생 id 를 화면이 보낸다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class PatrolTest {

    @Autowired PatrolService patrolService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    Academy other;
    StudentEnrollment minji;
    StudentEnrollment seojun;
    AuthPrincipal teacher;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now().getYear();

        academy = new Academy("PT01", "순찰지점", LocalTime.of(9, 0));
        other = new Academy("PT02", "남의지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.persist(other);

        Teacher t = new Teacher(academy, "박사감", "010-1000-0001");
        em.persist(t);
        Account account = Account.forTeacher(t, "pt-teacher", "x", false);
        em.persist(account);

        minji = enroll(academy, "김민지", "0001");
        seojun = enroll(academy, "박서준", "0002");
        em.flush();

        teacher = AuthPrincipal.of(account.getId(), "TEACHER", academy.getId(),
                List.of(Role.TEACHER), false);
    }

    private StudentEnrollment enroll(Academy at, String name, String studentNo) {
        Student person = new Student("P" + at.getAcadCd() + studentNo, name,
                "010-5000-" + studentNo);
        em.persist(person);
        StudentEnrollment e = new StudentEnrollment(person, at, year, studentNo,
                "R" + at.getAcadCd() + studentNo, GradeType.N_SU);
        em.persist(e);
        return e;
    }

    @Test
    @DisplayName("★★ 회차마다 따로 남는다 — 합치면 마지막 순찰만 남는다")
    void roundsAreSeparate() {
        LocalDate today = LocalDate.now();

        patrolService.record(teacher, academy.getId(), today, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.SLEEP)));
        patrolService.record(teacher, academy.getId(), today, (short) 3,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.TOILET)));
        em.flush();

        var round1 = patrolService.records(teacher, academy.getId(), today, (short) 1, null);
        var round3 = patrolService.records(teacher, academy.getId(), today, (short) 3, null);
        var all = patrolService.records(teacher, academy.getId(), today, null, null);

        assertThat(round1).singleElement()
                .extracting(r -> r.getMark()).isEqualTo(PatrolMark.SLEEP);
        assertThat(round3).singleElement()
                .extracting(r -> r.getMark()).isEqualTo(PatrolMark.TOILET);
        // 회차를 비우면 그날 전부다
        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("★ 같은 회차를 다시 찍으면 덮어쓴다 — 두 행이면 답이 둘이 된다")
    void remarkOverwrites() {
        LocalDate today = LocalDate.now();

        patrolService.record(teacher, academy.getId(), today, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.SLEEP)));
        em.flush();
        patrolService.record(teacher, academy.getId(), today, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A02", PatrolMark.NONE)));
        em.flush();

        var records = patrolService.records(teacher, academy.getId(), today, (short) 1, null);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMark()).isEqualTo(PatrolMark.NONE);
        // 좌석도 갱신된다 — 그 사이 자리를 옮겼을 수 있다
        assertThat(records.get(0).getSeatCd()).isEqualTo("A02");
    }

    @Test
    @DisplayName("★★ 좌석이 아니라 학생에 붙는다 — 재배치돼도 기록이 따라간다")
    void belongsToStudentNotSeat() {
        LocalDate today = LocalDate.now();
        patrolService.record(teacher, academy.getId(), today, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.SLEEP)));
        em.flush();

        var record = patrolService.records(teacher, academy.getId(), today, (short) 1, null).get(0);

        assertThat(record.getEnrollment().getId()).isEqualTo(minji.getId());
        // 좌석은 「그때 어느 자리였나」의 기록이다 — 참조가 아니다
        assertThat(record.getSeatCd()).isEqualTo("A01");
    }

    @Test
    @DisplayName("★ 한 번에 여러 건을 받는다 — 한 건씩 보내면 태블릿에서 빠진다")
    void acceptsManyAtOnce() {
        LocalDate today = LocalDate.now();

        var saved = patrolService.record(teacher, academy.getId(), today, (short) 2,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.QNA),
                        new MarkCommand(seojun.getId(), "A02", PatrolMark.CONSULT)));
        em.flush();

        assertThat(saved).hasSize(2);
        assertThat(patrolService.records(teacher, academy.getId(), today, (short) 2, null)).hasSize(2);
    }

    @Test
    @DisplayName("★★ 남의 지점 학생은 거절한다 — 학생 id 를 화면이 보낸다")
    void rejectsOtherBranchStudent() {
        StudentEnrollment stranger = enroll(other, "남의학생", "0003");
        em.flush();

        assertThatThrownBy(() -> patrolService.record(teacher, academy.getId(),
                LocalDate.now(), (short) 1,
                List.of(new MarkCommand(stranger.getId(), "A01", PatrolMark.SLEEP))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("회차 범위를 벗어나면 거절한다 — 1~5다")
    void rejectsBadRound() {
        assertThatThrownBy(() -> patrolService.record(teacher, academy.getId(),
                LocalDate.now(), (short) 6,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.NONE))))
                .hasMessageContaining("1~5");
        assertThatThrownBy(() -> patrolService.record(teacher, academy.getId(),
                LocalDate.now(), (short) 0,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.NONE))))
                .hasMessageContaining("1~5");
    }

    @Test
    @DisplayName("날짜를 비우면 오늘이다 — 순찰은 당일 업무다")
    void defaultsToToday() {
        patrolService.record(teacher, academy.getId(), null, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.NONE)));
        em.flush();

        assertThat(patrolService.records(teacher, academy.getId(), null, (short) 1, null))
                .singleElement()
                .extracting(r -> r.getPatrolDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("★ 「없다」와 「졸고 있다」를 가린다 — 졸음은 자리에 있는 것이다")
    void sleepIsNotAway() {
        assertThat(PatrolMark.SLEEP.away()).isFalse();
        assertThat(PatrolMark.NONE.away()).isFalse();
        assertThat(PatrolMark.TOILET.away()).isTrue();
        assertThat(PatrolMark.CONSULT.away()).isTrue();
    }

    @Test
    @DisplayName("★★ 회차를 비우는 것은 별 경로다 — 빈 목록 저장으로 지워지면 화면 버그가 회차를 날린다")
    void clearRoundIsExplicit() {
        LocalDate today = LocalDate.now();
        patrolService.record(teacher, academy.getId(), today, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.SLEEP),
                        new MarkCommand(seojun.getId(), "A02", PatrolMark.NONE)));
        em.flush();

        // 빈 목록 저장으로는 안 지워진다 — 목록을 만드는 코드가 빈 배열을 보내는 실수는 흔하고,
        // 그때 사감이 한 시간 돌며 찍은 것이 사라지면 안 된다
        assertThatThrownBy(() -> patrolService.record(teacher, academy.getId(), today,
                (short) 1, List.of()))
                .hasMessageContaining("좌석을 골라");
        assertThat(patrolService.records(teacher, academy.getId(), today, (short) 1, null))
                .hasSize(2);

        // 지우겠다고 말해야 지워진다
        int cleared = patrolService.clearRound(teacher, academy.getId(), today, (short) 1);
        em.flush();

        assertThat(cleared).isEqualTo(2);
        assertThat(patrolService.records(teacher, academy.getId(), today, (short) 1, null))
                .isEmpty();
    }

    @Test
    @DisplayName("비운 회차에 다시 찍을 수 있다 — soft delete 가 유니크 제약을 막지 않는다")
    void canRemarkAfterClear() {
        LocalDate today = LocalDate.now();
        patrolService.record(teacher, academy.getId(), today, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.SLEEP)));
        em.flush();
        patrolService.clearRound(teacher, academy.getId(), today, (short) 1);
        em.flush();

        patrolService.record(teacher, academy.getId(), today, (short) 1,
                List.of(new MarkCommand(minji.getId(), "A01", PatrolMark.NONE)));
        em.flush();

        assertThat(patrolService.records(teacher, academy.getId(), today, (short) 1, null))
                .singleElement()
                .extracting(r -> r.getMark()).isEqualTo(PatrolMark.NONE);
    }

    @Test
    @DisplayName("★ 묶음은 서버가 내린다 — 화면이 코드로 묶으면 상태가 늘 때 조용히 빠진다")
    void groupsAreServerSide() {
        assertThat(PatrolMark.SLEEP.group()).isEqualTo(PatrolMark.Group.WARN);
        assertThat(PatrolMark.QNA.group()).isEqualTo(PatrolMark.Group.INSIDE);
        assertThat(PatrolMark.TOILET.group()).isEqualTo(PatrolMark.Group.BRIEF);
        assertThat(PatrolMark.OUTING.group()).isEqualTo(PatrolMark.Group.OUTSIDE);
        assertThat(PatrolMark.NONE.group()).isEqualTo(PatrolMark.Group.OK);

        // 모든 상태에 묶음이 있어야 한다 — 빠지면 화면에서 색 없는 칸이 생긴다
        assertThat(java.util.Arrays.stream(PatrolMark.values()))
                .allMatch(m -> m.group() != null);
    }

    @Test
    @DisplayName("빈 목록은 거절한다")
    void rejectsEmptyMarks() {
        assertThatThrownBy(() -> patrolService.record(teacher, academy.getId(),
                LocalDate.now(), (short) 1, List.of()))
                .hasMessageContaining("좌석을 골라");
    }
}
