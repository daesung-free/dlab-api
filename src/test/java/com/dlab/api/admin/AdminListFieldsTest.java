package com.dlab.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.penalty.service.PenaltyService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.StudentSearchCondition;
import com.dlab.domain.user.service.ClassService;
import com.dlab.domain.user.service.StudentService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목록 응답에 빠져 있던 필드들 (2026-10-13 디랩 피드백 5-2 · 5-3 · 5-7 · 5-9).
 *
 * <p>공통점이 하나다 — <b>데이터는 있는데 목록 응답에 없어서</b> 화면이 그리지 못했다.
 * 없으면 화면이 <b>행마다 상세를 조회</b>하게 되고, 200명이면 쿼리가 200번 나간다.
 */
@SpringBootTest
@Transactional
class AdminListFieldsTest {

    @Autowired PenaltyService penaltyService;
    @Autowired StudentService studentService;
    @Autowired ClassService classService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment enrollment;
    ClassMaster class1;
    AuthPrincipal admin;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now().getYear();

        academy = new Academy("LF01", "목록필드지점", LocalTime.of(9, 0));
        em.persist(academy);

        class1 = new ClassMaster(academy, year, "LF 1반", ClassType.FIXED, null);
        class1.changeExamClassNo((short) 1);
        em.persist(class1);

