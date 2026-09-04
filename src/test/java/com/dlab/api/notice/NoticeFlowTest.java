package com.dlab.api.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.entity.NoticeAuthorType;
import com.dlab.domain.notice.entity.NoticeScope;
import com.dlab.domain.notice.service.NoticeService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지 (F-4.11-3).
 *
 * <p>핵심은 <b>범위별 작성 권한</b>과 <b>내게 보이는 것만 내려간다</b> 둘이다.
 */
@SpringBootTest
@Transactional
class NoticeFlowTest {

    @Autowired NoticeService noticeService;
    @Autowired com.dlab.domain.user.service.AppScopeResolver scopeResolver;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    ClassMaster class1;
    Teacher homeroom;
    StudentEnrollment minji;      // class1 배정
    StudentEnrollment seojun;     // 미배정

    AuthPrincipal headOffice;     // 본사 행정 (전 지점)
    AuthPrincipal branchAdmin;    // 분당 지점관리자 (행정)
    AuthPrincipal homeroomTeacher;// 분당 1반 담임
    AuthPrincipal otherTeacher;   // 담임 아님

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        homeroom = new Teacher(bundang, "박담임", "010-1111-1111");
        Teacher another = new Teacher(bundang, "최선생", "010-2222-2222");
        em.persist(homeroom);
        em.persist(another);

        Employee hq = new Employee(bundang, "본사행정");
        Employee staff = new Employee(bundang, "지점행정");
        em.persist(hq);
        em.persist(staff);

        class1 = new ClassMaster(bundang, (short) 2026, "1반", ClassType.FIXED, homeroom);
        em.persist(class1);

        minji = enroll("김민지", "2026-0001", bundang);
        em.persist(new ClassAssignment(bundang, minji, class1, ClassType.FIXED));
        seojun = enroll("박서준", "2026-0002", bundang);
        em.flush();

