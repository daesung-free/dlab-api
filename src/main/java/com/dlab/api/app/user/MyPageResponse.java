package com.dlab.api.app.user;

import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;

/**
 * 마이페이지.
 *
 * <p>학생과 학부모가 채우는 칸이 다르다. 하나의 형식으로 내리는 이유는 앱이 계정 종류에
 * 따라 화면을 갈라도 <b>같은 엔드포인트 하나</b>를 부르게 하기 위해서다.
 *
 * @param studentUniqueCode <b>학생 본인에게만</b> 채워진다. 학부모가 자녀를 연결하는
 *                          유일한 수단이고 노출 지점이 이 화면 하나다
 */
public record MyPageResponse(AccountType accountType,
                             String name,
                             String phone,
                             String studentUniqueCode,
                             Long enrollmentId,
                             String studentNo,
                             GradeType grade,
                             EnrollmentStatus enrollmentStatus,
                             String academyName,
                             String className,
                             Integer childCount) {

    static MyPageResponse ofStudent(Account account, StudentEnrollment enrollment,
                                    String className) {
        var student = account.getStudent();
        return new MyPageResponse(AccountType.STUDENT,
                student.getName(), student.getPhone(), student.getUniqueCode(),
                enrollment.getId(), enrollment.getStudentNo(), enrollment.getGrade(),
                enrollment.getEnrollmentStatus(),
                enrollment.getAcademy().getAcadNm(), className, null);
    }

    /**
     * 학부모.
     *
     * <p>자녀 <b>목록</b>은 담지 않는다 — {@code /api/v1/app/me/children}이 이미 내리고,
     * 두 곳에서 내리면 연결·해제 후 두 화면이 어긋난다. 여기서는 개수만 준다.
     */
    static MyPageResponse ofGuardian(Account account, int childCount) {
        var guardian = account.getGuardian();
        return new MyPageResponse(AccountType.PARENT,
                guardian.getName(), guardian.getPhone(), null,
                null, null, null, null, null, null, childCount);
    }
}