        Student person = new Student("LF-STU-1", "목록학생", "010-7000-0001");
        em.persist(person);
        enrollment = new StudentEnrollment(person, academy, year, "0001", "RF-LF01",
                GradeType.N_SU);
        enrollment.recordAdmission(LocalDate.now().minusDays(9));
        em.persist(enrollment);

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
        em.flush();
    }

    /** 그 학생에게 좌석을 붙인다. 좌석도 별도 테이블이라 목록이 따로 묶어 와야 한다 */
    private String assignSeat(String seatCd) {
        com.dlab.domain.facility.entity.Building building =
                new com.dlab.domain.facility.entity.Building(academy, "B" + seatCd, "본관",
                        (short) 1, 0);
        em.persist(building);
        StudyArea area = new StudyArea(building, "A" + seatCd, "A" + seatCd, "A구역", (short) 1);
        em.persist(area);
        SeatMaster seat = new SeatMaster(area, seatCd, seatCd, seatCd, 1, 1);
        em.persist(seat);
        em.persist(new SeatAssignment(academy, seat, enrollment));
        em.flush();
        return seatCd;
    }

    /** 항목은 지점·연도·이름이 유니크라 점수별로 하나씩만 만든다 */
    private PenaltyPoint givePenalty(int points, LocalDate on) {
        PenaltyItem item = new PenaltyItem(academy, year, "감점" + points, points,
                PenaltyCategory.DEMERIT);
        em.persist(item);
        PenaltyPoint p = new PenaltyPoint(academy, enrollment, item, points,
                "테스트", PenaltySource.MANUAL, null,
                on.atStartOfDay(com.dlab.common.config.TimeConfig.KST).toInstant());
        em.persist(p);
        em.flush();
        return p;
    }

    @Test
    @DisplayName("★★ 상벌점 목록에 그 학생 누적 점수가 온다 — 제적 기준 40점이 누적이다")
    void penaltyRowCarriesCumulativePoint() {
        // 지난달 10점 + 이번달 3점. 기간을 이번 달로 좁혀도 누적은 13 이어야 한다
        givePenalty(10, LocalDate.now().minusMonths(1));
        givePenalty(3, LocalDate.now());

        var board = penaltyService.board(admin, academy.getId(),
                LocalDate.now().withDayOfMonth(1), LocalDate.now(),
                null, null, null, null, null);

        // 기간 합계는 이번 달 것만
        assertThat(board.minusTotal()).isEqualTo(-3);
        // ★ 누적은 기간과 무관하다 — 이게 없으면 화면이 「이번 달 +3」만 보여 오인한다
        assertThat(board.cumulativePoints()).containsEntry(enrollment.getId(), 13);
    }

    @Test
    @DisplayName("★ 누적은 학생 수만큼만 조회한다 — 행마다 부르면 200명이면 200번이다")
    void cumulativeIsBatched() {
        // 같은 학생에게 행을 여러 개 만든다. 조회는 학생 1명분이어야 한다
        givePenalty(1, LocalDate.now());
        givePenalty(2, LocalDate.now());
        givePenalty(4, LocalDate.now());

        var board = penaltyService.board(admin, academy.getId(),
                LocalDate.now().minusDays(1), LocalDate.now(),
                null, null, null, null, null);

        assertThat(board.rows()).hasSize(3);
        // 묶음 결과는 학생 단위다
        assertThat(board.cumulativePoints()).hasSize(1)
                .containsEntry(enrollment.getId(), 7);
    }

    @Test
    @DisplayName("★ 상벌점 목록에 좌석번호가 온다 (피드백 5-7)")
    void penaltyRowCarriesSeat() {
        assignSeat("A-12");
        givePenalty(1, LocalDate.now());

        var board = penaltyService.board(admin, academy.getId(),
                LocalDate.now().minusDays(1), LocalDate.now(),
                null, null, null, null, null);

        assertThat(board.seatCodes()).containsEntry(enrollment.getId(), "A-12");
    }

    @Test
    @DisplayName("★ 학생 검색을 좌석번호로 한다 (피드백 5-2)")
    void searchesBySeatCd() {
        assignSeat("B-07");

        var found = studentService.search(
                com.dlab.common.search.SearchScope.of(admin, (int) year, academy.getId()),
                condition("B-07"), PageRequest.of(0, 20));
        var notFound = studentService.search(
                com.dlab.common.search.SearchScope.of(admin, (int) year, academy.getId()),
                condition("B-99"), PageRequest.of(0, 20));

        assertThat(found.getContent()).extracting(StudentEnrollment::getId)
                .contains(enrollment.getId());
        // 없는 좌석으로 찾으면 걸리지 않는다 — 조건이 무시되면 전체가 나와 버린다
        assertThat(notFound.getContent()).extracting(StudentEnrollment::getId)
                .doesNotContain(enrollment.getId());
    }

    @Test
    @DisplayName("★ 회수된 좌석으로는 안 걸린다 — 그 자리를 쓰던 옛 학생이 같이 나온다")
    void releasedSeatIsNotSearchable() {
        assignSeat("C-01");
        em.createNativeQuery("UPDATE seat_assignment SET released_at = now()").executeUpdate();
        em.clear();

        var result = studentService.search(
                com.dlab.common.search.SearchScope.of(admin, (int) year, academy.getId()),
                condition("C-01"), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(StudentEnrollment::getId)
                .doesNotContain(enrollment.getId());
    }

    @Test
    @DisplayName("★★ 수험번호 반이 중복되면 반 이름을 담아 거절한다 — 번호만으로는 어디를 고칠지 모른다")
    void rejectsDuplicateExamClassNoWithName() {
        ClassMaster other = new ClassMaster(academy, year, "LF 2반", ClassType.FIXED, null);
        em.persist(other);
        em.flush();

        assertThatThrownBy(() ->
                classService.changeExamClassNo(other.getId(), (short) 1, admin))
                .hasMessageContaining("LF 1반");
    }

    @Test
    @DisplayName("같은 반에 같은 번호를 다시 넣는 것은 막지 않는다 — 자기 자신은 중복이 아니다")
    void allowsResettingOwnExamClassNo() {
        assertThat(classService.changeExamClassNo(class1.getId(), (short) 1, admin)).isNotNull();
    }

    private StudentSearchCondition condition(String seatCd) {
        return new StudentSearchCondition(null, null, null, null, null, null, seatCd,
                null, null, null, null, null, null, null, null, null, null);
    }
}
