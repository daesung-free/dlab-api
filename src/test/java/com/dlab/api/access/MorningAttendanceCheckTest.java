package com.dlab.api.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.access.repository.MorningAttendanceCheckRepository;
import com.dlab.domain.access.service.MorningAttendanceCheckService;
import com.dlab.domain.attendance.entity.StaffAttendance;
import com.dlab.domain.attendance.entity.StaffAttendanceType;
import com.dlab.domain.menu.entity.AccountMenu;
import com.dlab.domain.menu.repository.MenuRepository;
import com.dlab.domain.user.entity.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오전 출근 확인 (긴급출입 기능 B).
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>출근 기록이 있으면 알리지 않는다</b> — 매일 아침 전 지점에 문자가 가면 아무도 안 본다</li>
 *   <li><b>하루 한 지점 한 건</b> — 배치를 두 번 돌려도 같은 날이 두 줄로 남지 않는다</li>
 *   <li><b>★ 수신 대상은 권한을 켜 둔 계정만</b> — 역할 기본값으로 세면 전 직원에게 나간다</li>
 *   <li><b>휴일을 빼지 않는다</b> — 요구사항의 「매일」을 글자 그대로</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class MorningAttendanceCheckTest {

    @Autowired MorningAttendanceCheckService checkService;
    @Autowired MorningAttendanceCheckRepository checkRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    /** 일요일로 잡는다 — 휴일에도 도는지 함께 확인한다 */
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    Academy quiet;
    Academy busy;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) SUNDAY.getYear();

        quiet = new Academy("MC01", "출근없는지점", LocalTime.of(9, 0));
        busy = new Academy("MC02", "출근한지점", LocalTime.of(9, 0));
        em.persist(quiet);
        em.persist(busy);

        // 출근한 지점에만 IN 기록을 넣는다
        StudentEnrollment staff = staffOf(busy, "MC-STAFF-1");
        em.persist(new StaffAttendance(staff, SUNDAY, StaffAttendanceType.IN,
                SUNDAY.atTime(6, 58).toInstant(java.time.ZoneOffset.UTC)));
        em.flush();
    }

    /** 직원 등록 건. 출근 태깅이 등록 건에 붙는다 */
    private StudentEnrollment staffOf(Academy academy, String code) {
        Student person = new Student(code, "직원" + code, "010-0000-0000");
        em.persist(person);
        StudentEnrollment enrollment = new StudentEnrollment(person, academy, year,
                code, null, GradeType.STAFF);
        em.persist(enrollment);
        return enrollment;
    }

    /** 그 계정에 알림 수신 권한을 명시적으로 켠다 */
    private Account grantAlert(Academy academy, String loginId) {
        Employee employee = new Employee(academy, "관리자" + loginId);
        em.persist(employee);
        Account account = Account.forEmployee(employee, loginId, "x", false);
        em.persist(account);

        var menu = menuRepository.findByCodes(
                List.of(MorningAttendanceCheckService.ALERT_MENU_CODE)).get(0);
        em.persist(new AccountMenu(account, menu));
        em.flush();
        return account;
    }

    @Test
    @DisplayName("★ 출근 기록이 없으면 알림 대상이다 — 일요일에도 돈다")
    void flagsQuietBranch() {
        var check = checkService.runOne(quiet, SUNDAY);
        em.flush();

        assertThat(check.getInCount()).isZero();
        assertThat(check.isAlertNeeded()).isTrue();
        // ⚠️ 알리고 연동 전이라 보내지 않았다는 사실이 남아야 한다
        assertThat(check.isAlertSent()).isFalse();
    }

    @Test
    @DisplayName("출근 기록이 있으면 알리지 않는다 — 매일 전 지점에 문자가 가면 아무도 안 본다")
    void skipsBusyBranch() {
        var check = checkService.runOne(busy, SUNDAY);
        em.flush();

        assertThat(check.getInCount()).isEqualTo(1);
        assertThat(check.isAlertNeeded()).isFalse();
    }

    @Test
    @DisplayName("★ 하루 한 지점 한 건이다 — 두 번 돌려도 같은 날이 두 줄로 남지 않는다")
    void idempotentPerDay() {
        var first = checkService.runOne(quiet, SUNDAY);
        em.flush();
        var second = checkService.runOne(quiet, SUNDAY);
        em.flush();

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(checkRepository.search(quiet.getId(), SUNDAY, SUNDAY)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 수신 대상은 권한을 켜 둔 계정만 — 역할 기본값으로 세면 전 직원에게 나간다")
    void countsOnlyExplicitGrants() {
        // 권한을 안 켠 계정. RoleMenuDefaults 는 「표에 없는 메뉴를 전 역할에 보인다」라
        // 기본값으로 세면 이 계정까지 대상이 된다
        Employee plain = new Employee(quiet, "권한없는직원");
        em.persist(plain);
        em.persist(Account.forEmployee(plain, "mc-plain", "x", false));

        grantAlert(quiet, "mc-alert");
        em.flush();

        var check = checkService.runOne(quiet, SUNDAY);
        em.flush();

        // 켠 계정 하나만 세야 한다
        assertThat(check.getRecipientCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 다른 지점 담당은 세지 않는다 — 남의 지점 아침 알림을 받으면 안 된다")
    void doesNotCountOtherBranch() {
        grantAlert(busy, "mc-other-branch");
        em.flush();

        var check = checkService.runOne(quiet, SUNDAY);
        em.flush();

        assertThat(check.getRecipientCount()).isZero();
    }

    @Test
    @DisplayName("수신 대상이 0이면 기록에 남는다 — 검사는 돌았는데 받을 사람이 없는 설정 문제다")
    void zeroRecipientIsRecorded() {
        var check = checkService.runOne(quiet, SUNDAY);
        em.flush();

        assertThat(check.isAlertNeeded()).isTrue();
        assertThat(check.getRecipientCount()).isZero();
    }

    @Test
    @DisplayName("발송 문구는 요구사항 원문 그대로다")
    void messageMatchesRequirement() {
        assertThat(checkService.alertMessage(quiet))
                .isEqualTo("[대성] 출근없는지점지점 7시 출근이 안 이루어졌습니다.");
    }

    @Test
    @DisplayName("전 지점을 돌고 미확인 지점 수를 돌려준다")
    void runsAllBranches() {
        int needed = checkService.runAll(SUNDAY);
        em.flush();

        // 이 테스트에서 만든 두 지점 중 하나만 미확인이다(다른 테스트 지점이 섞일 수 있어 최소값으로 본다)
        assertThat(needed).isGreaterThanOrEqualTo(1);
        assertThat(checkRepository.findByAcademyIdAndCheckDate(quiet.getId(), SUNDAY)).isPresent();
        assertThat(checkRepository.findByAcademyIdAndCheckDate(busy.getId(), SUNDAY)).isPresent();
    }
}
