package com.dlab.api.admin.clazz;

import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;

public record ClassResponse(
        Long id,
        Long academyId,
        short year,
        String name,
        ClassType classType,
        Long homeroomTeacherId,
        String homeroomTeacherName
) {

    public static ClassResponse from(ClassMaster c) {
        return new ClassResponse(
                c.getId(),
                c.getAcademy().getId(),
                c.getYear(),
                c.getName(),
                c.getClassType(),
                c.getHomeroomTeacher() == null ? null : c.getHomeroomTeacher().getId(),
                c.getHomeroomTeacher() == null ? null : c.getHomeroomTeacher().getName());
    }

    /** 반 소속 학생 한 줄. */
    public record Member(Long enrollmentId, String studentNo, String studentName) {

        public static Member from(ClassAssignment a) {
            return new Member(
                    a.getEnrollment().getId(),
                    a.getEnrollment().getStudentNo(),
                    a.getEnrollment().getStudentName());
        }
    }
}
