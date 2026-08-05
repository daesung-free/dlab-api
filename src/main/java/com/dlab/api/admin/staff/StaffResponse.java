package com.dlab.api.admin.staff;

import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.Teacher;

/** 선생님·직원 응답. 어느 쪽인지 {@code kind}로 구분한다. */
public record StaffResponse(
        String kind,
        Long id,
        Long academyId,
        String name,
        String deptName,
        String positionName,
        String phone,
        String email
) {

    /** 담당선생님(사감) — 반 담임·승인 에스컬레이션 대상이 될 수 있다. */
    public static StaffResponse from(Teacher t) {
        return new StaffResponse("TEACHER", t.getId(), t.getAcademy().getId(),
                t.getName(), null, null, t.getPhone(), t.getEmail());
    }

    /** 행정 — 행정선생님 포함. 학생 가입 승인 주체다. */
    public static StaffResponse from(Employee e) {
        return new StaffResponse("EMPLOYEE", e.getId(), e.getAcademy().getId(),
                e.getName(), e.getDeptName(), e.getPositionName(), e.getPhone(), e.getEmail());
    }
}
