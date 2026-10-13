package com.dlab.domain.patrol.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.patrol.entity.PatrolMark;
import com.dlab.domain.patrol.entity.PatrolRecord;
import com.dlab.domain.patrol.repository.PatrolRecordRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 순찰 관리 (2026-10-13 디랩 피드백 1-3 — 신규 기능).
 *
 * <p>지금은 <b>종이에 인쇄해 체크한 뒤 DSA 에 다시 입력</b>한다 — 같은 일을 두 번 한다.
 * 태블릿에서 좌석을 눌러 바로 넣는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatrolService {

    private final PatrolRecordRepository recordRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /**
     * 그날 그 회차 기록.
     *
     * <p>회차를 비우면 그날 전부다. 날짜를 비우면 <b>오늘</b>이다 — 순찰은 당일 업무라
     * 화면이 날짜를 안 보내는 것이 기본 흐름이다.
     */
    @Transactional(readOnly = true)
    public List<PatrolRecord> records(AuthPrincipal me, Long academyId,
                                      LocalDate date, Short roundNo) {
        Long scope = me.requireAcademyScope(academyId);
        if (roundNo != null) {
            requireValidRound(roundNo);
        }
        return recordRepository.findByRound(scope,
                date == null ? LocalDate.now(clock) : date, roundNo);
    }

    /**
     * 순찰 기록 저장.
     *
     * <h2>★ 한 번에 여러 건을 받는다</h2>
     * 순찰은 <b>좌석을 연달아 누르는</b> 업무다. 한 건씩 보내면 네트워크가 느린 태블릿에서
     * 누른 것이 빠진 채 넘어간다.
     *
     * <h2>★ 같은 회차를 다시 찍으면 덮어쓴다</h2>
     * 순찰을 다시 돌 수 있고, 두 행이 남으면 <b>「그 회차에 무엇이었나」에 답이 둘이 된다.</b>
     *
     * <h2>★ 다른 지점 학생은 거절한다</h2>
     * 학생 id 를 화면이 보내므로 <b>값만 바꿔 보내면 남의 지점 기록을 만들 수 있다.</b>
     *
     * @return 저장된 기록(신규 + 갱신)
     */
    @Transactional
    public List<PatrolRecord> record(AuthPrincipal me, Long academyId, LocalDate date,
                                     short roundNo, List<MarkCommand> marks) {
        Long scope = me.requireAcademyScope(academyId);
        requireValidRound(roundNo);
        if (marks == null || marks.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "찍을 좌석을 골라 주세요.");
        }
        LocalDate target = date == null ? LocalDate.now(clock) : date;

        List<PatrolRecord> saved = new java.util.ArrayList<>();
        for (MarkCommand mark : marks) {
            StudentEnrollment enrollment = enrollmentRepository.findById(mark.enrollmentId())
                    .filter(e -> !e.isDeleted())
                    .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
            if (!enrollment.getAcademy().getId().equals(scope)) {
                throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
            }

            saved.add(recordRepository.findOne(target, roundNo, enrollment.getId())
                    .map(existing -> {
                        existing.remark(mark.mark(), mark.seatCd());
                        return existing;
                    })
                    .orElseGet(() -> recordRepository.save(new PatrolRecord(
                            enrollment, target, roundNo, mark.seatCd(), mark.mark()))));
        }
        log.info("순찰 기록: academy={}, date={}, round={}, 건수={}, 처리자={}",
                scope, target, roundNo, saved.size(), me.accountId());
        return saved;
    }

    private static void requireValidRound(short roundNo) {
        if (roundNo < PatrolRecord.MIN_ROUND || roundNo > PatrolRecord.MAX_ROUND) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "순찰 회차는 %d~%d 입니다.".formatted(
                            PatrolRecord.MIN_ROUND, PatrolRecord.MAX_ROUND));
        }
    }

    /**
     * @param seatCd 찍은 시점의 좌석번호. <b>기록용</b>이라 비어 있어도 된다 —
     *               학생 목록 탭에서 찍으면 좌석을 안 보낼 수 있다
     */
    public record MarkCommand(Long enrollmentId, String seatCd, PatrolMark mark) {
    }
}
