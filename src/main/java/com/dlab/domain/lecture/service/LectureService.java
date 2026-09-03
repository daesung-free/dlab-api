package com.dlab.domain.lecture.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.lecture.entity.*;
import com.dlab.domain.lecture.repository.*;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 특강·설명회 (F-4.10-4 기초설정 · F-4.7 관리 · 앱 A-15).
 *
 * <p><b>결제는 없다.</b> 0803 답변서가 *"특강 신청+결제 가능 여부 검토 중"*이고
 * {@code payment} 도메인 자체가 아직 없다. {@code fee}는 안내용이다.
 *
 * <p><b>대기자는 별도 테이블이 아니다</b> — 정원이 차면 같은 신청 행이 {@code WAITLISTED}로
 * 들어가고 자리가 나면 승격된다. 여기의 대기자는 <b>F-4.2 입학 대기자와 다른 것</b>이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureService {

    private final LectureRepository lectureRepository;
    private final LectureSessionRepository sessionRepository;
    private final LectureApplicationRepository applicationRepository;
    private final LectureAttendanceRepository attendanceRepository;
    private final AcademyRepository academyRepository;
    private final com.dlab.domain.user.repository.ClassAssignmentRepository classAssignmentRepository;
    private final com.dlab.domain.user.repository.TeacherRepository teacherRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final com.dlab.domain.user.service.AppScopeResolver scopeResolver;
    private final Clock clock;

    /**
     * 정원 대비 현황.
     *
     * @param capacity {@code null}이면 무제한
     */
    public record Headcount(Integer capacity, long confirmed, long waitlisted) {

        public boolean isFull() {
            return capacity != null && confirmed >= capacity;
        }
    }

    // ── 마스터 (F-4.10-4) ────────────────────────────────────────

    @Transactional
    public Lecture create(Long academyId, short year, LectureType type, String name,
                          AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        Lecture lecture = lectureRepository.save(new Lecture(academy, year, type, name));
        log.info("특강 생성: id={}, name={}", lecture.getId(), name);
        return lecture;
    }

    @Transactional(readOnly = true)
    public List<Lecture> findAll(Long academyId, short year, LectureStatus status,
                                 AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return status == null
                ? lectureRepository.findByAcademyIdAndYearAndDeletedFalseOrderByStartDateDescIdDesc(
                        academyId, year)
                : lectureRepository
                        .findByAcademyIdAndYearAndStatusAndDeletedFalseOrderByStartDateDescIdDesc(
                                academyId, year, status);
    }

    /** 앱 목록 — 노출 설정된 것만(0803 "개설 시에만 노출"). */
    @Transactional(readOnly = true)
    public List<Lecture> findVisible(Long academyId, short year) {
        return lectureRepository.findVisible(academyId, year);
    }

    @Transactional
    public Lecture update(Long lectureId, String name, String description, Integer capacity,
                          Instant applyFrom, Instant applyTo, LocalDate startDate,
                          LocalDate endDate, Integer fee, Long teacherId,
                          AuthPrincipal principal) {
        Lecture lecture = require(lectureId, principal);
        // ★ 정원을 현재 확정 인원보다 낮추지 못하게 막는다.
        //   허용하면 이미 확정된 학생이 정원 밖으로 밀려나는데, 누구를 뺄지 정할 방법이 없다.
        if (capacity != null) {
            long confirmed = applicationRepository.countByLectureIdAndStatus(
                    lectureId, ApplicationStatus.APPLIED);
            if (capacity < confirmed) {
                throw new BusinessException(ErrorCode.LECTURE_CAPACITY_BELOW_CONFIRMED,
                        "이미 확정된 인원(%d명)보다 적은 정원으로 줄일 수 없습니다.".formatted(confirmed));
            }
        }
        lecture.update(name, description, capacity, applyFrom, applyTo, startDate, endDate, fee);
        if (teacherId != null) {
            lecture.changeTeacher(teacherRepository.findById(teacherId)
                    .filter(t -> !t.isDeleted())
                    .filter(t -> t.getAcademy().getId().equals(lecture.getAcademy().getId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                            "선생님을 찾을 수 없습니다.")));
        }
        return lecture;
    }

    /**
     * 상태 변경.
     *
     * <p>{@code OPEN}으로 열 때 <b>노출도 함께 켜지지는 않는다</b> — 노출은 별개 축이라
     * 관리자가 명시적으로 켠다(준비 중인 특강을 미리 만들어두는 흐름이 있다).
     */
    @Transactional
    public Lecture changeStatus(Long lectureId, LectureStatus status, AuthPrincipal principal) {
        Lecture lecture = require(lectureId, principal);
        lecture.changeStatus(status);
        return lecture;
    }

    @Transactional
    public Lecture changeVisible(Long lectureId, boolean visible, AuthPrincipal principal) {
        Lecture lecture = require(lectureId, principal);
        lecture.changeVisible(visible);
        return lecture;
    }

    // ── 회차 ─────────────────────────────────────────────────────

    @Transactional
    public LectureSession addSession(Long lectureId, LocalDate date, LocalTime start,
                                     LocalTime end, String room, AuthPrincipal principal) {
        Lecture lecture = require(lectureId, principal);
        short next = (short) (sessionRepository.findMaxSessionNo(lectureId) + 1);
        return sessionRepository.save(
                new LectureSession(lecture, next, date, start, end, room));
    }

    @Transactional(readOnly = true)
    public List<LectureSession> sessions(Long lectureId, AuthPrincipal principal) {
        require(lectureId, principal);
        return sessionRepository.findByLectureIdAndDeletedFalseOrderBySessionNo(lectureId);
    }

    // ── 신청 (앱 A-15) ───────────────────────────────────────────

    /**
     * 로그인한 학생 계정 → 올해 등록 건.
     *
     * <p>앱 요청은 계정만 들고 오는데 특강 신청은 <b>등록 건</b>에 붙는다(학번·지점·학년이 거기 있다).
     * 학생 계정이 아니면 여기서 막힌다 — 경로만 알면 학부모 계정으로도 호출할 수 있다.
     */
    @Transactional(readOnly = true)
    public StudentEnrollment currentEnrollmentOf(Long accountId) {
        return scopeResolver.requireStudent(accountId, "특강 신청");
    }

    /**
     * 신청. <b>정원이 차 있으면 대기로 들어간다.</b>
     *
     * <p><b>★ 행 락으로 직렬화한다.</b> "인원 세기 → 정원 비교 → INSERT"는 동시 신청에
     * 그대로 뚫린다 — 같은 유형의 결함을 기숙사 정원에서 겪었다(2인실에 8명).
     * 애플리케이션 락은 다중 인스턴스에서 무의미하다.
     *
     * @return 확정({@code APPLIED}) 또는 대기({@code WAITLISTED}) 상태의 신청
     */
    @Transactional
    public LectureApplication apply(Long lectureId, Long enrollmentId) {
        Instant now = Instant.now(clock);
        Lecture lecture = lectureRepository.findByIdForUpdate(lectureId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_NOT_FOUND));

        if (!lecture.acceptsApplicationAt(now)) {
            throw new BusinessException(ErrorCode.LECTURE_NOT_ACCEPTING);
        }
        applicationRepository.findActive(lectureId, enrollmentId).ifPresent(a -> {
            throw new BusinessException(ErrorCode.LECTURE_ALREADY_APPLIED);
        });

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        // 다른 지점 특강에 신청되면 명단·출석부가 섞인다
        if (!enrollment.getAcademy().getId().equals(lecture.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        long confirmed = applicationRepository.countByLectureIdAndStatus(
                lectureId, ApplicationStatus.APPLIED);
        ApplicationStatus status = lecture.hasCapacity() && confirmed >= lecture.getCapacity()
                ? ApplicationStatus.WAITLISTED
                : ApplicationStatus.APPLIED;

        return applicationRepository.save(
                new LectureApplication(lecture, enrollment, status, now));
    }

    /**
     * 취소.
     *
     * <p><b>확정자가 빠지면 대기 1번을 자동으로 올린다.</b> 수동으로 두면 자리가 비어 있는데
     * 대기자가 계속 기다리는 상태가 되고, 관리자가 매번 확인해야 한다.
     *
     * @return 승격된 신청. 없으면 {@code null}
     */
    @Transactional
    public LectureApplication cancel(Long applicationId, Long requesterEnrollmentId) {
        LectureApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_APPLICATION_NOT_FOUND));

        // 앱에서 남의 신청을 취소하지 못하게. 관리자 경로는 null을 넘긴다.
        if (requesterEnrollmentId != null
                && !application.getEnrollment().getId().equals(requesterEnrollmentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!application.getStatus().isActive()) {
            throw new BusinessException(ErrorCode.LECTURE_ALREADY_CANCELED);
        }

        boolean wasConfirmed = application.getStatus() == ApplicationStatus.APPLIED;
        application.cancel(Instant.now(clock));

        if (!wasConfirmed) {
            return null;
        }
        return applicationRepository
                .findFirstByLectureIdAndStatusOrderByAppliedAtAsc(
                        application.getLecture().getId(), ApplicationStatus.WAITLISTED)
                .map(next -> {
                    next.promote();
                    log.info("대기자 승격: applicationId={}, lectureId={}",
                            next.getId(), application.getLecture().getId());
                    return next;
                })
                .orElse(null);
    }

    /** 관리자 수동 승격 (F-4.7 "일괄 이동"). 정원을 넘겨도 관리자 판단을 존중한다. */
    @Transactional
    public LectureApplication promote(Long applicationId, AuthPrincipal principal) {
        LectureApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_APPLICATION_NOT_FOUND));
        verifyAccess(application.getAcademy().getId(), principal);
        if (!application.isWaitlisted()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "대기 상태가 아닙니다.");
        }
        application.promote();
        return application;
    }

    @Transactional(readOnly = true)
    public List<LectureApplication> roster(Long lectureId, AuthPrincipal principal) {
        require(lectureId, principal);
        return applicationRepository.findRoster(lectureId);
    }

    /**
     * 신청자 명단 + 반. 명단에서 <b>어느 반 학생인지</b>를 알 수 없으면
     * 담임에게 넘길 때 다시 대조해야 한다.
     *
     * <p>반을 신청자마다 조회하면 쿼리가 인원수만큼 나가므로 한 번에 받아 붙인다.
     */
    @Transactional(readOnly = true)
    public List<RosterEntry> rosterDetailed(Long lectureId, AuthPrincipal principal) {
        List<LectureApplication> applications = roster(lectureId, principal);

        List<Long> enrollmentIds = applications.stream()
                .map(a -> a.getEnrollment().getId()).toList();
        java.util.Map<Long, String> classNames = new java.util.HashMap<>();
        if (!enrollmentIds.isEmpty()) {
            classAssignmentRepository.findActiveFixedByEnrollmentIds(enrollmentIds)
                    .forEach(ca -> classNames.put(ca.getEnrollment().getId(),
                            ca.getClassMaster().getName()));
        }

        return applications.stream()
                .map(a -> new RosterEntry(a, classNames.get(a.getEnrollment().getId())))
                .toList();
    }

    /** @param className 고정반. 미배정이면 비어 있다 */
    public record RosterEntry(LectureApplication application, String className) {
    }

    @Transactional(readOnly = true)
    public List<LectureApplication> myApplications(Long enrollmentId) {
        return applicationRepository.findMine(enrollmentId);
    }

    @Transactional(readOnly = true)
    public Headcount headcount(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_NOT_FOUND));
        return new Headcount(
                lecture.getCapacity(),
                applicationRepository.countByLectureIdAndStatus(lectureId, ApplicationStatus.APPLIED),
                applicationRepository.countByLectureIdAndStatus(lectureId, ApplicationStatus.WAITLISTED));
    }

    // ── 출석부 (F-4.7) ───────────────────────────────────────────

    /** 출석부 대상 — 확정자만. 대기·취소자는 나오지 않는다. */
    @Transactional(readOnly = true)
    public List<LectureApplication> attendanceTargets(Long lectureId, AuthPrincipal principal) {
        require(lectureId, principal);
        return applicationRepository.findConfirmed(lectureId);
    }

    /**
     * 출석 체크. 같은 회차·같은 신청이면 덮어쓴다.
     *
     * <p>강사가 손으로 체크하는 값이라 정정이 잦다 — 새 행을 쌓으면 어느 게 최종인지 모른다.
     */
    @Transactional
    public LectureAttendance markAttendance(Long sessionId, Long applicationId,
                                            LectureAttendanceStatus status, String memo,
                                            AuthPrincipal principal) {
        LectureSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_SESSION_NOT_FOUND));
        verifyAccess(session.getAcademy().getId(), principal);

        LectureApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_APPLICATION_NOT_FOUND));
        // 다른 특강의 신청을 이 회차 출석부에 넣으면 명단이 오염된다
        if (!application.getLecture().getId().equals(session.getLecture().getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 특강의 신청이 아닙니다.");
        }

        return attendanceRepository.findBySessionIdAndApplicationId(sessionId, applicationId)
                .map(existing -> {
                    existing.change(status, memo);
                    return existing;
                })
                .orElseGet(() -> attendanceRepository.save(
                        new LectureAttendance(session, application, status, memo)));
    }

    @Transactional(readOnly = true)
    public List<LectureAttendance> attendances(Long sessionId) {
        return attendanceRepository.findBySessionIdAndDeletedFalse(sessionId);
    }

    // ─────────────────────────────────────────────────────────────

    private Lecture require(Long lectureId, AuthPrincipal principal) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .filter(l -> !l.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.LECTURE_NOT_FOUND));
        verifyAccess(lecture.getAcademy().getId(), principal);
        return lecture;
    }

    private void verifyAccess(Long academyId, AuthPrincipal principal) {
        if (principal != null && !principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }
}
