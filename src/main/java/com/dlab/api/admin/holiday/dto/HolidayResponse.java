package com.dlab.api.admin.holiday.dto;

import com.dlab.domain.holiday.entity.Holiday;
import com.dlab.domain.holiday.entity.HolidayType;
import java.time.LocalDate;

/**
 * @param nationwide 전 지점 공통 여부. 화면에서 "본사 등록"과 "우리 지점 등록"을
 *                   구분해 보여줘야 지점 관리자가 남의 것을 지우려다 막히는 일이 없다
 */
public record HolidayResponse(
        Long id,
        Long academyId,
        boolean nationwide,
        LocalDate date,
        String name,
        HolidayType type
) {

    public static HolidayResponse from(Holiday holiday) {
        return new HolidayResponse(
                holiday.getId(),
                holiday.getAcademyId(),
                holiday.isNationwide(),
                holiday.getHolidayDate(),
                holiday.getName(),
                holiday.getHolidayType());
    }
}
