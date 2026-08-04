package com.dlab.domain.master.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.master.entity.DormAssignment;
import com.dlab.domain.master.entity.DormRoom;
import com.dlab.domain.master.repository.DormAssignmentRepository;
import com.dlab.domain.master.repository.DormRoomRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 기숙사 방 관리·배정 (배정관리, 로드맵 Phase 2).
 *
 * <p><b>사물함과 구조가 다르다.</b> 한 방에 여러 명이 들어가므로 배정을 별도 테이블로 뺐고,
 * 그래서 <b>정원 확인이 필요하다</b> — 사물함은 "점유 여부" 한 번이면 끝이지만 여기는 인원을 센다.
 *
 * <p><b>퇴실해도 행을 지우지 않는다.</b> 누가 언제 어느 방을 썼는지가 남아야 한다.
 */
@Service
@RequiredArgsConstructor
public class DormService {

    private final DormRoomRepository roomRepository;
    private final DormAssignmentRepository assignmentRepository;
    private final AcademyRepository academyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<DormRoom> rooms(Long academyId, short year, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return roomRepository.findByAcademyIdAndYearAndDeletedFalseOrderByBuildingAscRoomNoAsc(
                academyId, year);
    }

    @Transactional(readOnly = true)
    public List<DormAssignment> occupants(Long roomId, AuthPrincipal principal) {
        DormRoom room = loadRoom(roomId);
        verifyAccess(room.getAcademy().getId(), principal);
        return assignmentRepository.findActiveByRoomId(roomId);
    }

    @Transactional
    public DormRoom createRoom(Long academyId, short year, String building, String roomNo,
                               short capacity, String gender, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (capacity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "정원은 1명 이상이어야 합니다.");
        }
        if (roomRepository.existsByAcademyIdAndYearAndBuildingAndRoomNoAndDeletedFalse(
                academyId, year, building, roomNo)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 동·호수의 방이 있습니다.");
        }
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        return roomRepository.save(new DormRoom(academy, year, building, roomNo, capacity, gender));
    }

    /**
     * 방 정보 수정.
     *
     * <p><b>정원을 현재 인원보다 작게 줄일 수 없다.</b> 줄이면 이미 들어가 있는 학생 중 누가
     * 나가야 하는지 시스템이 정할 수 없고, 방치하면 정원 초과 상태가 조용히 남는다.
     */
    @Transactional
    public DormRoom updateRoom(Long roomId, String building, String roomNo, Short capacity,
                               String gender, AuthPrincipal principal) {
        DormRoom room = loadRoom(roomId);
        verifyAccess(room.getAcademy().getId(), principal);

        if (capacity != null) {
            long occupied = assignmentRepository.countActiveByRoomId(roomId);
            if (capacity < occupied) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "현재 %d명이 배정돼 있어 정원을 %d명으로 줄일 수 없습니다.".formatted(occupied, capacity));
            }
        }
        room.updateRoom(building, roomNo, capacity, gender);
        return room;
    }

    /**
     * 배정.
     *
     * <p><b>방 행을 잠그고 시작한다.</b> 정원 N은 좌석·사물함의 "1칸 1명"과 달리 유니크 제약으로
     * 표현할 수 없어, 잠그지 않으면 동시 요청이 전부 정원 검사를 통과한다.
     *
     * <p>순서가 중요하다 — <b>이전 배정을 먼저 비우고 flush</b>한 뒤 새 배정을 넣는다.
     * Hibernate는 기본적으로 INSERT를 UPDATE보다 먼저 내보내는데, 그러면 이전 배정이 아직
     * 활성인 상태로 새 행이 들어가 {@code uq_dorm_assignment_active}(부분 유니크)에 걸린다.
     * 좌석·반 배정과 같은 함정이다.
     */
    @Transactional
    public DormAssignment assign(Long roomId, Long enrollmentId, AuthPrincipal principal) {
        // ★ 방 행을 잠그고 시작한다. 정원 N은 DB 제약으로 표현할 수 없어
        //   잠그지 않으면 동시 요청이 전부 "자리 있음"을 보고 통과한다.
        DormRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        verifyAccess(room.getAcademy().getId(), principal);

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!enrollment.getAcademy().getId().equals(room.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "다른 지점 기숙사에는 배정할 수 없습니다.");
        }
        if (!room.accepts(enrollment.getStudent().getGender())) {
            throw new BusinessException(ErrorCode.DORM_GENDER_MISMATCH);
        }

        // 이전 배정 해제가 먼저다 — 옮기는 경우 인원수 계산에도 영향을 준다
        assignmentRepository.findActiveByEnrollmentId(enrollmentId).ifPresent(previous -> {
            previous.release(clock.instant());
            // ★ 부분 유니크 때문에 여기서 반드시 flush. 위 주석 참고
            assignmentRepository.flush();
        });

        if (assignmentRepository.countActiveByRoomId(roomId) >= room.getCapacity()) {
            throw new BusinessException(ErrorCode.DORM_ROOM_FULL);
        }
        return assignmentRepository.save(new DormAssignment(room.getAcademy(), room, enrollment));
    }

    /** 퇴실. 행은 남기고 {@code released_at}만 찍는다. */
    @Transactional
    public void release(Long enrollmentId, AuthPrincipal principal) {
        DormAssignment assignment = assignmentRepository.findActiveByEnrollmentId(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DORM_NOT_ASSIGNED));
        verifyAccess(assignment.getAcademy().getId(), principal);
        assignment.release(clock.instant());
    }

    private DormRoom loadRoom(Long roomId) {
        return roomRepository.findDetailById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
    }

    private void verifyAccess(Long academyId, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }
}
