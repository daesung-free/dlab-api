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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class ClassService {

    private final com.dlab.domain.master.repository.RoomMasterRepository roomMasterRepository;
    private final ClassMasterRepository classMasterRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final com.dlab.domain.grade.service.ExamNumberService examNumberService;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AcademyRepository academyRepository;
    private final TeacherRepository teacherRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;

    @Transactional(readOnly = true)
    public List<ClassMaster> search(SearchScope scope) {
        return classMasterRepository.search(scope.academyId(), scope.year());
    }

    /**
     * 반 목록 + 현재 인원수 (F-4.1-5 "고정반목록(… 정원/원생수)").
     *
     * <p><b>인원수를 반마다 세지 않는다.</b> 목록이 한 번에 오는데 반마다 명단을 부르면
     * 쿼리가 반 개수만큼 나간다 — 지점 반이 20개면 21쿼리다. 반 ID를 모아
     * <b>GROUP BY 집계 한 번</b>으로 끝내므로 반이 몇 개든 쿼리는 항상 2회다
     * ({@code StudentListEnricher}·키오스크 학생 목록과 같은 방식).
     *
     * <p>배정이 없는 반은 집계에 행이 없다 — 여기서 0으로 채운다. 안 채우면
     * 화면에 인원수 칸이 비어 "집계가 안 됐다"로 읽힌다.
     */
    @Transactional(readOnly = true)
    public List<ClassSummaryView> searchWithMemberCount(SearchScope scope) {
        List<ClassMaster> classes = classMasterRepository.search(scope.academyId(), scope.year());
        if (classes.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> counts = memberCounts(classes.stream().map(ClassMaster::getId).toList());
        return classes.stream()
                .map(c -> new ClassSummaryView(c, counts.getOrDefault(c.getId(), 0)))
                .toList();
    }

    /** 반 ID → 현재 인원. 배정이 없는 반은 키 자체가 없다(= 0). */
    private Map<Long, Integer> memberCounts(List<Long> classIds) {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : classAssignmentRepository.countActiveByClassIds(classIds)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /** 목록 한 줄 — 반 + 현재 인원. 인원은 반 엔티티에서 나오지 않아 여기서 붙여 내보낸다. */
    public record ClassSummaryView(ClassMaster classMaster, int memberCount) {
    }

    /**
     * 반 기본정보 수정 (이름·정원).
     *
     * <p>담임은 여기서 바꾸지 않는다 — {@code PUT /homeroom}이 이미 있고, 담임 변경은
     * 승인 에스컬레이션 대상이 바뀌는 별개의 사건이라 축을 섞지 않는다.
     */
    @Transactional
    public ClassMaster update(Long classId, String name, Short capacity, boolean clearCapacity,
                              AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        if (name != null && !name.equals(classMaster.getName())
                && classMasterRepository.existsByAcademyIdAndYearAndName(
                        classMaster.getAcademy().getId(), classMaster.getYear(), name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 반이 있습니다.");
        }
        classMaster.updateDetails(name, capacity, clearCapacity);
        return classMaster;
    }

    /**
     * 반 삭제(soft).
     *
     * <p><b>배정된 학생이 있으면 거부한다.</b> 그냥 지우면 그 학생들의 배정 행이
     * 없는 반을 가리킨 채 남아 <b>"반이 있는데 목록에 안 보이는" 학생</b>이 된다.
     * 화면에서 해제가 먼저다.
     *
     * <p>물리 삭제하지 않는 이유는 다른 마스터와 같다 — 과거 학생이 어느 반이었는지가
     * 이력으로 남아야 한다. 지난 기수 반은 대부분 삭제가 아니라 그대로 두는 게 맞다.
     */
    @Transactional
    public void delete(Long classId, AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        int assigned = classAssignmentRepository.findActiveByClassId(classId).size();
        if (assigned > 0) {
            throw new BusinessException(ErrorCode.CLASS_HAS_MEMBERS,
                    "배정된 학생 %d명을 먼저 해제해 주세요.".formatted(assigned));
        }
        classMaster.markDeleted();
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
                              ClassType classType, Long homeroomTeacherId, Short capacity,
                              AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (classMasterRepository.existsByAcademyIdAndYearAndName(academyId, year, name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도에 같은 이름의 반이 있습니다.");
        }

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        ClassMaster classMaster =
                new ClassMaster(academy, year, name, classType, resolveTeacher(homeroomTeacherId, academyId));
        classMaster.changeCapacity(capacity);
        return classMasterRepository.save(classMaster);
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
     * 강의실 지정·해제. {@code roomId}가 {@code null}이면 해제다.
     *
     * <p><b>같은 지점 강의실만</b> 붙는다 — 다른 지점 강의실 id 를 넣어도 통하면 반 목록에
     * 남의 지점 강의실 이름이 뜬다.
     */
    @Transactional
    public ClassMaster assignRoom(Long classId, Long roomId, AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        if (roomId == null) {
            classMaster.assignRoom(null);
            return classMaster;
        }
        var room = roomMasterRepository.findById(roomId)
                .filter(r -> !r.isDeleted())
                .filter(r -> r.getAcademy().getId().equals(classMaster.getAcademy().getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "이 지점의 강의실이 아닙니다."));
        classMaster.assignRoom(room);
        return classMaster;
    }

    /**
     * 모의고사 반 번호 지정.
     *
     * <p>★ <b>반 이름에서 뽑지 않는다.</b> "고3 1반" 과 "N수 1반" 이 둘 다 1반이 되어
     * 수험번호가 겹친다 — 실제 자료는 지점 안에서 반 번호가 유일하다.
     *
     * <p><b>이미 채번된 학생의 번호는 바뀌지 않는다.</b> 여기서 바꾼 값은 <b>다음에 배정되는
     * 학생부터</b> 적용된다 — 연구소가 최초 부여 번호는 변경 불가라고 명시했다.
     */
    @Transactional
    public ClassMaster changeExamClassNo(Long classId, Short examClassNo, AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        if (examClassNo != null && (examClassNo < 1 || examClassNo > 99)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "모의고사 반 번호는 1~99 입니다.");
        }
        classMaster.changeExamClassNo(examClassNo);
        return classMaster;
    }

    /**
     * 학생 반 배정.
     *
     * <p>같은 유형의 기존 배정이 있으면 <b>이전 것을 비활성으로 내리고 새 행을 넣는다</b> —
     * 매년 전체 재세팅되는 구조라 이력이 남아야 한다. 덮어쓰면 "작년에 어느 반이었나"를 잃는다.
     *
     * <p>★ 여기서 <b>모의고사 수험번호가 채번된다</b>(최초 배정 시 한 번).
     */
    @Transactional
    public ClassMemberView assignStudent(Long classId, Long enrollmentId, AuthPrincipal principal) {
        return assignInto(loadAccessible(classId, principal), enrollmentId);
    }

    /**
     * 배정 본체. 단건·일괄이 같은 규칙을 타야 해서 분리했다 —
     * 일괄에만 다른 검증이 들어가면 두 경로로 만든 데이터가 서로 다른 상태가 된다.
     */
    private ClassMemberView assignInto(ClassMaster classMaster, Long enrollmentId) {
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
                    // ★ 고정반이 바뀌면 담임 예외 지정을 푼다. 반을 옮기는 순간 예외의 전제
                    //   ("반은 그대로인데 담임만 다르게")가 사라지는데, 남겨두면 옛 담임이 조용히
                    //   따라다니며 승인·상담을 받는다
                    if (classMaster.getClassType() == com.dlab.domain.user.entity.ClassType.FIXED
                            && !previous.getClassMaster().getId().equals(classMaster.getId())
                            && enrollment.getHomeroomOverride() != null) {
                        log.info("반 이동으로 담임 예외 지정 해제: enrollmentId={}, 해제된 담임 teacherId={}, 사유였던 것={}",
                                enrollmentId, enrollment.getHomeroomOverride().getId(),
                                enrollment.getHomeroomOverrideReason());
                        enrollment.clearHomeroomOverride();
                    }
                    previous.deactivate();
                    // ★ 반드시 여기서 flush 한다. Hibernate는 기본적으로 INSERT를 UPDATE보다
                    //   먼저 내보내는데, 그러면 이전 배정이 아직 활성인 상태로 새 행이 들어가
                    //   uq_class_assignment_active(부분 유니크)에 걸린다.
                    classAssignmentRepository.flush();
                });

        ClassAssignment saved = classAssignmentRepository.save(new ClassAssignment(
                classMaster.getAcademy(), enrollment, classMaster, classMaster.getClassType()));

        // ★ 모의고사 수험번호는 반 최초 배정에서 정해진다. 이미 있으면 덮어쓰지 않는다 —
        //   반을 옮길 때마다 번호가 바뀌면 지난 회차 성적과 연결이 끊긴다
        examNumberService.assignOnClassAssigned(enrollment, classMaster);

        // 명단과 같은 모양으로 돌려준다 — 배정 직후 화면이 그 줄을 그대로 쓴다
        return new ClassMemberView(saved,
                seatCodes(List.of(enrollmentId)).get(enrollmentId),
                classMaster.getAcademy().getAcadNm());
    }

    /**
     * 반 배정 해제 (좌석·사물함 해제와 같은 결).
     *
     * <p><b>행을 지우지 않고 비활성으로 내린다</b> — 배정은 이력이라
     * 지우면 "언제까지 어느 반이었나"를 잃는다. 재배정이 이전 행을 내리는 것과 같은 처리다.
     *
     * <p>반을 경로에 함께 받는다. 학생만으로 찾으면 고정반·이동수업반 중 <b>어느 것을 뗄지가
     * 정해지지 않는다</b>(좌석은 학생당 하나라 학생 ID만으로 충분했다).
     */
    @Transactional
    public void releaseStudent(Long classId, Long enrollmentId, AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        ClassAssignment assignment = classAssignmentRepository
                .findByClassMasterIdAndEnrollmentIdAndActiveTrue(classMaster.getId(), enrollmentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CLASS_NOT_ASSIGNED));
        assignment.deactivate();
    }

    /**
     * 학생 일괄 배정 (F-4.1-4 "선택 N명 일괄 배정").
     *
     * <p><b>★ 전부-아니면-전무가 아니라 건별 결과를 돌려준다.</b> 화면이 목록에서 여러 명을
     * 골라 보내는데, 한 명이 다른 지점·다른 연도라는 이유로 전부 되돌리면 <b>운영자는 누가
     * 문제였는지 모른 채 처음부터 다시</b> 골라야 한다. 키오스크 {@code seat-leaves}가
     * 같은 이유로 건별이다.
     *
     * <p><b>부분 성공이 위험하지 않은 이유</b> — 실패 사유가 전부 <b>그 학생 한 명에 대한
     * 검증</b>(존재·지점·연도)이라 다른 학생의 배정 결과를 바꾸지 않는다. 정원 초과도
     * 막지 않으므로(아래) "N명을 넣으면 넘친다" 같은 <b>집합 단위 판정이 없다</b> —
     * 집합 판정이 생기면 그때는 전부-아니면-전무로 바꿔야 한다.
     *
     * <p><b>정원은 넘겨도 막지 않는다.</b> 스키마 주석이 명시하듯 정원을 넘겨야 하는 예외가
     * 실제로 있다. 대신 결과에 초과 여부를 실어 화면이 경고를 띄울 수 있게 한다 —
     * 조용히 넘기면 아무도 모른다.
     *
     * <p>같은 배치에 같은 학생이 두 번 들어오면 뒤엣것은 건너뛴다. 두 번 배정하면
     * 방금 넣은 행을 스스로 비활성으로 내려 <b>이력에 의미 없는 줄이 남는다.</b>
     */
    @Transactional
    public BulkAssignOutcome assignStudents(Long classId, List<Long> enrollmentIds,
                                            AuthPrincipal principal) {
        ClassMaster classMaster = loadAccessible(classId, principal);
        List<BulkAssignResult> results = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();

        for (Long enrollmentId : enrollmentIds) {
            if (enrollmentId == null) {
                results.add(BulkAssignResult.failed(null, "등록 건은 필수입니다."));
                continue;
            }
            if (!seen.add(enrollmentId)) {
                results.add(new BulkAssignResult(
                        enrollmentId, null, BulkAssignResult.Status.DUPLICATE, "같은 요청에 두 번 들어왔습니다."));
                continue;
            }
            try {
                ClassMemberView view = assignInto(classMaster, enrollmentId);
                results.add(new BulkAssignResult(enrollmentId,
                        view.assignment().getEnrollment().getStudentName(),
                        BulkAssignResult.Status.ASSIGNED, null));
            } catch (BusinessException e) {
                // 검증 실패라 아직 아무것도 쓰지 않았다 — 다음 학생 처리에 영향이 없다
                results.add(BulkAssignResult.failed(enrollmentId, e.getMessage()));
            }
        }

        int memberCount = memberCounts(List.of(classMaster.getId()))
                .getOrDefault(classMaster.getId(), 0);
        return new BulkAssignOutcome(classMaster, memberCount, results);
    }

    /**
     * 일괄 배정 결과 전체. 반 현재 인원·정원을 함께 준다 —
     * 화면이 직후에 목록을 다시 부르지 않아도 충원율을 갱신할 수 있다.
     */
    public record BulkAssignOutcome(ClassMaster classMaster, int memberCount,
                                    List<BulkAssignResult> results) {

        /** 정원 초과 여부. 정원이 없는 반은 초과라는 개념 자체가 없다. */
        public boolean overCapacity() {
            Short capacity = classMaster.getCapacity();
            return capacity != null && memberCount > capacity;
        }
    }

    /** 건별 결과. 화면이 실패한 줄만 다시 고를 수 있어야 한다. */
    public record BulkAssignResult(Long enrollmentId, String studentName,
                                   Status status, String message) {

        public enum Status {
            /** 배정됐다. */
            ASSIGNED,
            /** 같은 요청에 중복으로 들어와 건너뛰었다 — 앞엣것이 이미 배정됐으므로 오류가 아니다. */
            DUPLICATE,
            /** 배정하지 못했다. 사유는 {@code message}. */
            FAILED
        }

        static BulkAssignResult failed(Long enrollmentId, String message) {
            return new BulkAssignResult(enrollmentId, null, Status.FAILED, message);
        }
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
