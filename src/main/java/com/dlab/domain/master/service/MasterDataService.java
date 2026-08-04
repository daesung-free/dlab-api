package com.dlab.domain.master.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.master.entity.*;
import com.dlab.domain.master.repository.*;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 기초 마스터 — 학과 · 과정 · 전형 · 커리큘럼 · 계열 · 사물함 · 장학.
 *
 * <p>학과·과정·전형은 지점·연도 단위라 <b>전년도 복사 대상</b>이고, 계열은 전 지점 공통
 * 고정값이라 복사 대상이 아니다(docs/entity-design.md §0-1의 의도된 예외).
 *
 * <p>삭제는 전부 soft delete다 — 과거 데이터가 이 마스터를 참조하고 있어서
 * 물리 삭제하면 작년 기록의 학과명·장학 내역이 사라진다.
 */
@Service
@RequiredArgsConstructor
public class MasterDataService {

    private final DepartmentMasterRepository departmentRepository;
    private final TrackMasterRepository trackRepository;
    private final LockerMasterRepository lockerRepository;
    private final ScholarshipRepository scholarshipRepository;
    private final CourseTypeRepository courseTypeRepository;
    private final AdmissionTypeRepository admissionTypeRepository;
    private final CurriculumRepository curriculumRepository;
    private final com.dlab.domain.user.repository.ClassMasterRepository classMasterRepository;
    private final AcademyRepository academyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    // ── 학과 ──

    @Transactional(readOnly = true)
    public List<DepartmentMaster> departments(SearchScope scope) {
        return departmentRepository.search(scope.academyId(), scope.year());
    }

