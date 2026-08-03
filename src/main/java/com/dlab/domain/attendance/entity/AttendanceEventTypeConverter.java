package com.dlab.domain.attendance.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * DB에는 DSA 원본 코드(S/T/A/D/N/C/R) 한 글자로 저장한다.
 * {@code EnumType.STRING}을 쓰면 "CHECK_IN" 같은 우리 이름이 들어가 DSA 호환 응답을 만들 때
 * 매번 역매핑해야 하고, DB를 직접 조회했을 때 레거시와 대조가 안 된다.
 */
@Converter(autoApply = false)
public class AttendanceEventTypeConverter implements AttributeConverter<AttendanceEventType, String> {

    @Override
    public String convertToDatabaseColumn(AttendanceEventType attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public AttendanceEventType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AttendanceEventType.fromCode(dbData);
    }
}
