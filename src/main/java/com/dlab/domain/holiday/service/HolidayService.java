package com.dlab.domain.holiday.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.holiday.entity.Holiday;
import com.dlab.domain.holiday.entity.HolidayType;
import com.dlab.domain.holiday.repository.HolidayRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공휴일 등록·수정·삭제.
 *
 * <p><b>요구사항에 등록 주체·시점 정의가 없다</b>(문서 전체에서 공휴일은 "제외한다"는
 * 규칙으로만 등장한다). 수기 입력으로 진행하기로 했으므로 아래 규칙을 여기서 정한다.
 * <ul>
 *   <li>법정공휴일은 <b>전 지점에 적용</b>되므로 전 지점 권한자(본사)만 등록한다 —
 *       지점 관리자가 넣으면 다른 지점 급식까지 막힌다.</li>
 *   <li>지점 자체 휴일(개원기념일 등)은 <b>자기 지점만</b> 등록할 수 있다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HolidayService {

    private final HolidayRepository holidayRepository;

    /** 기간 조회. 전 지점 공통 + 해당 지점 것을 함께 돌려준다. */
    public List<Holiday> findInRange(AuthPrincipal principal, LocalDate from, LocalDate to) {
        Long academyId = principal.academyScopeFilter();
        return academyId == null
                ? holidayRepository.findNationwideInRange(from, to)
                : holidayRepository.findInRange(academyId, from, to);
    }

    /**
     * 등록.
     *
     * @param academyId {@code null}이면 전 지점 공통(본사만 가능)
     */
    @Transactional
    public Holiday register(AuthPrincipal principal, Long academyId, LocalDate date,
                            String name, HolidayType type) {
        validateWritable(principal, academyId, type);
        validateNotDuplicated(academyId, date);

        Holiday holiday = academyId == null
                ? Holiday.nationwide(date, name, type)
                : Holiday.ofAcademy(academyId, date, name);
        holiday.recordCreatedBy(principal.accountId());
        return holidayRepository.save(holiday);
    }

    @Transactional
    public Holiday rename(AuthPrincipal principal, Long holidayId, String name) {
        Holiday holiday = findWritable(principal, holidayId);
        holiday.rename(name);
        return holiday;
    }

    /** soft delete. 물리 삭제하면 과거 급식 신청이 어느 규칙으로 계산됐는지 추적이 끊긴다. */
    @Transactional
    public void remove(AuthPrincipal principal, Long holidayId) {
        findWritable(principal, holidayId).markDeleted();
    }

    private Holiday findWritable(AuthPrincipal principal, Long holidayId) {
        Holiday holiday = holidayRepository.findById(holidayId)
                .filter(h -> !h.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.HOLIDAY_NOT_FOUND));
        validateWritable(principal, holiday.getAcademyId(), holiday.getHolidayType());
        return holiday;
    }

    private void validateWritable(AuthPrincipal principal, Long academyId, HolidayType type) {
        if (academyId == null) {
            // 전 지점 공통 — 본사만
            if (!principal.allAcademy()) {
                throw new BusinessException(ErrorCode.NATIONWIDE_HOLIDAY_FORBIDDEN);
            }
            return;
        }
        // 지점 휴일 — 자기 지점만
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (type != HolidayType.ACADEMY) {
            // 지점에 붙는 휴일은 자체휴일뿐이다. 법정공휴일을 지점별로 넣으면
            // 같은 날이 지점마다 다르게 처리돼 정합성이 깨진다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지점 휴일은 ACADEMY 유형만 등록할 수 있습니다.");
        }
    }

    /**
     * 중복 검사. DB 부분 유니크 인덱스가 최종 방어선이지만,
     * 제약 위반 예외는 사용자에게 원인을 알려주지 못하므로 여기서 먼저 막는다.
     */
    private void validateNotDuplicated(Long academyId, LocalDate date) {
        List<Holiday> existing = academyId == null
                ? holidayRepository.findNationwideInRange(date, date)
                : holidayRepository.findInRange(academyId, date, date);

        boolean duplicated = existing.stream().anyMatch(h ->
                academyId == null ? h.isNationwide() : academyId.equals(h.getAcademyId()));
        if (duplicated) {
            throw new BusinessException(ErrorCode.HOLIDAY_DUPLICATED);
        }
    }
}
