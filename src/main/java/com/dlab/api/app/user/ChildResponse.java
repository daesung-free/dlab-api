package com.dlab.api.app.user;

import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.ParentSignupService;

/**
 * 자녀 전환 UI 한 줄.
 *
 * @param studentId  사람 기준 ID. 이후 조회 API에 이 값을 싣는다
 *                   (학번은 매년 초기화되므로 키로 쓰지 않는다)
 * @param active     올해 등록 건이 있는지. {@code false}면 수료·퇴원한 자녀라
 *                   목록에는 보이되 출결·성적 조회는 열지 않는다
 */
public record ChildResponse(Long studentId, String name, String studentNo, String academyName,
                            String grade, boolean active) {

    public static ChildResponse from(ParentSignupService.Child child) {
        StudentEnrollment enrollment = child.enrollment();
        if (enrollment == null) {
            return new ChildResponse(child.student().getId(), child.student().getName(),
                    null, null, null, false);
        }
        return new ChildResponse(
                child.student().getId(),
                child.student().getName(),
                enrollment.getStudentNo(),
                enrollment.getAcademy().getName(),
                enrollment.getGrade() == null ? null : enrollment.getGrade().name(),
                true);
    }
}
