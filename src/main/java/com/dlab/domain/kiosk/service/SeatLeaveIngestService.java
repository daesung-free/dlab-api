package com.dlab.domain.kiosk.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.kiosk.entity.SeatLeaveEventType;
import com.dlab.domain.kiosk.entity.SeatLeaveLog;
import com.dlab.domain.kiosk.repository.SeatLeaveLogRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 이탈·복귀 로그 적재 (F-4.3-2).
 *
 * <p>키오스크가 이탈/복귀 시점에 호출한다. <b>응답 200이 곧 ack</b>이고, 키오스크는 200을
 * 받은 행만 전송완료로 표시한 뒤 나머지를 다시 보낸다 — 유실이 없다.
 *
 * <p><b>★ 한 건이 틀려도 배치 전체를 거절하지 않는다.</b> 거절하면 키오스크가 그 배치를
 * 영원히 재전송하며 <b>큐가 영영 안 빠진다.</b> 건별로 결과를 돌려주고, 처리된 것은
 * 처리했다고 알린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatLeaveIngestService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SeatLeaveLogRepository logRepository;
    private final AcademyRepository academyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    /**
     * 한 건.
     *
     * @param sourceRowId 키오스크 {@code seat_leaves} 행 ID. 멱등키라 필수다
     * @param rfidNo      카드번호. {@code studentNo}와 <b>둘 중 하나</b>는 있어야 학생을 찾는다
     */
    public record Event(Long sourceRowId, String rfidNo, String studentNo,
                        String areaCd, String seatCd,
                        SeatLeaveEventType eventType, Instant occurredAt) {}

    /** 건별 처리 결과. 키오스크가 이걸 보고 전송완료 표시를 한다. */
    public record Result(Long sourceRowId, Status status, String message) {

        public enum Status {
            /** 새로 저장했다. */
            ACCEPTED,
            /** 이미 받은 행이다 — 재전송이므로 <b>정상</b>이고, 전송완료로 표시하면 된다. */
            DUPLICATE,
            /** 저장했지만 학생을 못 찾았다. 재전송해도 결과가 같으므로 전송완료로 표시한다. */
            ACCEPTED_UNRESOLVED,
            /** 값이 잘못돼 저장하지 못했다. 고쳐서 다시 보내야 한다. */
            REJECTED
        }
    }

    /**
     * 일괄 적재.
     *
     * <p><b>배열로 받는 이유</b> — 재전송이 밀렸을 때 한 건씩 보내면 왕복이 그만큼 늘어난다.
     */
    @Transactional
    public List<Result> ingest(Long academyId, List<Event> events) {
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        // ★ 이미 받은 행을 한 번에 조회한다. 건별로 조회하면 배치 크기만큼 쿼리가 나간다
        Set<Long> known = new HashSet<>(logRepository.findExistingSourceRowIds(academyId,
                events.stream().map(Event::sourceRowId).filter(java.util.Objects::nonNull).toList()));

        // 같은 배치 안에 같은 행이 두 번 들어오는 경우도 있다(키오스크 재시도가 겹칠 때)
        Set<Long> seenInBatch = new HashSet<>();

        return events.stream()
                .map(event -> ingestOne(academy, event, known, seenInBatch))
                .toList();
    }

    private Result ingestOne(Academy academy, Event event, Set<Long> known, Set<Long> seenInBatch) {
        if (event.sourceRowId() == null) {
            // 멱등키가 없으면 재전송이 중복으로 쌓인다 — 받을 수 없다
            return new Result(null, Result.Status.REJECTED, "sourceRowId는 필수입니다.");
        }
        if (event.eventType() == null || event.occurredAt() == null) {
            return new Result(event.sourceRowId(), Result.Status.REJECTED,
                    "eventType·occurredAt은 필수입니다.");
        }
        if (known.contains(event.sourceRowId()) || !seenInBatch.add(event.sourceRowId())) {
            return new Result(event.sourceRowId(), Result.Status.DUPLICATE, null);
        }

        StudentEnrollment enrollment = resolveStudent(academy, event);
        short year = enrollment != null
                ? enrollment.getYear()
                : (short) LocalDate.ofInstant(event.occurredAt(), KST).getYear();

        try {
            logRepository.save(new SeatLeaveLog(academy, year, event.sourceRowId(), enrollment,
                    event.rfidNo(), event.studentNo(), event.areaCd(), event.seatCd(),
                    event.eventType(), event.occurredAt()));
        } catch (DataIntegrityViolationException e) {
            // 유니크 위반 — 다른 요청이 같은 행을 먼저 넣었다는 뜻이라 중복과 같다.
            // 조회로 걸러도 동시 호출에서는 여기까지 온다
            return new Result(event.sourceRowId(), Result.Status.DUPLICATE, null);
        }

        if (enrollment == null) {
            // ★ 거절하지 않는다. 거절하면 키오스크가 이 행을 영원히 재전송한다
            log.warn("좌석이탈 로그 학생 미해결: academyId={}, sourceRowId={}, rfidNo={}, stdNo={}",
                    academy.getId(), event.sourceRowId(), event.rfidNo(), event.studentNo());
            return new Result(event.sourceRowId(), Result.Status.ACCEPTED_UNRESOLVED,
                    "학생을 찾지 못해 식별자만 보관했습니다.");
        }
        return new Result(event.sourceRowId(), Result.Status.ACCEPTED, null);
    }

    /**
     * 카드번호 우선, 없으면 학번.
     *
     * <p>카드는 {@code current} 등록 건만 본다 — 빠뜨리면 퇴원생 카드가 통과한다.
     *
     * <h2>★ 토큰 지점과 학생 지점이 다르면 연결하지 않는다</h2>
     * 지점은 <b>토큰</b>이 정하는데 학생은 카드번호로 <b>전 지점</b>에서 찾는다. 그래서
     * 32번 지점 학생의 카드가 31번 토큰으로 들어오면 <b>행 하나가 두 지점에 걸친다</b> —
     * {@code academy_id}는 31, 연결된 학생은 32번 소속. 저장은 성공하고 응답도 정상이라
     * <b>지점별 집계가 어긋난 뒤에야 발견되고 원인을 찾기 어렵다.</b>
     *
     * <p>키오스크가 배치를 지점별로 나누지 않으면 실제로 발생한다. 그래서 어긋나면
     * <b>연결하지 않고 미해결로 떨군다</b> — 기록은 남되(원본 식별자 보관) 잘못된 학생에
     * 붙지는 않게 한다. 거절하지 않는 이유는 다른 미해결과 같다(영원한 재전송 방지).
     */
    private StudentEnrollment resolveStudent(Academy academy, Event event) {
        StudentEnrollment found = lookup(academy, event);
        if (found == null) {
            return null;
        }
        if (!found.getAcademy().getId().equals(academy.getId())) {
            log.warn("좌석이탈 로그 지점 불일치 — 연결하지 않는다: 토큰지점={}, 학생지점={}, "
                            + "sourceRowId={}, rfidNo={}",
                    academy.getId(), found.getAcademy().getId(), event.sourceRowId(),
                    event.rfidNo());
            return null;
        }
        return found;
    }

    /**
     * ★ <b>학번은 지점 안에서만 찾는다.</b> 학번이 지점마다 따로 매겨져서 전 지점을 뒤지면
     * 같은 학번이 여러 건 걸리고, 한 건을 기대한 조회가 예외로 끝나 <b>배치 전체가
     * 500으로 실패</b>한다(카드번호로 보낼 때는 나지 않아 한동안 안 보였다).
     *
     * <p>카드번호는 전 지점에서 찾은 뒤 {@link #resolveStudent}가 지점을 대조한다 —
     * 카드가 다른 지점 학생이면 잘못 붙는 것보다 미해결로 남기는 편이 낫기 때문이다.
     */
    private StudentEnrollment lookup(Academy academy, Event event) {
        if (event.rfidNo() != null && !event.rfidNo().isBlank()) {
            var found = enrollmentRepository.findCurrentByRfidNo(event.rfidNo());
            if (found.isPresent()) {
                return found.get();
            }
        }
        if (event.studentNo() != null && !event.studentNo().isBlank()) {
            return enrollmentRepository
                    .findCurrentByStudentNo(academy.getId(), event.studentNo())
                    .orElse(null);
        }
        return null;
    }
}
