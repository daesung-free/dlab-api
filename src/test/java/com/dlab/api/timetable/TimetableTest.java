package com.dlab.api.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.master.entity.RoomMaster;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.timetable.entity.TimetableCell;
import com.dlab.domain.timetable.service.TimetableService;
import com.dlab.domain.timetable.service.TimetableService.CellCommand;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
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
 * 반 주간 시간표 편성 (F-C-3 「시간표·이동수업」).
 *
 * <p>화면은 있는데 <b>「학기 선택·전 학기 복사·편성 저장」이 전부 「준비 중」</b>이었다 —
 * 저장할 곳이 없었다.
 *
 * <p>지키려는 것 다섯이다.
 * <ol>
 *   <li><b>★★ (연도, 교시, 요일, 강의실) 이 DB 에서 유일하다</b> — 없으면 편성 화면에서
 *       아무리 막아도 뚫린다. 화면 검사는 「지금 보고 있는 반」만 안다</li>
 *   <li><b>★★ 강의실이 두 군데다</b> — 셀이 비면 반 홈룸으로 떨어진다. 둘 다 유지한다</li>
 *   <li><b>★★ 전년도 복사가 멱등하다</b> — 두 번 눌러도 두 배가 되지 않는다</li>
 *   <li><b>★ 복사 결과는 건별이다</b> — 어느 반이 못 붙었는지 화면이 말할 수 있어야 한다</li>
 *   <li><b>★ 빈 목록 저장은 거절한다</b> — 화면 버그 하나가 편성을 날리면 안 된다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class TimetableTest {

    @Autowired TimetableService timetableService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2026;
    static final short LAST_YEAR = 2025;

    Academy bundang;
    AuthPrincipal admin;
    Teacher kim;
    RoomMaster homeroom;
    RoomMaster lab;
    ClassMaster n1;
    ClassMaster n2;
    PeriodMaster p1;
    PeriodMaster saturdayP1;

    @BeforeEach
    void setUp() {
        bundang = new Academy("TT1", "시간표지점", LocalTime.of(9, 0));
        em.persist(bundang);

        kim = new Teacher(bundang, "김담임", "010-2000-0001");
        em.persist(kim);

        homeroom = new RoomMaster(bundang, "201", "홈룸", (short) 30, null);
        lab = new RoomMaster(bundang, "301", "실험실", (short) 20, null);
        em.persist(homeroom);
        em.persist(lab);

        n1 = clazz(YEAR, "N수 1반");
        n1.assignRoom(homeroom);
        n2 = clazz(YEAR, "N수 2반");

        p1 = period(YEAR, (short) 1, DayType.WEEKDAY, 9, 10);
        saturdayP1 = period(YEAR, (short) 1, DayType.SATURDAY, 10, 11);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private ClassMaster clazz(short year, String name) {
        ClassMaster c = new ClassMaster(bundang, year, name, ClassType.FIXED, kim);
        em.persist(c);
        return c;
    }

    private PeriodMaster period(short year, short no, DayType dayType, int from, int to) {
        PeriodMaster p = new PeriodMaster(bundang, year, no, no + "교시", dayType,
                PeriodType.CLASS, LocalTime.of(from, 0), LocalTime.of(to, 0));
        em.persist(p);
        return p;
    }

    @Test
    @DisplayName("★★ 셀 강의실이 비면 반 홈룸으로 떨어진다 — 강의실은 두 군데이고 둘 다 산다")
    void cellRoomFallsBackToHomeroom() {
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "국어", kim.getId(), null)));
        em.flush();
        em.clear();

        TimetableCell cell = timetableService.week(admin, n1.getId()).get(0);
        // 칸에 지정된 강의실은 없고(이동수업이 아니다)
        assertThat(cell.getRoom()).isNull();
        // 실제 적용 강의실은 반 홈룸이다
        assertThat(cell.effectiveRoom().getRoomNo()).isEqualTo("201");
        // 홈룸도 그대로 남아 있다 — 합치면 둘 중 하나가 사라진다
        assertThat(cell.getClassMaster().getRoom().getRoomNo()).isEqualTo("201");
    }

    @Test
    @DisplayName("이동수업 칸은 그 칸만 강의실을 바꾼다 — 홈룸은 건드리지 않는다")
    void movingClassOverridesRoomForThatCellOnly() {
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "과학", kim.getId(), lab.getId())));
        em.flush();
        em.clear();

        TimetableCell cell = timetableService.week(admin, n1.getId()).get(0);
        assertThat(cell.effectiveRoom().getRoomNo()).isEqualTo("301");
        assertThat(cell.getClassMaster().getRoom().getRoomNo()).isEqualTo("201");
    }

    @Test
    @DisplayName("★ 같은 시간 같은 강의실은 거절 — 어느 반이 쓰고 있는지까지 알려준다")
    void roomConflictIsRejectedWithClassName() {
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "과학", null, lab.getId())));
        em.flush();

        assertThatThrownBy(() -> timetableService.save(admin, n2.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "과학", null, lab.getId()))))
                .isInstanceOf(BusinessException.class)
                // 「이미 쓰고 있습니다」만으로는 무엇을 고칠지 모른다
                .hasMessageContaining("N수 1반");
    }

    @Test
    @DisplayName("★★ DB 가 막는다 — 서비스를 건너뛰어도 같은 시간 같은 강의실은 두 반이 못 들어간다")
    void roomUniqueIsEnforcedByDatabase() {
        // 편성 화면 검사는 「지금 보고 있는 반」만 안다 — 두 사람이 다른 반을 동시에 편성하면
        // 화면 검사는 둘 다 통과한다. 그걸 막는 것은 DB 제약뿐이다
        em.persist(new TimetableCell(n1, DayOfWeek.MONDAY, p1, "과학", null, lab));

        // IDENTITY 채번이라 persist 가 곧 INSERT 다 — 제약은 여기서 터진다
        assertThatThrownBy(() ->
                em.persist(new TimetableCell(n2, DayOfWeek.MONDAY, p1, "과학", null, lab)))
                // 어느 제약이 막았는지까지 못 박는다 — 다른 제약으로 통과하면 검사가 무의미하다
                .hasStackTraceContaining("uq_timetable_room");
    }

    @Test
    @DisplayName("강의실이 빈 칸은 제약에서 빠진다 — 대부분의 칸이 비어 있다")
    void blankRoomIsOutsideTheUniqueIndex() {
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "국어", null, null)));
        timetableService.save(admin, n2.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "수학", null, null)));
        em.flush();

        assertThat(timetableService.year(admin, bundang.getId(), YEAR)).hasSize(2);
    }

    @Test
    @DisplayName("★ 같은 시간 같은 선생님도 거절 — 서비스 검사다(DB 제약은 두지 않았다)")
    void teacherConflictIsRejected() {
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "국어", kim.getId(), null)));
        em.flush();

        assertThatThrownBy(() -> timetableService.save(admin, n2.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "국어", kim.getId(), null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("김담임");
    }

    @Test
    @DisplayName("★ 평일 교시를 토요일 칸에 둘 수 없다 — 토요일은 교시 구성이 따로 있다")
    void weekdayPeriodCannotSitOnSaturday() {
        assertThatThrownBy(() -> timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.SATURDAY, p1.getId(), "국어", null, null))))
                .isInstanceOf(BusinessException.class);

        // 토요일 교시는 들어간다 — 이 학원은 토요일에도 운영한다
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.SATURDAY, saturdayP1.getId(), "국어", null, null)));
        em.flush();
        assertThat(timetableService.week(admin, n1.getId())).hasSize(1);
    }

    @Test
    @DisplayName("★ 내용이 빈 칸은 지움, 빈 목록은 거절 — 화면 버그 하나가 편성을 날리면 안 된다")
    void blankCellClearsButEmptyListIsRejected() {
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "국어", kim.getId(), lab.getId())));
        em.flush();

        // 내용을 비운 칸을 보내면 그 칸만 지운다
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), null, null, null)));
        em.flush();
        assertThat(timetableService.week(admin, n1.getId())).isEmpty();

        // 비웠던 칸을 다시 채우면 행이 둘 쌓이지 않는다 — 되살려 쓴다
        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "수학", null, null)));
        em.flush();
        assertThat(timetableService.week(admin, n1.getId())).singleElement()
                .extracting(TimetableCell::getSubject).isEqualTo("수학");

        // 그러나 빈 목록은 의도로 볼 수 없다
        assertThatThrownBy(() -> timetableService.save(admin, n1.getId(), List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 전년도 복사는 멱등하다 — 두 번 눌러도 두 배가 되지 않는다")
    void copyIsIdempotent() {
        seedLastYear("N수 1반");

        var first = timetableService.copyFromYear(admin, bundang.getId(), LAST_YEAR, YEAR);
        em.flush();
        assertThat(first.copied()).isEqualTo(1);

        var second = timetableService.copyFromYear(admin, bundang.getId(), LAST_YEAR, YEAR);
        em.flush();
        // 두 번째는 전부 「이미 있음」이다 — 덮어쓰지도 않는다
        assertThat(second.copied()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(timetableService.week(admin, n1.getId())).hasSize(1);
    }

    @Test
    @DisplayName("★ 복사 뒤 손으로 고친 편성은 두 번째 클릭에 되돌려지지 않는다")
    void copyDoesNotOverwriteManualEdits() {
        seedLastYear("N수 1반");
        timetableService.copyFromYear(admin, bundang.getId(), LAST_YEAR, YEAR);
        em.flush();

        timetableService.save(admin, n1.getId(), List.of(
                new CellCommand(DayOfWeek.MONDAY, p1.getId(), "바뀐과목", null, null)));
        em.flush();

        timetableService.copyFromYear(admin, bundang.getId(), LAST_YEAR, YEAR);
        em.flush();

        assertThat(timetableService.week(admin, n1.getId())).singleElement()
                .extracting(TimetableCell::getSubject).isEqualTo("바뀐과목");
    }

    @Test
    @DisplayName("★ 복사 결과는 건별 — 작년에 짝이 없는 반을 화면이 말할 수 있어야 한다")
    void copyReportsEachClass() {
        seedLastYear("N수 1반");

        var result = timetableService.copyFromYear(admin, bundang.getId(), LAST_YEAR, YEAR);
        em.flush();

        assertThat(result.classes()).hasSize(2);
        var matched = result.classes().stream()
                .filter(c -> c.classId().equals(n1.getId())).findFirst().orElseThrow();
        var unmatched = result.classes().stream()
                .filter(c -> c.classId().equals(n2.getId())).findFirst().orElseThrow();

        assertThat(matched.sourceClassName()).isEqualTo("N수 1반");
        assertThat(matched.copied()).isEqualTo(1);
        // 「이 반은 작년에 같은 이름이 없어 비었습니다」를 보여줄 근거
        assertThat(unmatched.sourceClassId()).isNull();
        assertThat(result.classesWithoutSource()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 반 매칭은 복사 원본이 1순위 — 이름이 바뀌어도 맞는다")
    void copiedFromIdBeatsName() {
        ClassMaster lastYearClass = seedLastYear("작년 이름");
        // 기초관리 전년도 복사로 만들어진 반은 원본을 기록하고 있다
        n1.markCopiedFrom(lastYearClass.getId());
        em.flush();

        var result = timetableService.copyFromYear(admin, bundang.getId(), LAST_YEAR, YEAR);
        em.flush();

        var matched = result.classes().stream()
                .filter(c -> c.classId().equals(n1.getId())).findFirst().orElseThrow();
        assertThat(matched.sourceClassName()).isEqualTo("작년 이름");
        assertThat(matched.copied()).isEqualTo(1);
    }

    @Test
    @DisplayName("올해에 그 교시가 없으면 못 붙는다 — 교시 마스터를 먼저 복사해야 한다")
    void missingPeriodIsReported() {
        ClassMaster source = clazz(LAST_YEAR, "N수 1반");
        // 작년에만 있는 2교시
        PeriodMaster onlyLastYear = period(LAST_YEAR, (short) 2, DayType.WEEKDAY, 11, 12);
        em.persist(new TimetableCell(source, DayOfWeek.MONDAY, onlyLastYear, "국어", null, null));
        em.flush();

        var result = timetableService.copyFromYear(admin, bundang.getId(), LAST_YEAR, YEAR);
        em.flush();

        var matched = result.classes().stream()
                .filter(c -> c.classId().equals(n1.getId())).findFirst().orElseThrow();
        assertThat(matched.copied()).isZero();
        assertThat(matched.periodMissing()).isEqualTo(1);
    }

    /** 작년 반 + 월요일 1교시 한 칸. 올해 교시와 (요일구분, 교시번호)가 같아 매핑된다. */
    private ClassMaster seedLastYear(String name) {
        ClassMaster source = clazz(LAST_YEAR, name);
        PeriodMaster lastYearP1 = period(LAST_YEAR, (short) 1, DayType.WEEKDAY, 9, 10);
        em.persist(new TimetableCell(source, DayOfWeek.MONDAY, lastYearP1, "국어", kim, null));
        em.flush();
        return source;
    }
}
