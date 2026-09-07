package com.dlab.domain.notice.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.entity.NoticeAuthorType;
import com.dlab.domain.notice.entity.NoticeRead;
import com.dlab.domain.notice.entity.NoticeScope;
import com.dlab.domain.notice.repository.NoticeRepository;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.ClassMasterRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지 (F-4.11-3).
 *
 * <h2>조회는 공유, 작성은 제한</h2>
 * 관리자는 전 지점 공지까지 <b>전부 본다</b>. 제한되는 건 쓰는 쪽뿐이다.
 *
 * <h2>★ 범위별 작성 권한</h2>
 * <ul>
 *   <li>{@link NoticeScope#ALL} — <b>본사(전 지점 권한자)만.</b> 지점관리자가 실수로
 *       전 지점에 공지하는 걸 막는다</li>
 *   <li>{@link NoticeScope#BRANCH} — 지점관리자 이상, 자기 지점만</li>
 *   <li>{@link NoticeScope#CLASS} — <b>그 반 담임</b> 또는 지점관리자 이상</li>
 *   <li>{@link NoticeScope#INDIVIDUAL} — 그 학생의 담임 또는 지점관리자 이상</li>
 * </ul>
 *
 * <p><b>전체·지점 공지는 담당선생님(사감)이 못 쓴다.</b> 그쪽은 반 단위 소통이 업무이고,
 * 전 지점·지점 공지는 행정 조직이 낸다.
 *
 * <p>반공지에 지점관리자를 함께 허용한 것은 <b>의도적인 판단</b>이다 — 담임을 아직
 * 지정하지 않은 반이 있을 수 있는데, 담임만 허용하면 그 반은 공지를 아무도 못 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final com.dlab.domain.notice.repository.NoticeReadRepository noticeReadRepository;
    private final AccountRepository accountRepository;
    private final ClassMasterRepository classMasterRepository;
    private final com.dlab.domain.user.repository.AcademyRepository academyRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /** 관리자 목록. 전 지점 공지가 함께 나온다. */
    @Transactional(readOnly = true)
    public List<Notice> findForAdmin(AuthPrincipal me, Short year) {
        short targetYear = year != null ? year : currentYear();
        Long academyId = me.academyScopeFilter();
        return academyId == null
                ? noticeRepository.findForAdminAllAcademy(targetYear)
                : noticeRepository.findForAdmin(academyId, targetYear);
    }

    @Transactional
    public Notice createForAll(AuthPrincipal me, String title, String content) {
        Author author = requireAuthor(me);
        requireHeadOffice(me);
        requireAdministrative(author, "전체 공지");

        Notice notice = noticeRepository.save(
                Notice.ofAll(currentYear(), title, content, author.type(), author.id()));
        log.info("전체 공지 등록: id={}, 작성자={}:{}", notice.getId(), author.type(), author.id());
        return notice;
    }

    @Transactional
    public Notice createForBranch(AuthPrincipal me, Long academyId, String title, String content) {
        Author author = requireAuthor(me);
        requireBranchAdmin(me, academyId);
        requireAdministrative(author, "지점 공지");

        var academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        Notice notice = noticeRepository.save(
                Notice.ofBranch(academy, currentYear(), title, content, author.type(), author.id()));
        log.info("지점 공지 등록: id={}, 지점={}", notice.getId(), academyId);
        return notice;
    }

    @Transactional
    public Notice createForClass(AuthPrincipal me, Long classId, String title, String content) {
        Author author = requireAuthor(me);

        ClassMaster classMaster = classMasterRepository.findById(classId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));
        requireClassWriter(me, author, classMaster);

        Notice notice = noticeRepository.save(Notice.ofClass(
                classMaster, currentYear(), title, content, author.type(), author.id()));
        log.info("반 공지 등록: id={}, 반={}", notice.getId(), classId);
        return notice;
    }

    @Transactional
    public Notice createForIndividual(AuthPrincipal me, Long enrollmentId,
                                      String title, String content) {
        Author author = requireAuthor(me);

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        // 담임은 자기 반 학생에게만 보낼 수 있다
        ClassMaster classMaster = classAssignmentRepository
                .findActiveFixedByEnrollmentId(enrollmentId)
                .map(ClassAssignment::getClassMaster)
                .orElse(null);
        requireIndividualWriter(me, author, enrollment, classMaster);

        Notice notice = noticeRepository.save(Notice.ofIndividual(
                enrollment, currentYear(), title, content, author.type(), author.id()));
        log.info("개별 공지 등록: id={}, 학생={}", notice.getId(), enrollmentId);
        return notice;
    }

    /**
     * 수정.
     *
     * <p><b>범위와 대상은 못 바꾼다</b>(엔티티 참고). 바꿀 수 있게 하면 작성 권한 검사를
     * 한 번만 통과하고 범위를 넓히는 길이 열린다.
     */
    @Transactional
    public Notice update(AuthPrincipal me, Long id, String title, String content,
                         boolean pinned, boolean banner,
                         Instant publishedAt, Instant expiresAt) {
        Notice notice = requireWritable(me, id);
        notice.update(title, content, pinned, banner, publishedAt, expiresAt);
        return notice;
    }

    /**
     * 부분 수정 — 보내지 않은 필드는 그대로 둔다.
     *
     * <p>상단 고정만 켜려고 본문을 함께 보내는 구조였는데, 화면 목록이 낡았으면
     * <b>버튼 한 번에 남의 수정이 덮어써진다.</b>
     */
    @Transactional
    public Notice patch(AuthPrincipal me, Long id, String title, String content,
                        Boolean pinned, Boolean banner,
                        Instant publishedAt, Instant expiresAt) {
        Notice notice = requireWritable(me, id);
        notice.patch(title, content, pinned, banner, publishedAt, expiresAt);
        return notice;
    }

    /**
     * 열람 기록.
     *
     * <p><b>중복 호출이 정상 경로다</b> — 앱이 상세 화면에 들어올 때마다 부른다.
     * 이미 있으면 아무것도 하지 않는다(최초 열람 시각을 덮어쓰지 않는다).
     *
     * <p><b>내게 보이는 공지인지 먼저 확인한다</b> — 안 그러면 번호를 바꿔가며
     * 남의 반 공지를 읽음 처리해 열람 수를 부풀릴 수 있다.
     */
    @Transactional
    public void markRead(Long enrollmentId, Long noticeId) {
        Notice notice = readOne(enrollmentId, noticeId);
        if (noticeReadRepository.existsByNoticeIdAndEnrollmentId(noticeId, enrollmentId)) {
            return;
        }
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        try {
            noticeReadRepository.save(new NoticeRead(notice, enrollment, Instant.now(clock)));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 같은 학생이 동시에 두 번 열었다. 유니크 제약이 막아준 것이라 정상이다
            log.debug("공지 열람 중복: noticeId={}, enrollmentId={}", noticeId, enrollmentId);
        }
    }

    /** 공지별 열람 수. 목록이 공지마다 세면 쿼리가 행 수만큼 나가서 한 번에 받는다. */
    @Transactional(readOnly = true)
    public Map<Long, Long> readCounts(List<Long> noticeIds) {
        if (noticeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        noticeReadRepository.countByNoticeIds(noticeIds)
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return result;
    }

    /** 삭제(soft). 물리 삭제하면 "그때 무슨 공지가 나갔나"에 답할 수 없다. */
    @Transactional
    public void delete(AuthPrincipal me, Long id) {
        requireWritable(me, id).markDeleted();
        log.info("공지 삭제: id={}, 처리자={}", id, me.accountId());
    }

    /**
     * 앱 피드 — 학생 본인에게 보이는 공지.
     *
     * <p>전 지점 + 내 지점 + 내 반 + 나 개인을 합쳐서 내린다.
     * <b>예약 발행·만료를 여기서 거른다</b> — 상태 컬럼으로 두면 예약 시각이 지나도
     * 배치가 돌기 전까지 안 보인다.
     */
    @Transactional(readOnly = true)
    public List<Notice> feed(Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        Long classId = classAssignmentRepository.findActiveFixedByEnrollmentId(enrollmentId)
                .map(a -> a.getClassMaster().getId())
                .orElse(null);

        Instant now = Instant.now(clock);
        return noticeRepository.findFeed(
                        enrollment.getAcademy().getId(), classId, enrollmentId,
                        enrollment.getYear())
                .stream()
                .filter(n -> n.isVisibleAt(now))
                .toList();
    }

    /** 앱 홈 배너. 피드 중 배너 표시분만. */
    @Transactional(readOnly = true)
    public List<Notice> banners(Long enrollmentId) {
        return feed(enrollmentId).stream().filter(Notice::isBanner).toList();
    }

    /**
     * 상세.
     *
     * <p><b>내게 보이는 공지인지 확인한다.</b> id만으로 열어주면 다른 반 공지를
     * 번호를 바꿔가며 읽을 수 있다.
     */
    @Transactional(readOnly = true)
    public Notice readOne(Long enrollmentId, Long noticeId) {
        return feed(enrollmentId).stream()
                .filter(n -> n.getId().equals(noticeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));
    }

    // ── 권한 ─────────────────────────────────────────────

    /**
     * 수정·삭제 권한.
     *
     * <p>작성 때와 같은 기준을 다시 건다 — 만들 때만 검사하면 남의 반 공지를
     * 고치는 길이 열린다.
     */
    private Notice requireWritable(AuthPrincipal me, Long id) {
        Notice notice = noticeRepository.findById(id)
                .filter(n -> !n.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));
        Author author = requireAuthor(me);

        switch (notice.getScope()) {
            case ALL -> {
                requireHeadOffice(me);
                requireAdministrative(author, "전체 공지");
            }
            case BRANCH -> {
                requireBranchAdmin(me, notice.getAcademy().getId());
                requireAdministrative(author, "지점 공지");
            }
            case CLASS -> requireClassWriter(me, author, notice.getClassMaster());
            case INDIVIDUAL -> requireIndividualWriter(me, author, notice.getEnrollment(),
                    classAssignmentRepository
                            .findActiveFixedByEnrollmentId(notice.getEnrollment().getId())
                            .map(ClassAssignment::getClassMaster)
                            .orElse(null));
        }
        return notice;
    }

    /** 전 지점 공지는 본사만. 지점관리자가 실수로 전 지점에 쏘는 걸 막는다. */
    private void requireHeadOffice(AuthPrincipal me) {
        if (!me.allAcademy()) {
            throw new BusinessException(ErrorCode.NOTICE_SCOPE_FORBIDDEN,
                    "전 지점 공지는 본사만 작성할 수 있습니다.");
        }
    }

    private void requireBranchAdmin(AuthPrincipal me, Long academyId) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (!me.hasRole(Role.SUPER_ADMIN) && !me.hasRole(Role.BRANCH_ADMIN)) {
            throw new BusinessException(ErrorCode.NOTICE_SCOPE_FORBIDDEN,
                    "지점 공지는 지점관리자 이상만 작성할 수 있습니다.");
        }
    }

    /** 담당선생님(사감)은 반 단위 소통이 업무다 — 전 지점·지점 공지는 행정이 낸다. */
    private void requireAdministrative(Author author, String what) {
        if (author.type() != NoticeAuthorType.EMPLOYEE) {
            throw new BusinessException(ErrorCode.NOTICE_SCOPE_FORBIDDEN,
                    what + "는 행정선생님만 작성할 수 있습니다.");
        }
    }

    private void requireClassWriter(AuthPrincipal me, Author author, ClassMaster classMaster) {
        if (!me.canAccessAcademy(classMaster.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (isHomeroomOf(author, classMaster)
                || me.hasRole(Role.SUPER_ADMIN) || me.hasRole(Role.BRANCH_ADMIN)) {
            return;
        }
        throw new BusinessException(ErrorCode.NOTICE_SCOPE_FORBIDDEN,
                "반 공지는 그 반 담임 또는 지점관리자만 작성할 수 있습니다.");
    }

    private void requireIndividualWriter(AuthPrincipal me, Author author,
                                         StudentEnrollment enrollment, ClassMaster classMaster) {
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if ((classMaster != null && isHomeroomOf(author, classMaster))
                || me.hasRole(Role.SUPER_ADMIN) || me.hasRole(Role.BRANCH_ADMIN)) {
            return;
        }
        throw new BusinessException(ErrorCode.NOTICE_SCOPE_FORBIDDEN,
                "개별 공지는 담임 또는 지점관리자만 작성할 수 있습니다.");
    }

    private boolean isHomeroomOf(Author author, ClassMaster classMaster) {
        if (author.type() != NoticeAuthorType.TEACHER) {
            return false;
        }
        Teacher homeroom = classMaster.getHomeroomTeacher();
        return homeroom != null && homeroom.getId().equals(author.id());
    }

    /**
     * 작성자 확인.
     *
     * <p>학생·학부모 계정은 공지를 쓸 수 없다 — 컨트롤러가 관리자 구획이라 걸러지지만,
     * 여기서도 막아야 도메인만 재사용하는 경로가 생겨도 안전하다.
     */
    private Author requireAuthor(AuthPrincipal me) {
        Account account = accountRepository.findById(me.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (account.getEmployee() != null) {
            return new Author(NoticeAuthorType.EMPLOYEE, account.getEmployee().getId());
        }
        if (account.getTeacher() != null) {
            return new Author(NoticeAuthorType.TEACHER, account.getTeacher().getId());
        }
        throw new BusinessException(ErrorCode.NOTICE_SCOPE_FORBIDDEN,
                "공지를 작성할 수 있는 계정이 아닙니다.");
    }

    private short currentYear() {
        return (short) LocalDate.now(clock).getYear();
    }

    /** 작성자 다형 참조 — 행정(employee)과 담당선생님(teacher)은 다른 테이블이다. */
    private record Author(NoticeAuthorType type, Long id) {
    }
}
