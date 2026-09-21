package com.dlab.domain.consult.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.consult.entity.ConsultLog;
import com.dlab.domain.consult.entity.ConsultMethod;
import com.dlab.domain.consult.entity.ConsultTag;
import com.dlab.domain.consult.entity.ConsultType;
import com.dlab.domain.consult.repository.ConsultLogRepository;
import com.dlab.domain.consult.repository.ConsultTagRepository;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.TeacherRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담 일지 (F-4.11-4).
 *
 * <h2>범위 — 일지까지다</h2>
 * 학부모용 <b>리포트</b>는 다른 도메인 셋에 얹혀 있어 아직 못 만든다:
 * 성적 상세(F-4.6-1) · 과목별 이행률 별점(F-4.11-2, 표기 정합 I-19 미확정) ·
 * 신상기록부 작성 여부(F-4.11-8).
 *
 * <h2>상담은 담당선생님이 한다</h2>
 * {@code teacher}만 상담자가 된다 — 행정({@code employee})은 상담 업무가 아니다.
 * 담임은 반 배정에서 자동으로 나오므로 별도 매핑을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultService {

    private final ConsultLogRepository logRepository;
    private final ConsultTagRepository tagRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final TeacherRepository teacherRepository;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;
    private final com.dlab.domain.user.repository.AcademyRepository academyRepository;
    private final com.dlab.domain.user.service.HomeroomScopeService homeroomScopeService;
    private final Clock clock;

    // ── 일지 ──────────────────────────────────────────────────

    @Transactional
    public ConsultLog write(AuthPrincipal me, Long enrollmentId, Long teacherId,
                            ConsultType type, ConsultMethod method, LocalDate consultedAt,
                            String placeNote, String content, String actionPlan,
                            LocalDate nextDueDate, List<Long> tagIds,
                            com.dlab.domain.consult.entity.ParentShare parentShare,
                            Short durationMinutes) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        Teacher teacher = resolveTeacher(me, enrollment, teacherId);

        if (consultedAt.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "미래 날짜로 상담을 기록할 수 없습니다.");
        }

        ConsultLog written = logRepository.save(new ConsultLog(
                enrollment, teacher, type, method, consultedAt, content));
        written.update(type, method, consultedAt, placeNote, content,
                actionPlan, false, nextDueDate, parentShare, durationMinutes);
        written.replaceTags(resolveTags(enrollment, tagIds));

        log.info("상담 일지 작성: enrollmentId={}, 유형={}, 일자={}",
                enrollmentId, type, consultedAt);
        return written;
    }

    @Transactional
    public ConsultLog update(AuthPrincipal me, Long logId, ConsultType type, ConsultMethod method,
                             LocalDate consultedAt, String placeNote, String content,
                             String actionPlan, boolean actionDone, LocalDate nextDueDate,
                             List<Long> tagIds,
                             com.dlab.domain.consult.entity.ParentShare parentShare,
                             Short durationMinutes) {

        ConsultLog target = requireLog(me, logId);
        target.update(type, method, consultedAt, placeNote, content, actionPlan,
                actionDone, nextDueDate, parentShare, durationMinutes);
        target.replaceTags(resolveTags(target.getEnrollment(), tagIds));
        return target;
    }

    /** 삭제(soft). 상담 이력은 다음 상담의 근거라 물리 삭제하지 않는다. */
    @Transactional
    public void delete(AuthPrincipal me, Long logId) {
        requireLog(me, logId).markDeleted();
    }

    @Transactional(readOnly = true)
    public List<ConsultLog> findByStudent(AuthPrincipal me, Long enrollmentId) {
        requireEnrollment(me, enrollmentId);
        return logRepository.findByEnrollment(enrollmentId);
    }

    @Transactional(readOnly = true)
    public List<ConsultLog> findByPeriod(AuthPrincipal me, Long requestedAcademyId,
                                         LocalDate from, LocalDate to,
                                         Long teacherId) {
        Long academyId = me.requireAcademyScope(requestedAcademyId);
        short year = (short) from.getYear();

        // ★ 담임은 맡은 학생의 상담만 본다(예외 지정 포함). 상담 내용에 학생 신상이 그대로 들어 있다.
        var scope = homeroomScopeService.enrollmentIdsOf(me, year);

        return logRepository.findByPeriod(academyId, year, from, to).stream()
                .filter(l -> teacherId == null
                        || (l.getTeacher() != null && l.getTeacher().getId().equals(teacherId)))
                .filter(l -> com.dlab.domain.user.service.HomeroomScopeService.allows(
                        scope, l.getEnrollment() == null ? null : l.getEnrollment().getId()))
                .toList();
    }

    // ── 현황 ──────────────────────────────────────────────────

    /**
     * 상담 현황 — 재원생 전원 + 최근 상담.
     *
     * <p><b>상담을 한 번도 안 한 학생이 목록에 남아야 한다.</b> 일지만 보여주면
     * 미상담자가 화면에서 사라져 "누구를 아직 안 만났나"를 알 수 없다 — 이 화면의 목적이다.
     *
     * <p>신상기록부 작성 여부는 아직 못 채운다(F-4.11-8 대기) — {@code null}로 내리고
     * 화면이 "미확인"으로 표시한다. {@code false}로 내리면 "작성 안 함"으로 읽혀
     * 없는 사실을 만들어낸다.
     */
    @Transactional(readOnly = true)
    public List<ConsultStatusRow> status(AuthPrincipal me, Long requestedAcademyId,
                                        Long teacherId) {
        Long academyId = me.requireAcademyScope(requestedAcademyId);
        List<StudentEnrollment> targets = enrollmentRepository.findCurrentByAcademyId(academyId)
                .stream()
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .toList();
        if (targets.isEmpty()) {
            return List.of();
        }

        short year = targets.get(0).getYear();
        Map<Long, ConsultLog> latest = latestByEnrollment(academyId, year);
        Map<Long, ClassAssignment> classes = classesOf(targets);
        LocalDate today = LocalDate.now(clock);

        // ★ 담임 범위. teacherId 는 화면이 고르는 필터라 빼고 부르면 지점 전체가 나갔다.
        //   반이 아니라 학생으로 거른다 — 담임 예외 지정된 학생이 새 담임에게 보여야 한다
        var studentFilter = homeroomScopeService.resolveStudentFilter(me, year, null);
        if (studentFilter.blocksEverything()) {
            return List.of();
        }

        return targets.stream()
                .filter(e -> studentFilter.matches(e.getId()))
                .filter(e -> matchesTeacher(e, classes.get(e.getId()), teacherId))
                .map(e -> {
                    ConsultLog last = latest.get(e.getId());
                    ClassAssignment assignment = classes.get(e.getId());
                    return new ConsultStatusRow(
                            e.getId(),
                            e.getStudentNo(),
                            e.getStudent().getName(),
                            assignment == null ? null : assignment.getClassMaster().getName(),
                            homeroomName(e, assignment),
                            last == null ? null : last.getConsultedAt(),
                            last == null ? null : last.getConsultType(),
                            last == null ? null : last.getNextDueDate(),
                            overdueDays(last, today),
                            last == null,
                            null);   // 신상기록부 — F-4.11-8 대기
                })
                .sorted(Comparator.comparing(ConsultStatusRow::studentNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 다음 상담 예정일이 지난 일수.
     *
     * <p>화면이 "지연 3일"을 빨간색으로 띄운다. 예정일이 없거나 아직 안 지났으면 {@code 0}.
     */
    private long overdueDays(ConsultLog last, LocalDate today) {
        if (last == null || last.getNextDueDate() == null
                || !today.isAfter(last.getNextDueDate())) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(last.getNextDueDate(), today);
    }

    private Map<Long, ConsultLog> latestByEnrollment(Long academyId, short year) {
        Map<Long, ConsultLog> result = new HashMap<>();
        // 최근 순으로 오므로 먼저 들어온 것이 최신이다
        logRepository.findAllByYear(academyId, year)
                .forEach(l -> result.putIfAbsent(l.getEnrollment().getId(), l));
        return result;
    }

    private Map<Long, ClassAssignment> classesOf(List<StudentEnrollment> targets) {
        Map<Long, ClassAssignment> result = new HashMap<>();
        targets.forEach(e -> classAssignmentRepository
                .findActiveFixedByEnrollmentId(e.getId())
                .ifPresent(a -> result.put(e.getId(), a)));
        return result;
    }

    /**
     * 담임 필터 — <b>그 학생의 담임</b>(예외 지정 ?? 반 담임)으로 판정한다.
     *
     * <p>예외 지정된 학생은 지정된 선생님의 상담 대상이다. 반 담임 기준으로 두면 맡은 학생이
     * 목록에서 빠지고 맡지 않은 학생이 뜬다.
     */
    private boolean matchesTeacher(StudentEnrollment enrollment, ClassAssignment assignment,
                                   Long teacherId) {
        if (teacherId == null) {
            return true;
        }
        var homeroom = com.dlab.domain.user.service.HomeroomResolver.of(enrollment, assignment);
        return homeroom != null && homeroom.getId().equals(teacherId);
    }

    private String homeroomName(StudentEnrollment enrollment, ClassAssignment assignment) {
        var homeroom = com.dlab.domain.user.service.HomeroomResolver.of(enrollment, assignment);
        return homeroom == null ? null : homeroom.getName();
    }

    // ── 태그 마스터 ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ConsultTag> tags(AuthPrincipal me, Long requestedAcademyId, short year,
                                 boolean includeInactive) {
        Long academyId = me.requireAcademyScope(requestedAcademyId);
        return includeInactive
                ? tagRepository.findAll(academyId, year)
                : tagRepository.findActive(academyId, year);
    }

    @Transactional
    public ConsultTag createTag(AuthPrincipal me, Long requestedAcademyId, short year,
                                ConsultType type, String name, short sortOrder) {
        var academy = academyRepository.findById(me.requireAcademyScope(requestedAcademyId))
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        return tagRepository.save(new ConsultTag(academy, year, type, name, sortOrder));
    }

    @Transactional
    public ConsultTag updateTag(AuthPrincipal me, Long tagId, String name, ConsultType type,
                                short sortOrder, short maxDisplay, boolean active) {
        ConsultTag tag = tagRepository.findById(tagId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "태그를 찾을 수 없습니다."));
        if (!me.canAccessAcademy(tag.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        tag.update(name, type, sortOrder, maxDisplay, active);
        return tag;
    }

    // ── 내부 ──────────────────────────────────────────────────

    /**
     * 상담자 결정 — <b>지정 &gt; 로그인한 본인 &gt; 학생 담임</b> 순이다.
     *
     * <p>화면이 {@code teacherId}를 보낼 방법이 없다. 토큰에는 {@code accountId}만 있고
     * 그것을 교사와 잇는 API가 없어서, 안 보내면 <b>작성자가 빈 채로 남아</b> 상담 이력에서
     * "누가 상담했는지"가 사라진다. 그래서 <b>서버가 로그인 주체로 채운다</b> —
     * 클라이언트가 남의 id를 실어 보낼 수 없다는 점에서도 이쪽이 안전하다.
     *
     * <p>담임 폴백은 그대로 둔다. 행정직원이 대신 입력하는 경우가 있어, 그때는 그 학생의
     * 담임이 상담자가 되는 것이 실제 운영과 맞다.
     */
    private Teacher resolveTeacher(AuthPrincipal me, StudentEnrollment enrollment, Long teacherId) {
        if (teacherId != null) {
            return teacherRepository.findById(teacherId)
                    .filter(t -> !t.isDeleted())
                    .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                            "선생님을 찾을 수 없습니다."));
        }
        Teacher self = loginTeacher(me);
        if (self != null) {
            return self;
        }
        return classAssignmentRepository.findActiveFixedByEnrollmentId(enrollment.getId())
                .map(ClassAssignment::getHomeroomTeacher)
                .orElse(null);
    }

    /** 로그인 주체가 담당선생님이면 그 교사. 행정직원·관리자면 없다. */
    private Teacher loginTeacher(AuthPrincipal me) {
        if (me == null || me.accountId() == null) {
            return null;
        }
        return accountRepository.findById(me.accountId())
                .map(com.dlab.domain.user.entity.Account::getTeacher)
                .filter(t -> t != null && !t.isDeleted())
                .orElse(null);
    }

    private List<ConsultTag> resolveTags(StudentEnrollment enrollment, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        List<ConsultTag> tags = tagRepository.findAllById(tagIds);
        tags.forEach(t -> {
            if (!t.getAcademy().getId().equals(enrollment.getAcademy().getId())) {
                throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
            }
        });
        return tags;
    }

    private ConsultLog requireLog(AuthPrincipal me, Long logId) {
        ConsultLog found = logRepository.findDetail(logId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "상담 일지를 찾을 수 없습니다."));
        if (!me.canAccessAcademy(found.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return found;
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /**
     * 현황 한 줄.
     *
     * @param neverConsulted 초도상담 미실시. <b>화면이 빨간 경고를 띄운다</b>
     * @param overdueDays    다음 상담 예정일이 지난 일수. 0이면 정상
     * @param profileWritten 신상기록부 작성 여부 — <b>지금은 항상 {@code null}</b>(F-4.11-8 대기).
     *                       {@code false}로 내리면 "작성 안 함"으로 읽혀 없는 사실을 만든다
     */
    public record ConsultStatusRow(
            Long enrollmentId,
            String studentNo,
            String name,
            String className,
            String homeroomTeacherName,
            LocalDate lastConsultedAt,
            ConsultType lastConsultType,
            LocalDate nextDueDate,
            long overdueDays,
            boolean neverConsulted,
            Boolean profileWritten) {
    }
}
