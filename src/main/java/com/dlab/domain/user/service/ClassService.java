package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 반 관리 · 학생 반 배정.
 *
 * <p><b>이 도메인이 승인 워크플로우의 입구다.</b> 반에 담임을 지정하고 학생을 그 반에 배정하면
 * 방화벽·사유신청의 <b>에스컬레이션 대상이 자동으로 결정된다</b>(CLAUDE.md §3).
 * 그래서 "학생별로 승인자를 따로 지정하는 화면"은 만들지 않는다.
 *
 * <p>담임은 {@link Teacher}만 될 수 있다 — 행정({@link Employee})은 애초에 다른 테이블이라
 * FK가 자격을 보장한다.
 */
@Service
@RequiredArgsConstructor
public class ClassService {

    private final ClassMasterRepository classMasterRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AcademyRepository academyRepository;
    private final TeacherRepository teacherRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;

    @Transactional(readOnly = true)
    public List<ClassMaster> search(SearchScope scope) {
        return classMasterRepository.search(scope.academyId(), scope.year());
    }

    /**
     * 반 학생 명단 (F-4.1-4 반 배정 · F-4.10-3 배정 관리).
     *
     * <p><b>좌석은 행마다 조회하지 않는다.</b> 반 하나에 수십 명이라 학생 수만큼 쿼리가 나간다 —
     * 명단을 먼저 읽고 등록 건 id를 모아 <b>좌석을 한 번에 조회해 Map으로 붙인다</b>
     * (키오스크 {@code getStdInfoList}와 같은 방식). 쿼리는 학생 수와 무관하게 항상 3회다
     * (반 · 명단 · 좌석).
     *
     * <p>지점명은 반에서 가져온다 — 다른 지점 반에는 배정 자체가 막혀 있어
     * 명단 전원이 반과 같은 지점이다. 등록 건마다 지점을 다시 읽을 이유가 없다.
     */
    @Transactional(readOnly = true)
    public List<ClassMemberView> studentsOf(Long classId, AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        List<ClassAssignment> assignments =
                classAssignmentRepository.findActiveByClassId(classMaster.getId());
        if (assignments.isEmpty()) {
            return List.of();
        }

        Map<Long, String> seatByEnrollment = seatCodes(
                assignments.stream().map(a -> a.getEnrollment().getId()).toList());
        String academyName = classMaster.getAcademy().getAcadNm();

        return assignments.stream()
                .map(a -> new ClassMemberView(
                        a, seatByEnrollment.get(a.getEnrollment().getId()), academyName))
                .toList();
    }

    /** 등록 건 → 현재 좌석코드. 좌석 미배정 학생은 키 자체가 없다(= null). */
    private Map<Long, String> seatCodes(List<Long> enrollmentIds) {
        return seatAssignmentRepository.findActiveByEnrollmentIds(enrollmentIds).stream()
                .collect(Collectors.toMap(
                        sa -> sa.getEnrollment().getId(),
                        sa -> sa.getSeat().getSeatCd(),
                        // 같은 학생에 활성 배정이 둘일 수는 없지만, 데이터가 어긋나도
                        // 명단 조회가 예외로 죽지 않게 먼저 것을 쓴다
                        (first, second) -> first));
    }

    /**
     * 명단 한 줄 — 배정 + 화면에 필요한 파생값.
     *
     * <p>좌석·지점명은 {@link ClassAssignment}에서 바로 나오지 않는다.
     * 응답 조립부에서 다시 조회하면 N+1이 되므로 여기서 붙여 내보낸다.
     */
    public record ClassMemberView(ClassAssignment assignment, String seatCd, String academyName) {
    }

    @Transactional
    public ClassMaster create(Long academyId, short year, String name,
                              ClassType classType, Long homeroomTeacherId,
                              AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (classMasterRepository.existsByAcademyIdAndYearAndName(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 반이 있습니다.");
        }

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        return classMasterRepository.save(
                new ClassMaster(academy, year, name, classType, resolveTeacher(homeroomTeacherId, academyId)));
    }

    /**
     * 담임 지정·변경.
     *
     * <p>이미 처리된 승인 건은 영향받지 않는다 — 신청 시점의 담당선생님을 스냅샷으로 박아두기
     * 때문이다. 담임을 바꿔도 진행 중인 건의 승인자가 바뀌지 않는다.
     */
    @Transactional
    public ClassMaster assignHomeroom(Long classId, Long teacherId, AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        classMaster.assignHomeroom(resolveTeacher(teacherId, classMaster.getAcademy().getId()));
        return classMaster;
    }

    /**
     * 학생 반 배정.
     *
     * <p>같은 유형의 기존 배정이 있으면 <b>이전 것을 비활성으로 내리고 새 행을 넣는다</b> —
     * 매년 전체 재세팅되는 구조라 이력이 남아야 한다. 덮어쓰면 "작년에 어느 반이었나"를 잃는다.
     */
    @Transactional
    public ClassMemberView assignStudent(Long classId, Long enrollmentId, AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        if (!enrollment.getAcademy().getId().equals(classMaster.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "다른 지점의 반에는 배정할 수 없습니다.");
        }
        if (enrollment.getYear() != classMaster.getYear()) {
            // 연도가 어긋나면 "올해 학생이 작년 반에" 같은 상태가 만들어진다
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "학생의 등록 연도와 반의 연도가 다릅니다.");
        }

        classAssignmentRepository
                .findByEnrollmentIdAndClassTypeAndActiveTrue(enrollmentId, classMaster.getClassType())
                .ifPresent(previous -> {
                    previous.deactivate();
                    // ★ 반드시 여기서 flush 한다. Hibernate는 기본적으로 INSERT를 UPDATE보다
                    //   먼저 내보내는데, 그러면 이전 배정이 아직 활성인 상태로 새 행이 들어가
                    //   uq_class_assignment_active(부분 유니크)에 걸린다.
                    classAssignmentRepository.flush();
                });

        ClassAssignment saved = classAssignmentRepository.save(new ClassAssignment(
                classMaster.getAcademy(), enrollment, classMaster, classMaster.getClassType()));

        // 명단과 같은 모양으로 돌려준다 — 배정 직후 화면이 그 줄을 그대로 쓴다
        return new ClassMemberView(saved,
                seatCodes(List.of(enrollmentId)).get(enrollmentId),
                classMaster.getAcademy().getAcadNm());
    }

    private ClassMaster loadAccessible(Long classId, AuthPrincipal principal) {
        ClassMaster classMaster = classMasterRepository.findDetailById(classId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));
        if (!principal.canAccessAcademy(classMaster.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return classMaster;
    }

    /** 담임은 같은 지점 소속이어야 한다. 겸직이 없으므로 지점이 하나로 정해진다. */
    private Teacher resolveTeacher(Long teacherId, Long academyId) {
        if (teacherId == null) {
            return null;
        }
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "선생님을 찾을 수 없습니다."));
        if (!teacher.getAcademy().getId().equals(academyId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "다른 지점 선생님은 담임으로 지정할 수 없습니다.");
        }
        return teacher;
    }
}
