package com.dlab.domain.facility.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.facility.repository.SeatMasterRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 좌석 배정 (배정관리).
 *
 * <p>스키마는 V2에서 왔고 <b>키오스크 조회는 다른 담당자</b>가 맡는다. 여기는 쓰기만 한다.
 *
 * <p>현재 배정은 {@code releasedAt IS NULL}로 표현하고, 좌석당·학생당 하나뿐이라는
 * 부분 유니크 인덱스가 DB에 걸려 있다.
 */
@Service
@RequiredArgsConstructor
public class SeatAssignmentService {

    private final SeatAssignmentRepository seatAssignmentRepository;
    private final SeatMasterRepository seatMasterRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SeatAssignment> byStudyArea(Long studyAreaId) {
        return seatAssignmentRepository.findActiveByStudyAreaId(studyAreaId);
    }

    /**
     * 좌석 배정.
     *
     * <p>학생이 이미 다른 좌석에 있으면 그 배정을 먼저 반납한다. 좌석 쪽이 점유돼 있으면
     * 거부한다 — 자리를 빼앗는 동작은 관리자가 명시적으로 반납시킨 뒤 하도록 한다.
     */
    @Transactional
    public SeatAssignment assign(Long seatId, Long enrollmentId, AuthPrincipal principal) {
        SeatMaster seat = seatMasterRepository.findDetailById(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
        if (!principal.canAccessAcademy(seat.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (!seat.isUsable()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사용할 수 없는 좌석입니다.");
        }

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!enrollment.getAcademy().getId().equals(seat.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "다른 지점 좌석에는 배정할 수 없습니다.");
        }

        seatAssignmentRepository.findActiveBySeatId(seatId).ifPresent(occupied -> {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_OCCUPIED);
        });

        Instant now = Instant.now(clock);
        seatAssignmentRepository.findActiveByEnrollmentId(enrollmentId).ifPresent(previous -> {
            previous.release(now);
            // ★ Hibernate가 INSERT를 UPDATE보다 먼저 내보내면 이전 배정이 살아 있는 상태로
            //   새 행이 들어가 uq_seat_assignment_active_enrollment에 걸린다.
            seatAssignmentRepository.flush();
        });

        return seatAssignmentRepository.save(
                new SeatAssignment(seat.getAcademy(), seat, enrollment));
    }

    /** 반납. 좌석을 비워 다른 학생이 쓸 수 있게 한다. */
    @Transactional
    public void release(Long enrollmentId, AuthPrincipal principal) {
        SeatAssignment assignment = seatAssignmentRepository.findActiveByEnrollmentId(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_ASSIGNED));
        if (!principal.canAccessAcademy(assignment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        assignment.release(Instant.now(clock));
    }
}
