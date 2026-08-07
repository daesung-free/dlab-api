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
    private final com.dlab.domain.user.repository.AcademyRepository academyRepository;
    private final Clock clock;

    // ── 일지 ──────────────────────────────────────────────────

    @Transactional
    public ConsultLog write(AuthPrincipal me, Long enrollmentId, Long teacherId,
                            ConsultType type, ConsultMethod method, LocalDate consultedAt,
                            String placeNote, String content, String actionPlan,
                            LocalDate nextDueDate, List<Long> tagIds) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        Teacher teacher = resolveTeacher(enrollment, teacherId);

        if (consultedAt.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "미래 날짜로 상담을 기록할 수 없습니다.");
        }

        ConsultLog written = logRepository.save(new ConsultLog(
                enrollment, teacher, type, method, consultedAt, content));
        written.update(type, method, consultedAt, placeNote, content,
                actionPlan, false, nextDueDate);
        written.replaceTags(resolveTags(enrollment, tagIds));

        log.info("상담 일지 작성: enrollmentId={}, 유형={}, 일자={}",
                enrollmentId, type, consultedAt);
        return written;
    }

    @Transactional
    public ConsultLog update(AuthPrincipal me, Long logId, ConsultType type, ConsultMethod method,
                             LocalDate consultedAt, String placeNote, String content,
                             String actionPlan, boolean actionDone, LocalDate nextDueDate,
                             List<Long> tagIds) {

        ConsultLog target = requireLog(me, logId);
        target.update(type, method, consultedAt, placeNote, content, actionPlan,
                actionDone, nextDueDate);
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
    public List<ConsultLog> findByPeriod(AuthPrincipal me, LocalDate from, LocalDate to,
                                         Long teacherId) {
        Long academyId = academyOf(me);
        short year = (short) from.getYear();

        return logRepository.findByPeriod(academyId, year, from, to).stream()
                .filter(l -> teacherId == null
                        || (l.getTeacher() != null && l.getTeacher().getId().equals(teacherId)))
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
    public List<ConsultStatusRow> status(AuthPrincipal me, Long teacherId) {
        Long academyId = academyOf(me);
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

        return targets.stream()
                .filter(e -> matchesTeacher(classes.get(e.getId()), teacherId))
                .map(e -> {
                    ConsultLog last = latest.get(e.getId());
                    ClassAssignment assignment = classes.get(e.getId());
                    return new ConsultStatusRow(
                            e.getId(),
                            e.getStudentNo(),
                            e.getStudent().getName(),
                            assignment == null ? null : assignment.getClassMaster().getName(),
                            assignment == null || assignment.getHomeroomTeacher() == null
                                    ? null : assignment.getHomeroomTeacher().getName(),
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

    private boolean matchesTeacher(ClassAssignment assignment, Long teacherId) {
        if (teacherId == null) {
            return true;
        }
        return assignment != null
                && assignment.getHomeroomTeacher() != null
                && assignment.getHomeroomTeacher().getId().equals(teacherId);
    }

    // ── 태그 마스터 ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ConsultTag> tags(AuthPrincipal me, short year, boolean includeInactive) {
        Long academyId = academyOf(me);
        return includeInactive
                ? tagRepository.findAll(academyId, year)
                : tagRepository.findActive(academyId, year);
    }

    @Transactional
    public ConsultTag createTag(AuthPrincipal me, short year, ConsultType type,
                                String name, short sortOrder) {
        var academy = academyRepository.findById(academyOf(me))
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
     * 상담자 결정.
     *
     * <p>지정하지 않으면 <b>그 학생의 담임</b>이 된다 — 반 배정에서 자동으로 나온다.
     */
    private Teacher resolveTeacher(StudentEnrollment enrollment, Long teacherId) {
        if (teacherId != null) {
            return teacherRepository.findById(teacherId)
                    .filter(t -> !t.isDeleted())
                    .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                            "선생님을 찾을 수 없습니다."));
        }
        return classAssignmentRepository.findActiveFixedByEnrollmentId(enrollment.getId())
                .map(ClassAssignment::getHomeroomTeacher)
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

    private Long academyOf(AuthPrincipal me) {
        Long academyId = me.academyScopeFilter();
        if (academyId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        return academyId;
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
