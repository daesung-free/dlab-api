package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.facility.repository.SeatMasterRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 좌석 변경 (DSA 3.23 {@code setSeatChgProc}).
 *
 * <p>키오스크 25개 중 <b>출결 2개와 함께 몇 안 되는 쓰기</b>다.
 *
 * <h2>남의 자리를 뺏지 않는다</h2>
 * 대상 좌석에 다른 학생이 배정돼 있으면 <b>거절한다.</b> 기존 배정을 풀고 새로 넣으면
 * 코드는 간단해지지만, 앉아 있던 학생이 아무 통보 없이 자리를 잃는다.
 *
 * <h2>같은 자리로 바꾸면 성공이다</h2>
 * 키오스크는 자기 쪽 좌석 변경을 마친 뒤 우리에게 동기화 호출을 보내고, 실패하면
 * 재시도할 수 있다. 이미 그 자리인데 오류를 주면 <b>영원히 실패로 남는다</b> —
 * 멱등하게 성공으로 처리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KioskSeatChangeService {

    private final StudentEnrollmentRepository enrollmentRepository;
    private final SeatMasterRepository seatMasterRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final EntityManager em;
    private final Clock clock;

    public void changeSeat(Long academyId, String rfidNo, String seatCd) {
        if (seatCd == null || seatCd.isBlank()) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }

        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);
        // ★ 단말은 구역 없이 좌석번호만 보낸다. 그 값이 지점 안에서 유일하려면
        //   우리 seat_cd 가 아니라 관까지 반영된 kiosk_seat_cd 여야 한다
        SeatMaster seat = seatMasterRepository.findByAcademyIdAndKioskSeatCd(academyId, seatCd)
                .orElseThrow(() -> new DsaApiException(DsaCode.INVALID_KEY, "없는 좌석입니다."));

        // 통로·미사용 좌석에는 배정하지 않는다. 좌석표에는 보이지만 앉을 수 없는 자리다
        if (!seat.isUsable()) {
            throw new DsaApiException(DsaCode.INVALID_KEY, "사용할 수 없는 좌석입니다.");
        }

        Optional<SeatAssignment> current =
                seatAssignmentRepository.findActiveByEnrollmentId(enrollment.getId());
        if (current.isPresent() && current.get().getSeat().getId().equals(seat.getId())) {
            return;   // 이미 그 자리. 멱등하게 성공
        }

        Optional<SeatAssignment> occupant = seatAssignmentRepository.findActiveBySeatId(seat.getId());
        if (occupant.isPresent()) {
            throw new DsaApiException(DsaCode.SEAT_OCCUPIED);
        }

        Instant now = Instant.now(clock);
        current.ifPresent(assignment -> assignment.release(now));

        // ★ 해제를 먼저 반영한다. 같은 트랜잭션이라도 Hibernate가 INSERT를 UPDATE보다
        //   먼저 내보내면 "한 등록 건에 현재 좌석 하나" 부분 유니크 인덱스에 걸린다
        em.flush();

        try {
            seatAssignmentRepository.save(new SeatAssignment(
                    enrollment.getAcademy(), seat, enrollment));
            em.flush();
        } catch (DataIntegrityViolationException e) {
            // 조회와 저장 사이에 다른 키오스크가 같은 자리를 채간 경우.
            // 사전 조회로는 막을 수 없고 부분 유니크 인덱스만이 막는다
            throw new DsaApiException(DsaCode.SEAT_OCCUPIED);
        }
    }

    private StudentEnrollment requireEnrollment(Long academyId, String rfidNo) {
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .filter(e -> e.getAcademy().getId().equals(academyId))
                .orElseThrow(() -> new DsaApiException(DsaCode.INVALID_KEY, "등록되지 않은 카드입니다."));
    }
}