    @Transactional
    public DepartmentMaster createDepartment(Long academyId, short year, String name,
                                             AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (departmentRepository.existsByAcademyIdAndYearAndNameAndDeletedFalse(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 학과가 있습니다.");
        }
        return departmentRepository.save(new DepartmentMaster(loadAcademy(academyId), year, name));
    }

    @Transactional
    public DepartmentMaster renameDepartment(Long id, String name, AuthPrincipal principal) {
        DepartmentMaster department = departmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(department.getAcademy().getId(), principal);
        department.rename(name);
        return department;
    }

    @Transactional
    public void deleteDepartment(Long id, AuthPrincipal principal) {
        DepartmentMaster department = departmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(department.getAcademy().getId(), principal);
        department.markDeleted();
    }

    // ── 과정 (course_type) ──

    @Transactional(readOnly = true)
    public List<CourseType> courseTypes(Long academyId, short year, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return courseTypeRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(academyId, year);
    }

    @Transactional
    public CourseType createCourseType(Long academyId, short year, String name, short sortOrder,
                                       AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (courseTypeRepository.existsByAcademyIdAndYearAndNameAndDeletedFalse(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 과정이 있습니다.");
        }
        return courseTypeRepository.save(new CourseType(loadAcademy(academyId), year, name, sortOrder));
    }

    @Transactional
    public CourseType renameCourseType(Long id, String name, AuthPrincipal principal) {
        CourseType courseType = courseTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(courseType.getAcademy().getId(), principal);
        courseType.rename(name);
        return courseType;
    }

    @Transactional
    public void deleteCourseType(Long id, AuthPrincipal principal) {
        CourseType courseType = courseTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(courseType.getAcademy().getId(), principal);
        // 물리 삭제하면 이 과정을 참조하던 반의 FK가 깨진다
        courseType.markDeleted();
    }

    // ── 전형 (admission_type) ──

    @Transactional(readOnly = true)
    public List<AdmissionType> admissionTypes(Long academyId, short year, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return admissionTypeRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(academyId, year);
    }

    @Transactional
    public AdmissionType createAdmissionType(Long academyId, short year, String name, short sortOrder,
                                             AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (admissionTypeRepository.existsByAcademyIdAndYearAndNameAndDeletedFalse(
                academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 전형이 있습니다.");
        }
        return admissionTypeRepository.save(
                new AdmissionType(loadAcademy(academyId), year, name, sortOrder));
    }

    @Transactional
    public AdmissionType renameAdmissionType(Long id, String name, AuthPrincipal principal) {
        AdmissionType admissionType = admissionTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(admissionType.getAcademy().getId(), principal);
        admissionType.rename(name);
        return admissionType;
    }

    @Transactional
    public void deleteAdmissionType(Long id, AuthPrincipal principal) {
        AdmissionType admissionType = admissionTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(admissionType.getAcademy().getId(), principal);
        admissionType.markDeleted();
    }

    // ── 커리큘럼 ──

    @Transactional(readOnly = true)
    public List<Curriculum> curriculums(Long academyId, short year, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return curriculumRepository.findAllOfYear(academyId, year);
    }

    /** 반은 선택이다 — 지점 공통 커리큘럼이 있을 수 있다. */
    @Transactional
    public Curriculum createCurriculum(Long academyId, short year, String name, Long classId,
                                       short sortOrder, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (curriculumRepository.existsByAcademyIdAndYearAndNameAndDeletedFalse(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 커리큘럼이 있습니다.");
        }
        ClassMaster classMaster = null;
        if (classId != null) {
            classMaster = classMasterRepository.findDetailById(classId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));
            if (!classMaster.getAcademy().getId().equals(academyId)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "다른 지점의 반은 지정할 수 없습니다.");
            }
        }
        return curriculumRepository.save(
                new Curriculum(loadAcademy(academyId), year, name, classMaster, sortOrder));
    }

    @Transactional
    public Curriculum renameCurriculum(Long id, String name, AuthPrincipal principal) {
        Curriculum curriculum = curriculumRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(curriculum.getAcademy().getId(), principal);
        curriculum.rename(name);
        return curriculum;
    }

    @Transactional
    public void deleteCurriculum(Long id, AuthPrincipal principal) {
        Curriculum curriculum = curriculumRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(curriculum.getAcademy().getId(), principal);
        curriculum.markDeleted();
    }

    // ── 계열 (지점 무관) ──

    @Transactional(readOnly = true)
    public List<TrackMaster> tracks() {
        return trackRepository.findByDeletedFalseOrderByNameAsc();
    }

    /** 전 지점 공통값이라 상위 관리자만 건드릴 수 있어야 한다 — 컨트롤러에서 role로 막는다. */
    @Transactional
    public TrackMaster createTrack(String name) {
        if (trackRepository.existsByNameAndDeletedFalse(name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 있는 계열입니다.");
        }
        return trackRepository.save(new TrackMaster(name));
    }

    // ── 사물함 ──

    @Transactional(readOnly = true)
    public List<LockerMaster> lockers(Long academyId, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return lockerRepository.findByAcademyId(academyId);
    }

    @Transactional
    public LockerMaster createLocker(Long academyId, String lockerNo, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (lockerRepository.existsByAcademyIdAndLockerNoAndDeletedFalse(academyId, lockerNo)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 번호의 사물함이 있습니다.");
        }
        return lockerRepository.save(new LockerMaster(loadAcademy(academyId), lockerNo));
    }

    /**
     * 사물함 배정.
     *
     * <p>학생이 이미 다른 사물함을 쓰고 있으면 그것부터 비운다 — 한 명이 여러 개를 점유하면
     * 실물 열쇠와 어긋난다. 점유된 사물함에 다른 학생을 넣는 건 거부한다.
     */
    @Transactional
    public LockerMaster assignLocker(Long lockerId, Long enrollmentId, AuthPrincipal principal) {
        LockerMaster locker = lockerRepository.findDetailById(lockerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(locker.getAcademy().getId(), principal);

        if (locker.isOccupied()) {
            throw new BusinessException(ErrorCode.LOCKER_ALREADY_OCCUPIED);
        }

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!enrollment.getAcademy().getId().equals(locker.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "다른 지점 사물함에는 배정할 수 없습니다.");
        }

        lockerRepository.findByAssignedEnrollmentIdAndDeletedFalse(enrollmentId)
                .ifPresent(LockerMaster::release);

        locker.assign(enrollment);
        return locker;
    }

    @Transactional
    public void releaseLocker(Long lockerId, AuthPrincipal principal) {
        LockerMaster locker = lockerRepository.findDetailById(lockerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(locker.getAcademy().getId(), principal);
        locker.release();
    }

    // ── 장학 ──

    @Transactional(readOnly = true)
    public List<Scholarship> scholarshipsOf(Long enrollmentId) {
        return scholarshipRepository.findByEnrollmentId(enrollmentId);
    }

    @Transactional
    public Scholarship grantScholarship(Long enrollmentId, String type, BigDecimal discountRate,
                                        AuthPrincipal principal) {
        if (discountRate.signum() < 0 || discountRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "할인율은 0~100 사이여야 합니다.");
        }
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        verifyAccess(enrollment.getAcademy().getId(), principal);

        return scholarshipRepository.save(
                new Scholarship(enrollment.getAcademy(), enrollment, type, discountRate));
    }

    @Transactional
    public void revokeScholarship(Long id, AuthPrincipal principal) {
        Scholarship scholarship = scholarshipRepository.findDetailById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(scholarship.getAcademy().getId(), principal);
        // 물리 삭제하면 과거 청구서의 할인 근거가 사라진다
        scholarship.markDeleted();
    }

    private Academy loadAcademy(Long academyId) {
        return academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    private void verifyAccess(Long academyId, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }
}
