package com.dlab.domain.holiday.service;

import com.dlab.common.holiday.HolidayProvider;
import com.dlab.domain.holiday.entity.Holiday;
import com.dlab.domain.holiday.repository.HolidayRepository;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 기반 공휴일 조회.
 *
 * <p>공공데이터포털 특일정보 API를 붙이더라도 <b>이 구현을 대체하는 게 아니라
 * 이 테이블에 적재하는 방향</b>이어야 한다 — API 장애 시 급식 신청이 통째로 멈추면 안 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DbHolidayProvider implements HolidayProvider {

    private final HolidayRepository holidayRepository;

    @Override
    public boolean isHoliday(Long academyId, LocalDate date) {
        return !holidaysIn(academyId, date, date).isEmpty();
    }

    @Override
    public Set<LocalDate> holidaysIn(Long academyId, LocalDate from, LocalDate to) {
        var holidays = academyId == null
                ? holidayRepository.findNationwideInRange(from, to)
                : holidayRepository.findInRange(academyId, from, to);
        return holidays.stream()
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toUnmodifiableSet());
    }
}
