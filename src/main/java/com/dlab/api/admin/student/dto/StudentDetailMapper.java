package com.dlab.api.admin.student.dto;

import com.dlab.api.admin.student.dto.StudentDetailResponse.ClassInfo;
import com.dlab.api.admin.student.dto.StudentDetailResponse.GuardianInfo;
import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.service.StudentDetail;
import java.util.List;

/**
 * 상세 응답 조립. <b>마스킹이 여기서 걸린다</b> — 표현 계층의 책임이라
 * 도메인 서비스가 열람자를 알 필요가 없다.
 */
public final class StudentDetailMapper {

    private StudentDetailMapper() {
    }

    public static StudentDetailResponse toResponse(AuthPrincipal viewer, StudentDetail detail) {
        boolean raw = PersonalDataPolicy.canViewRaw(viewer);
        StudentEnrollment e = detail.enrollment();
        Student s = e.getStudent();

        return new StudentDetailResponse(
                e.getId(),
                s.getId(),
                s.getUniqueCode(),
                e.getStudentNo(),
                s.getName(),
                (int) e.getYear(),
                e.getGrade(),
                e.getTrack(),
                e.getEnrollmentStatus(),
                raw ? s.getPhone() : Masking.phone(s.getPhone()),
                raw ? isoOrNull(s) : Masking.birthDate(s.getBirthDate()),
                s.getGender(),
                s.getSchoolName(),
                e.getAdmissionDate(),
                e.getWithdrawalDate(),
                // 카드번호 자체는 상세에서도 내리지 않는다 — 출결 태깅 키다
                e.getRfidNo() != null,
                e.isCurrent(),
                e.getAcademy().getId(),
                e.getAcademy().getName(),
                toClassInfo(detail.classAssignment()),
                toGuardians(detail, raw),
                !raw);
    }

    private static String isoOrNull(Student s) {
        return s.getBirthDate() == null ? null : s.getBirthDate().toString();
    }

    private static ClassInfo toClassInfo(ClassAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        Teacher homeroom = assignment.getHomeroomTeacher();
        return new ClassInfo(
                assignment.getClassMaster().getId(),
                assignment.getClassMaster().getName(),
                homeroom == null ? null : homeroom.getId(),
                homeroom == null ? null : homeroom.getName());
    }

    private static List<GuardianInfo> toGuardians(StudentDetail detail, boolean raw) {
        return detail.guardianLinks().stream()
                .map(link -> {
                    ParentGuardian g = link.getGuardian();
                    return new GuardianInfo(
                            g.getId(),
                            g.getName(),
                            raw ? g.getPhone() : Masking.phone(g.getPhone()),
                            g.getGender(),
                            link.getRelationOrder());
                })
                .toList();
    }
}