        headOffice = principal(account(Account.forEmployee(hq, "hq", "x")),
                null, List.of(Role.SUPER_ADMIN), true);
        branchAdmin = principal(account(Account.forEmployee(staff, "staff", "x")),
                bundang.getId(), List.of(Role.BRANCH_ADMIN), false);
        homeroomTeacher = principal(account(Account.forTeacher(homeroom, "t1", "x")),
                bundang.getId(), List.of(Role.TEACHER), false);
        otherTeacher = principal(account(Account.forTeacher(another, "t2", "x")),
                bundang.getId(), List.of(Role.TEACHER), false);
    }

    private StudentEnrollment enroll(String name, String stdNo, Academy academy) {
        Student student = new Student("DL-" + stdNo, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, academy, (short) 2026, stdNo, null, GradeType.HIGH3);
        em.persist(e);
        return e;
    }

    private Account account(Account account) {
        em.persist(account);
        em.flush();
        return account;
    }

    private AuthPrincipal principal(Account account, Long academyId,
                                    List<Role> roles, boolean allAcademy) {
        return AuthPrincipal.of(account.getId(), account.getAccountType().name(),
                academyId, roles, allAcademy);
    }

    // ── 작성 권한 ─────────────────────────────────────────

    @Test
    @DisplayName("★ 전 지점 공지는 본사만 — 지점관리자가 실수로 전 지점에 쏘면 안 된다")
    void onlyHeadOfficeCanWriteToAllBranches() {
        assertThatCode(() -> noticeService.createForAll(headOffice, "전체 안내", "본문"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> noticeService.createForAll(branchAdmin, "전체 안내", "본문"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본사");
    }

    @Test
    @DisplayName("★ 전체·지점 공지는 담당선생님(사감)이 못 쓴다 — 행정 조직이 낸다")
    void teacherCannotWriteBranchWideNotice() {
        assertThatThrownBy(() -> noticeService.createForBranch(
                homeroomTeacher, bundang.getId(), "지점 안내", "본문"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("지점 공지는 지점관리자가 쓴다")
    void branchAdminWritesBranchNotice() {
        Notice notice = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "지점 안내", "본문");

        assertThat(notice.getScope()).isEqualTo(NoticeScope.BRANCH);
        assertThat(notice.getAuthorType()).isEqualTo(NoticeAuthorType.EMPLOYEE);
    }

    @Test
    @DisplayName("★ 반 공지는 그 반 담임이 쓴다")
    void homeroomTeacherWritesClassNotice() {
        Notice notice = noticeService.createForClass(
                homeroomTeacher, class1.getId(), "1반 안내", "본문");

        assertThat(notice.getScope()).isEqualTo(NoticeScope.CLASS);
        assertThat(notice.getAuthorType()).isEqualTo(NoticeAuthorType.TEACHER);
        assertThat(notice.getClassMaster().getId()).isEqualTo(class1.getId());
    }

    @Test
    @DisplayName("★ 담임이 아닌 선생님은 그 반 공지를 못 쓴다")
    void nonHomeroomTeacherIsRejected() {
        assertThatThrownBy(() -> noticeService.createForClass(
                otherTeacher, class1.getId(), "1반 안내", "본문"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("지점관리자도 반 공지를 쓸 수 있다 — 담임 미지정 반이 공지 불가가 되면 안 된다")
    void branchAdminCanAlsoWriteClassNotice() {
        assertThatCode(() -> noticeService.createForClass(
                branchAdmin, class1.getId(), "1반 안내", "본문"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 다른 지점 반에는 못 쓴다")
    void otherAcademyClassIsRejected() {
        ClassMaster ilsanClass = new ClassMaster(ilsan, (short) 2026, "1반", ClassType.FIXED, null);
        em.persist(ilsanClass);
        em.flush();

        assertThatThrownBy(() -> noticeService.createForClass(
                branchAdmin, ilsanClass.getId(), "안내", "본문"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 수정에도 같은 권한을 다시 건다 — 만들 때만 검사하면 남의 반 공지를 고칠 수 있다")
    void updateRechecksPermission() {
        Notice notice = noticeService.createForClass(
                homeroomTeacher, class1.getId(), "1반 안내", "본문");
        em.flush();

        assertThatThrownBy(() -> noticeService.update(
                otherTeacher, notice.getId(), "바꿈", "본문", false, false, null, null))
                .isInstanceOf(BusinessException.class);
    }

    // ── 앱 피드 ──────────────────────────────────────────

    @Test
    @DisplayName("★ 전 지점 + 내 지점 + 내 반이 한 목록으로 내려온다")
    void feedMergesEveryScope() {
        noticeService.createForAll(headOffice, "전체", "본문");
        noticeService.createForBranch(branchAdmin, bundang.getId(), "분당", "본문");
        noticeService.createForClass(homeroomTeacher, class1.getId(), "1반", "본문");
        em.flush();
        em.clear();

        assertThat(noticeService.feed(minji.getId()))
                .extracting(Notice::getTitle)
                .containsExactlyInAnyOrder("전체", "분당", "1반");
    }

    @Test
    @DisplayName("★ 다른 반 공지는 안 보인다")
    void otherClassNoticeIsHidden() {
        noticeService.createForClass(homeroomTeacher, class1.getId(), "1반", "본문");
        em.flush();
        em.clear();

        // 서준은 반 미배정이라 반 공지가 안 걸린다
        assertThat(noticeService.feed(seojun.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 다른 지점 공지는 안 보인다")
    void otherAcademyNoticeIsHidden() {
        StudentEnrollment ilsanStudent = enroll("일산생", "2026-0003", ilsan);
        noticeService.createForBranch(branchAdmin, bundang.getId(), "분당", "본문");
        em.flush();
        em.clear();

        assertThat(noticeService.feed(ilsanStudent.getId())).isEmpty();
    }

    @Test
    @DisplayName("개별 공지는 그 학생에게만 보인다")
    void individualNoticeGoesToOneStudent() {
        noticeService.createForIndividual(branchAdmin, minji.getId(), "개별", "본문");
        em.flush();
        em.clear();

        assertThat(noticeService.feed(minji.getId())).hasSize(1);
        assertThat(noticeService.feed(seojun.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 예약 발행 시각 전에는 안 보인다 — 앱이 시각을 비교하면 기기 시계에 좌우된다")
    void scheduledNoticeIsHiddenUntilPublished() {
        Notice notice = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "예약", "본문");
        notice.schedule(Instant.now(clock).plus(1, ChronoUnit.HOURS), null);
        em.flush();
        em.clear();

        assertThat(noticeService.feed(minji.getId())).isEmpty();
    }

    @Test
    @DisplayName("만료된 공지는 안 보인다")
    void expiredNoticeIsHidden() {
        Notice notice = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "만료", "본문");
        notice.schedule(null, Instant.now(clock).minus(1, ChronoUnit.HOURS));
        em.flush();
        em.clear();

        assertThat(noticeService.feed(minji.getId())).isEmpty();
    }

    @Test
    @DisplayName("배너는 피드 중 배너 표시분만")
    void bannersAreSubsetOfFeed() {
        noticeService.createForBranch(branchAdmin, bundang.getId(), "일반", "본문");
        Notice banner = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "배너", "본문");
        banner.markBanner(true);
        em.flush();
        em.clear();

        assertThat(noticeService.feed(minji.getId())).hasSize(2);
        assertThat(noticeService.banners(minji.getId()))
                .extracting(Notice::getTitle).containsExactly("배너");
    }

    @Test
    @DisplayName("★ 안 보이는 공지는 상세도 못 연다 — id를 바꿔가며 읽을 수 있으면 안 된다")
    void hiddenNoticeCannotBeOpenedById() {
        Notice classNotice = noticeService.createForClass(
                homeroomTeacher, class1.getId(), "1반", "본문");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> noticeService.readOne(seojun.getId(), classNotice.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // ── 관리자 목록 ───────────────────────────────────────

    @Test
    @DisplayName("★ 관리자 목록에는 전 지점 공지도 함께 나온다 — 조회는 전체 공유다")
    void adminListIncludesHeadOfficeNotice() {
        noticeService.createForAll(headOffice, "전체", "본문");
        noticeService.createForBranch(branchAdmin, bundang.getId(), "분당", "본문");
        em.flush();
        em.clear();

        assertThat(noticeService.findForAdmin(branchAdmin, (short) 2026))
                .extracting(Notice::getTitle)
                .containsExactlyInAnyOrder("전체", "분당");
    }

    @Test
    @DisplayName("고정 공지가 위로 온다")
    void pinnedComesFirst() {
        noticeService.createForBranch(branchAdmin, bundang.getId(), "일반", "본문");
        Notice pinned = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "고정", "본문");
        pinned.markPinned(true);
        em.flush();
        em.clear();

        assertThat(noticeService.findForAdmin(branchAdmin, (short) 2026))
                .first().satisfies(n -> assertThat(n.getTitle()).isEqualTo("고정"));
    }

    @Test
    @DisplayName("삭제는 soft — 그때 무슨 공지가 나갔는지 추적이 끊기면 안 된다")
    void deleteIsSoft() {
        Notice notice = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "분당", "본문");
        em.flush();

        noticeService.delete(branchAdmin, notice.getId());
        em.flush();
        em.clear();

        assertThat(noticeService.findForAdmin(branchAdmin, (short) 2026)).isEmpty();
        assertThat(em.find(Notice.class, notice.getId())).isNotNull();
    }

    // ── 학부모 ───────────────────────────────────────────

    @Test
    @DisplayName("★ 학부모는 자녀를 지정해야 한다 — 계정 하나에 자녀가 여럿이다")
    void guardianMustSpecifyChild() {
        ParentGuardian guardian = new ParentGuardian("김보호", "010-9999-8888", "M");
        em.persist(guardian);
        em.persist(new StudentGuardianLink(minji.getStudent(), guardian, (short) 1));
        Account account = account(Account.forGuardian(guardian, "p1", "x"));
        em.flush();

        assertThatThrownBy(() -> scopeResolver.resolve(account.getId(), null))
                .isInstanceOf(BusinessException.class);

        // ★ 학생 id를 넘긴다 — 등록 건 id가 아니다. 앱 API 전체가 같은 값을 쓴다
        assertThat(scopeResolver.resolve(account.getId(), minji.getStudent().getId()).getId())
                .isEqualTo(minji.getId());
    }

    @Test
    @DisplayName("★ 남의 자녀 공지는 못 본다")
    void guardianCannotReadOtherChild() {
        ParentGuardian guardian = new ParentGuardian("김보호", "010-9999-8888", "M");
        em.persist(guardian);
        em.persist(new StudentGuardianLink(minji.getStudent(), guardian, (short) 1));
        Account account = account(Account.forGuardian(guardian, "p1", "x"));
        em.flush();

        assertThatThrownBy(() ->
                scopeResolver.resolve(account.getId(), seojun.getStudent().getId()))
                .isInstanceOf(BusinessException.class);
    }

    // ── 열람 기록 (API_GAPS 13-2) ─────────────────────────────

    @Test
    @DisplayName("★ 같은 공지를 여러 번 열어도 한 번만 센다 — 앱은 화면 진입마다 부른다")
    void repeatedReadCountsOnce() {
        Notice notice = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "지점 공지", "본문");
        em.flush();

        noticeService.markRead(minji.getId(), notice.getId());
        noticeService.markRead(minji.getId(), notice.getId());
        noticeService.markRead(minji.getId(), notice.getId());
        em.flush();

        assertThat(noticeService.readCounts(List.of(notice.getId())))
                .containsEntry(notice.getId(), 1L);
    }

    @Test
    @DisplayName("학생마다 따로 센다")
    void countsPerStudent() {
        Notice notice = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "지점 공지", "본문");
        em.flush();

        noticeService.markRead(minji.getId(), notice.getId());
        noticeService.markRead(seojun.getId(), notice.getId());
        em.flush();

        assertThat(noticeService.readCounts(List.of(notice.getId())))
                .containsEntry(notice.getId(), 2L);
    }

    @Test
    @DisplayName("★★ 내게 안 보이는 공지는 읽음 처리도 안 된다 — 번호를 바꿔 열람 수를 부풀릴 수 있다")
    void cannotMarkReadOnInvisibleNotice() {
        Notice classNotice = noticeService.createForClass(
                branchAdmin, class1.getId(), "1반 공지", "본문");
        em.flush();

        // seojun 은 반 미배정이라 이 공지가 보이지 않는다
        assertThatThrownBy(() -> noticeService.markRead(seojun.getId(), classNotice.getId()))
                .isInstanceOf(BusinessException.class);

        assertThat(noticeService.readCounts(List.of(classNotice.getId()))).isEmpty();
    }

    @Test
    @DisplayName("아무도 안 읽은 공지는 목록에 없다 — 화면이 0으로 채운다")
    void unreadNoticeHasNoEntry() {
        Notice notice = noticeService.createForBranch(
                branchAdmin, bundang.getId(), "지점 공지", "본문");
        em.flush();

        assertThat(noticeService.readCounts(List.of(notice.getId()))).isEmpty();
    }
}
