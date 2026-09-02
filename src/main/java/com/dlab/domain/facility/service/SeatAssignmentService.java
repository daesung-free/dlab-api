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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 좌석 일괄 배정 — <b>전부-아니면-전무</b>.
     *
     * <h2>왜 배열을 받나</h2>
     * 화면의 "선택 N명 일괄 배정"이 단건 API를 N번 부르고 있었다. 중간에 하나가 실패하면
     * <b>앞의 것은 이미 들어가 있고 되돌릴 방법이 없다</b> — 데스크는 어디까지 됐는지 모르는
     * 채로 다시 눌러야 했다.
     *
     * <h2>왜 건별 결과가 아니라 전부-아니면-전무인가</h2>
     * 실패 사유가 대부분 <b>"그 자리에 이미 다른 학생이 있다"</b>인데, 이건 명단을 고쳐서
     * 다시 올릴 일이지 절반만 반영해 둘 일이 아니다. 대신 <b>실패한 건을 전부 모아서</b>
     * 돌려준다 — 하나씩 알려주면 고쳐 올릴 때마다 한 건씩만 발견하게 된다.
     *
     * <h2>자리 맞바꾸기가 된다</h2>
     * 배정 대상 학생이 이미 쓰던 자리는 <b>이 요청 안에서 먼저 반납된다.</b> 그래서
     * A↔B 교환도 한 번에 들어간다. 반대로 <b>이 요청에 없는 학생</b>이 앉아 있는 자리면
     * 충돌이다 — 남의 자리를 말없이 빼앗지 않는다.
     */
    @Transactional
    public List<SeatAssignment> assignAll(List<SeatAssignRequest> requests,
                                          AuthPrincipal principal) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "배정할 건이 없습니다.");
        }

        List<String> failures = new ArrayList<>();
        Set<Long> seenSeats = new LinkedHashSet<>();
        Set<Long> seenEnrollments = new LinkedHashSet<>();
        List<Long> enrollmentIds = requests.stream().map(SeatAssignRequest::enrollmentId).toList();

        // 1) 요청 안에서의 중복. DB까지 가기 전에 잡는다 — 같은 좌석에 두 명을 보내면
        //    어느 쪽이 이겨야 하는지 우리가 정할 문제가 아니다
        for (SeatAssignRequest r : requests) {
            if (!seenSeats.add(r.seatId())) {
                failures.add("좌석 " + r.seatId() + ": 같은 좌석이 요청에 두 번 있습니다.");
            }
            if (!seenEnrollments.add(r.enrollmentId())) {
                failures.add("학생(등록 " + r.enrollmentId() + "): 같은 학생이 요청에 두 번 있습니다.");
            }
        }

        // 2) 건별 검증. 실패해도 멈추지 않고 끝까지 모은다
        List<Resolved> resolved = new ArrayList<>();
        for (SeatAssignRequest r : requests) {
            SeatMaster seat = seatMasterRepository.findDetailById(r.seatId()).orElse(null);
            if (seat == null) {
                failures.add("좌석 " + r.seatId() + ": 좌석을 찾을 수 없습니다.");
                continue;
            }
            if (!principal.canAccessAcademy(seat.getAcademy().getId())) {
                throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
            }
            if (!seat.isUsable()) {
                failures.add(seat.getSeatCd() + ": 사용할 수 없는 좌석입니다.");
                continue;
            }

            StudentEnrollment enrollment = enrollmentRepository.findById(r.enrollmentId())
                    .orElse(null);
            if (enrollment == null) {
                failures.add("등록 " + r.enrollmentId() + ": 등록 건을 찾을 수 없습니다.");
                continue;
            }
            if (!enrollment.getAcademy().getId().equals(seat.getAcademy().getId())) {
                failures.add(seat.getSeatCd() + ": 다른 지점 학생은 배정할 수 없습니다.");
                continue;
            }

            // 이 요청에 없는 학생이 앉아 있으면 충돌. 요청 안의 학생이면 아래에서 반납된다
            var occupied = seatAssignmentRepository.findActiveBySeatId(seat.getId());
            if (occupied.isPresent()
                    && !enrollmentIds.contains(occupied.get().getEnrollment().getId())) {
                failures.add(seat.getSeatCd() + ": 이미 "
                        + occupied.get().getEnrollment().getStudentNo() + " 학생이 배정돼 있습니다.");
                continue;
            }
            resolved.add(new Resolved(seat, enrollment));
        }

        if (!failures.isEmpty()) {
            throw new BusinessException(ErrorCode.SEAT_ASSIGN_PARTIAL_FAILED,
                    "배정하지 못한 건이 있어 전체를 취소했습니다. " + String.join(" / ", failures));
        }

        // 3) 기존 배정을 먼저 전부 반납한다. INSERT가 UPDATE보다 먼저 나가면
        //    부분 유니크(uq_seat_assignment_active_*)에 걸린다
        Instant now = Instant.now(clock);
        for (Resolved item : resolved) {
            seatAssignmentRepository.findActiveByEnrollmentId(item.enrollment().getId())
                    .ifPresent(previous -> previous.release(now));
            seatAssignmentRepository.findActiveBySeatId(item.seat().getId())
                    .ifPresent(previous -> previous.release(now));
        }
        seatAssignmentRepository.flush();

        return resolved.stream()
                .map(item -> seatAssignmentRepository.save(new SeatAssignment(
                        item.seat().getAcademy(), item.seat(), item.enrollment())))
                .toList();
    }

    private record Resolved(SeatMaster seat, StudentEnrollment enrollment) {
    }

    /** 일괄 배정 한 건. */
    public record SeatAssignRequest(Long seatId, Long enrollmentId) {
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
