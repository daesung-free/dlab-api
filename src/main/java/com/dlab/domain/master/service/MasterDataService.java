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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 기초 마스터 — 학과 · 과정(전형) · 커리큘럼 · 교습비 · 계열 · 사물함 · 장학.
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

    /** 사물함 블록 일괄 등록 상한. 오타 하나로 수만 건이 들어가는 걸 막는 안전장치다. */
    private static final int MAX_LOCKER_BLOCK = 500;

    private final DepartmentMasterRepository departmentRepository;
    private final TrackMasterRepository trackRepository;
    private final LockerMasterRepository lockerRepository;
    private final RoomMasterRepository roomRepository;
    private final ScholarshipRepository scholarshipRepository;
    private final CourseTypeRepository courseTypeRepository;
    private final CurriculumRepository curriculumRepository;
    private final TuitionRepository tuitionRepository;
    private final com.dlab.domain.user.repository.ClassMasterRepository classMasterRepository;
    private final AcademyRepository academyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ScholarshipMasterService scholarshipMasterService;

    // ── 학과 ──

    /** @param active 상태 필터. {@code null}이면 사용중 + 중지 전부 */
    @Transactional(readOnly = true)
    public List<DepartmentMaster> departments(SearchScope scope, Boolean active) {
        return filterActive(departmentRepository.search(scope.academyId(), scope.year()),
                DepartmentMaster::isActive, active);
    }

    @Transactional
    public DepartmentMaster createDepartment(Long academyId, short year, String name,
                                             String code, String memo, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (departmentRepository.existsByAcademyIdAndYearAndNameAndDeletedFalse(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 학과가 있습니다.");
        }
        requireCodeUnique(departmentRepository.search(academyId, year),
                DepartmentMaster::getCode, DepartmentMaster::getId, code, null);
        DepartmentMaster saved = departmentRepository.save(
                new DepartmentMaster(loadAcademy(academyId), year, name));
        saved.updateAttributes(code, memo);
        return saved;
    }

    @Transactional
    public DepartmentMaster renameDepartment(Long id, String name, String code, String memo,
                                             AuthPrincipal principal) {
        DepartmentMaster department = loadDepartment(id, principal);
        requireCodeUnique(departmentRepository.search(department.getAcademy().getId(),
                        department.getYear()),
                DepartmentMaster::getCode, DepartmentMaster::getId, code, id);
        department.rename(name);
        department.updateAttributes(code, memo);
        return department;
    }

    @Transactional
    public DepartmentMaster changeDepartmentActive(Long id, boolean active, AuthPrincipal principal) {
        DepartmentMaster department = loadDepartment(id, principal);
        department.changeActive(active);
        return department;
    }

    private DepartmentMaster loadDepartment(Long id, AuthPrincipal principal) {
        DepartmentMaster department = departmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(department.getAcademy().getId(), principal);
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
    public List<CourseType> courseTypes(Long academyId, short year, Boolean active,
                                        AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return filterActive(courseTypeRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(academyId, year),
                CourseType::isActive, active);
    }

    @Transactional
    public CourseType createCourseType(Long academyId, short year, String name, String code,
                                       String memo, short sortOrder, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (courseTypeRepository.existsByAcademyIdAndYearAndNameAndDeletedFalse(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 과정이 있습니다.");
        }
        requireCodeUnique(courseTypeRepository
                        .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(academyId, year),
                CourseType::getCode, CourseType::getId, code, null);
        CourseType saved = courseTypeRepository.save(
                new CourseType(loadAcademy(academyId), year, name, sortOrder));
        saved.updateAttributes(code, memo);
        return saved;
    }

    @Transactional
    public CourseType renameCourseType(Long id, String name, String code, String memo,
                                       AuthPrincipal principal) {
        CourseType courseType = loadCourseType(id, principal);
        requireCodeUnique(courseTypeRepository
                        .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(
                                courseType.getAcademy().getId(), courseType.getYear()),
                CourseType::getCode, CourseType::getId, code, id);
        courseType.rename(name);
        courseType.updateAttributes(code, memo);
        return courseType;
    }

    @Transactional
    public CourseType changeCourseTypeActive(Long id, boolean active, AuthPrincipal principal) {
        CourseType courseType = loadCourseType(id, principal);
        courseType.changeActive(active);
        return courseType;
    }

    private CourseType loadCourseType(Long id, AuthPrincipal principal) {
        CourseType courseType = courseTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(courseType.getAcademy().getId(), principal);
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


    // ── 교습비 ──

    @Transactional(readOnly = true)
    public List<Tuition> tuitions(Long academyId, short year, Boolean active,
                                  AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return filterActive(tuitionRepository.findAllOfYear(academyId, year),
                Tuition::isActive, active);
    }

    @Transactional
    public Tuition createTuition(Long academyId, short year, String name, int amount,
                                 String code, String memo, short sortOrder,
                                 AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (amount < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "금액은 0 이상이어야 합니다.");
        }
        if (tuitionRepository.existsByAcademyIdAndYearAndNameAndDeletedFalse(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 교습비가 있습니다.");
        }
        requireCodeUnique(tuitionRepository.findAllOfYear(academyId, year),
                Tuition::getCode, Tuition::getId, code, null);
        Tuition saved = tuitionRepository.save(
                new Tuition(loadAcademy(academyId), year, name, amount, sortOrder));
        saved.updateAttributes(code, memo);
        return saved;
    }

    /**
     * 교습비 변경.
     *
     * <p><b>과거 청구에 소급되면 안 된다.</b> 청구 도메인이 생기면 청구 시점 금액을
     * 청구 행에 복사해 남겨야 한다 — 상벌점이 부여 시점 점수를 복사하는 것과 같은 이유다.
     */
    @Transactional
    public Tuition updateTuition(Long id, String name, Integer amount, String code, String memo,
                                 AuthPrincipal principal) {
        Tuition tuition = loadTuition(id, principal);
        requireCodeUnique(tuitionRepository.findAllOfYear(tuition.getAcademy().getId(),
                        tuition.getYear()),
                Tuition::getCode, Tuition::getId, code, id);
        tuition.update(name, amount);
        tuition.updateAttributes(code, memo);
        return tuition;
    }

    @Transactional
    public Tuition changeTuitionActive(Long id, boolean active, AuthPrincipal principal) {
        Tuition tuition = loadTuition(id, principal);
        tuition.changeActive(active);
        return tuition;
    }

    private Tuition loadTuition(Long id, AuthPrincipal principal) {
        Tuition tuition = tuitionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(tuition.getAcademy().getId(), principal);
        return tuition;
    }

    @Transactional
    public void deleteTuition(Long id, AuthPrincipal principal) {
        Tuition tuition = tuitionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(tuition.getAcademy().getId(), principal);
        tuition.markDeleted();
    }

    // ── 커리큘럼 ──

    @Transactional(readOnly = true)
    public List<Curriculum> curriculums(Long academyId, short year, Boolean active,
                                        AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return filterActive(curriculumRepository.findAllOfYear(academyId, year),
                Curriculum::isActive, active);
    }

    /** 반은 선택이다 — 지점 공통 커리큘럼이 있을 수 있다. */
    @Transactional
    public Curriculum createCurriculum(Long academyId, short year, String name, Long classId,
                                       String code, String memo, short sortOrder,
                                       AuthPrincipal principal) {
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
        requireCodeUnique(curriculumRepository.findAllOfYear(academyId, year),
                Curriculum::getCode, Curriculum::getId, code, null);
        Curriculum saved = curriculumRepository.save(
                new Curriculum(loadAcademy(academyId), year, name, classMaster, sortOrder));
        saved.updateAttributes(code, memo);
        return saved;
    }

    @Transactional
    public Curriculum renameCurriculum(Long id, String name, String code, String memo,
                                       AuthPrincipal principal) {
        Curriculum curriculum = loadCurriculum(id, principal);
        requireCodeUnique(curriculumRepository.findAllOfYear(curriculum.getAcademy().getId(),
                        curriculum.getYear()),
                Curriculum::getCode, Curriculum::getId, code, id);
        curriculum.rename(name);
        curriculum.updateAttributes(code, memo);
        return curriculum;
    }

    @Transactional
    public Curriculum changeCurriculumActive(Long id, boolean active, AuthPrincipal principal) {
        Curriculum curriculum = loadCurriculum(id, principal);
        curriculum.changeActive(active);
        return curriculum;
    }

    private Curriculum loadCurriculum(Long id, AuthPrincipal principal) {
        Curriculum curriculum = curriculumRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(curriculum.getAcademy().getId(), principal);
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

    /**
     * 계열 목록.
     *
     * <p>코드 유니크 검사에 지점·연도 축이 없다 — 계열은 전 지점 공통이라 <b>전체에서
     * 유일</b>해야 한다. 그래서 다른 마스터와 달리 목록 전체를 대조 대상으로 넘긴다.
     *
     * @param active 상태 필터. {@code null}이면 사용중 + 중지 전부
     */
    @Transactional(readOnly = true)
    public List<TrackMaster> tracks(Boolean active) {
        return filterActive(trackRepository.findByDeletedFalseOrderByNameAsc(),
                TrackMaster::isActive, active);
    }

    /** 전 지점 공통값이라 상위 관리자만 건드릴 수 있어야 한다 — 컨트롤러에서 role로 막는다. */
    @Transactional
    public TrackMaster createTrack(String name, String code, String memo) {
        if (trackRepository.existsByNameAndDeletedFalse(name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 있는 계열입니다.");
        }
        requireCodeUnique(trackRepository.findByDeletedFalseOrderByNameAsc(),
                TrackMaster::getCode, TrackMaster::getId, code, null);
        return trackRepository.save(new TrackMaster(name, code, memo));
    }

    @Transactional
    public TrackMaster renameTrack(Long id, String name, String code, String memo) {
        TrackMaster track = loadTrack(id);
        // 이름도 전 지점 공통이라 자기 자신을 뺀 전체와 대조한다
        boolean nameTaken = trackRepository.findByDeletedFalseOrderByNameAsc().stream()
                .filter(row -> !row.getId().equals(id))
                .anyMatch(row -> row.getName().equals(name));
        if (nameTaken) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 있는 계열입니다.");
        }
        requireCodeUnique(trackRepository.findByDeletedFalseOrderByNameAsc(),
                TrackMaster::getCode, TrackMaster::getId, code, id);
        track.rename(name);
        track.updateAttributes(code, memo);
        return track;
    }

    /** 중지는 삭제와 다르다 — "새로 고를 수 없다"이고 그 계열이었던 데이터는 남는다. */
    @Transactional
    public TrackMaster changeTrackActive(Long id, boolean active) {
        TrackMaster track = loadTrack(id);
        track.changeActive(active);
        return track;
    }

    /**
     * 삭제(soft).
     *
     * <p>물리 삭제하지 않는다. 대부분의 경우 삭제가 아니라 {@link #changeTrackActive}
     * 중지가 맞다 — 오타로 만든 행을 치우는 용도다.
     */
    @Transactional
    public void deleteTrack(Long id) {
        loadTrack(id).markDeleted();
    }

    private TrackMaster loadTrack(Long id) {
        return trackRepository.findById(id)
                .filter(track -> !track.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
    }

    // ── 강의실 ──

    /**
     * 강의실 목록.
     *
     * <p><b>자습 구역({@code study_area})과 다르다</b> — 자습 구역은 좌석이 속하는 단위이고
     * 키오스크 계약에 걸려 있다. 여기는 수업 공간이라 키오스크와 무관하다.
     *
     * @param active 상태 필터. {@code null}이면 사용중 + 중지 전부
     */
    @Transactional(readOnly = true)
    public List<RoomMaster> rooms(Long academyId, Boolean active, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return filterActive(roomRepository.findByAcademyId(academyId), RoomMaster::isActive, active);
    }

    @Transactional
    public RoomMaster createRoom(Long academyId, String roomNo, String name, Short capacity,
                                 String memo, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (roomRepository.existsByAcademyIdAndRoomNoAndDeletedFalse(academyId, roomNo)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 번호의 강의실이 있습니다.");
        }
        return roomRepository.save(
                new RoomMaster(loadAcademy(academyId), roomNo, name, capacity, memo));
    }

    /** 방 번호도 바꿀 수 있다 — 이 번호를 참조하는 다른 표가 없어 이력이 흔들리지 않는다. */
    @Transactional
    public RoomMaster updateRoom(Long id, String roomNo, String name, Short capacity, String memo,
                                 AuthPrincipal principal) {
        RoomMaster room = loadRoom(id, principal);
        if (!room.getRoomNo().equals(roomNo)
                && roomRepository.existsByAcademyIdAndRoomNoAndDeletedFalse(
                        room.getAcademy().getId(), roomNo)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 번호의 강의실이 있습니다.");
        }
        room.update(roomNo, name, capacity, memo);
        return room;
    }

    /**
     * 강의실 부분 수정 — <b>안 보낸 값은 그대로 둔다.</b>
     *
     * <p>{@link #updateRoom}은 전체 교체라 이름 하나 바꾸려 해도 번호·수용인원·비고를
     * 다 실어 보내야 하고, <b>그 사이 다른 사람이 바꾼 값을 덮어쓴다</b>.
     *
     * <p>⚠️ 부분 수정이 동시 수정을 완전히 막아주지는 않는다 — 두 사람이 <b>같은 필드</b>를
     * 고치면 여전히 나중 게 이긴다. 그건 버전(낙관적 락)이 있어야 잡히고 여기 범위가 아니다.
     * 여기서 지켜지는 것은 "안 건드린 필드"다.
     *
     * @param clearCapacity 수용인원을 <b>비우려는</b> 경우에만 {@code true}.
     *                      {@code capacity = null}은 "안 바꿈"이라 이 플래그가 없으면
     *                      한번 넣은 인원수를 되돌릴 방법이 없다
     */
    @Transactional
    public RoomMaster patchRoom(Long id, String roomNo, String name, Short capacity,
                                boolean clearCapacity, String memo, AuthPrincipal principal) {
        RoomMaster room = loadRoom(id, principal);
        String nextRoomNo = roomNo == null ? room.getRoomNo() : roomNo;
        if (!room.getRoomNo().equals(nextRoomNo)
                && roomRepository.existsByAcademyIdAndRoomNoAndDeletedFalse(
                        room.getAcademy().getId(), nextRoomNo)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 번호의 강의실이 있습니다.");
        }
        room.update(nextRoomNo,
                name == null ? room.getName() : name,
                clearCapacity ? null : (capacity == null ? room.getCapacity() : capacity),
                memo == null ? room.getMemo() : memo);
        return room;
    }

    /** 사용/중지. 공사 중인 방을 지우면 그 방에서 진행됐던 기록의 근거가 끊긴다. */
    @Transactional
    public RoomMaster changeRoomActive(Long id, boolean active, AuthPrincipal principal) {
        RoomMaster room = loadRoom(id, principal);
        room.changeActive(active);
        return room;
    }

    @Transactional
    public void deleteRoom(Long id, AuthPrincipal principal) {
        loadRoom(id, principal).markDeleted();
    }

    private RoomMaster loadRoom(Long id, AuthPrincipal principal) {
        RoomMaster room = roomRepository.findDetailById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(room.getAcademy().getId(), principal);
        return room;
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
            throw new BusinessException(ErrorCode.LOCKER_NO_DUPLICATED);
        }
        return lockerRepository.save(new LockerMaster(loadAcademy(academyId), lockerNo));
    }

    /**
     * 블록 일괄 등록 (예: {@code L-} + 1~120 → {@code L-001 … L-120}).
     *
     * <p>사물함은 블록 단위로 들어온다 — A블록 120칸을 <b>한 칸씩 120번 등록</b>하게
     * 두면 아무도 안 쓴다.
     *
     * <p><b>이미 있는 번호는 건너뛰고 계속한다.</b> 중간에 하나 겹쳤다고 전부 되돌리면
     * 운영자는 어디까지 됐는지 모른 채 처음부터 다시 해야 한다. 대신 건너뛴 번호를
     * 그대로 돌려줘 화면이 알린다.
     *
     * @param digits 0으로 채울 자릿수. {@code 3}이면 {@code L-007}
     */
    @Transactional
    public LockerBlockOutcome createLockerBlock(Long academyId, String prefix, int startNo,
                                                int endNo, int digits, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (startNo > endNo) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "시작 번호가 끝 번호보다 큽니다.");
        }
        // 오타 한 번으로 수만 건이 들어가는 걸 막는다. 블록 하나가 이보다 큰 경우는 없다
        if (endNo - startNo + 1 > MAX_LOCKER_BLOCK) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 %d칸까지 만들 수 있습니다.".formatted(MAX_LOCKER_BLOCK));
        }

        Academy academy = loadAcademy(academyId);
        // 번호마다 조회하면 블록 크기만큼 쿼리가 나간다 — 한 번 읽어 Set으로 대조한다
        Set<String> existing = lockerRepository.findByAcademyId(academyId).stream()
                .map(LockerMaster::getLockerNo).collect(java.util.stream.Collectors.toSet());

        List<LockerMaster> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int no = startNo; no <= endNo; no++) {
            String lockerNo = prefix + String.format("%0" + digits + "d", no);
            if (existing.contains(lockerNo)) {
                skipped.add(lockerNo);
                continue;
            }
            created.add(lockerRepository.save(new LockerMaster(academy, lockerNo)));
        }
        return new LockerBlockOutcome(created, skipped);
    }

    /** @param skipped 이미 있어서 건너뛴 번호. 화면이 "몇 개는 이미 있었다"를 알려야 한다 */
    public record LockerBlockOutcome(List<LockerMaster> created, List<String> skipped) {
    }

    /** 사물함 번호 변경. 배정은 그대로 유지된다 — 칸 이름표만 바꾸는 것이다. */
    @Transactional
    public LockerMaster renameLocker(Long lockerId, String lockerNo, AuthPrincipal principal) {
        LockerMaster locker = lockerRepository.findDetailById(lockerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(locker.getAcademy().getId(), principal);
        if (!locker.getLockerNo().equals(lockerNo)
                && lockerRepository.existsByAcademyIdAndLockerNoAndDeletedFalse(
                        locker.getAcademy().getId(), lockerNo)) {
            throw new BusinessException(ErrorCode.LOCKER_NO_DUPLICATED);
        }
        locker.rename(lockerNo);
        return locker;
    }

    /**
     * 사물함 삭제(soft).
     *
     * <p><b>배정된 사물함은 거부한다</b> — 지우면 그 학생 배정이 붕 뜬다. 해제가 먼저다.
     * (실물 칸이 없어진 게 아니라 잘못 만든 행을 치우는 용도다.)
     */
    @Transactional
    public void deleteLocker(Long lockerId, AuthPrincipal principal) {
        LockerMaster locker = lockerRepository.findDetailById(lockerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(locker.getAcademy().getId(), principal);
        if (locker.isOccupied()) {
            throw new BusinessException(ErrorCode.LOCKER_IN_USE);
        }
        locker.markDeleted();
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

    /**
     * 장학 부여.
     *
     * <h2>★ 장학 종류는 마스터에 있어야 한다</h2>
     * 취소 판정이 {@code scholarship_cancel_rule.scholarship_type}과 <b>문자열 일치</b>로
     * 걸리는데 여기가 자유 입력이면 {@code KICE-50} 하나로 <b>그 학생만 판정에서 조용히
     * 빠진다</b>. 오류도 안 나고 검토 목록에 안 뜰 뿐이라 아무도 알아채지 못한다.
     *
     * <h2>★ 할인율은 마스터에서 복사한다</h2>
     * 같은 장학인데 학생마다 다른 값이 들어가면 퇴원 소급 재결제(0820 규정)가
     * "이 학생은 왜 40%였나"에 답할 수 없다.
     *
     * @param discountRate 화면이 보내온 값. <b>저장에 쓰지 않고 대조만 한다</b> —
     *                     마스터와 다르면 막는다. 조용히 바꿔치우면 데스크는 화면에
     *                     입력한 값이 아닌 것이 저장된 줄 모른다
     */
    @Transactional
    public Scholarship grantScholarship(Long enrollmentId, String type, BigDecimal discountRate,
                                        AuthPrincipal principal) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        verifyAccess(enrollment.getAcademy().getId(), principal);

        ScholarshipMaster master = scholarshipMasterService.requireApplicable(
                enrollment.getYear(), type, enrollment.getAcademy().getId());

        // compareTo 로 비교한다 — equals 는 30.0 과 30.00 을 다르다고 본다
        if (discountRate != null && discountRate.compareTo(master.getDiscountRate()) != 0) {
            throw new BusinessException(ErrorCode.SCHOLARSHIP_RATE_MISMATCH,
                    "'%s' 장학의 할인율은 %s%% 입니다."
                            .formatted(master.getCode(), master.getDiscountRate().stripTrailingZeros()
                                    .toPlainString()));
        }

        return scholarshipRepository.save(new Scholarship(enrollment.getAcademy(), enrollment,
                master.getCode(), master.getDiscountRate()));
    }

    @Transactional
    public void revokeScholarship(Long id, AuthPrincipal principal) {
        Scholarship scholarship = scholarshipRepository.findDetailById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(scholarship.getAcademy().getId(), principal);
        // 물리 삭제하면 과거 청구서의 할인 근거가 사라진다
        scholarship.markDeleted();
    }

    /**
     * 코드 중복 검사.
     *
     * <p>DB에도 부분 유니크 인덱스가 있지만 그것만 믿으면 <b>제약 위반이 500으로 나가</b>
     * 화면이 무엇이 잘못됐는지 못 알려준다. 여기서 먼저 걸러 409로 돌려준다.
     *
     * @param currentId 수정 중인 행. 자기 자신은 중복이 아니다
     */
    private <T> void requireCodeUnique(List<T> rows, java.util.function.Function<T, String> codeOf,
                                       java.util.function.Function<T, Long> idOf,
                                       String code, Long currentId) {
        String normalized = MasterAttributes.normalize(code);
        if (normalized == null) {
            return;
        }
        boolean taken = rows.stream()
                .filter(row -> !idOf.apply(row).equals(currentId))
                .anyMatch(row -> normalized.equals(codeOf.apply(row)));
        if (taken) {
            throw new BusinessException(ErrorCode.MASTER_CODE_DUPLICATED,
                    "'%s' 코드가 이미 사용 중입니다.".formatted(normalized));
        }
    }

    /**
     * 상태 필터.
     *
     * <p><b>{@code null}이면 전부 내린다</b> — 관리 화면은 중지된 것도 봐야 한다.
     * 리포지토리 질의를 넷 다 고치는 대신 여기서 거른다: 기초 마스터는 지점·연도당
     * 수십 건 규모라 걸러서 얻을 것이 없고, 질의를 넷으로 늘리면 한 곳만 고쳐진다.
     */
    private <T> List<T> filterActive(List<T> rows, java.util.function.Predicate<T> isActive,
                                     Boolean active) {
        if (active == null) {
            return rows;
        }
        return rows.stream().filter(row -> isActive.test(row) == active).toList();
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
