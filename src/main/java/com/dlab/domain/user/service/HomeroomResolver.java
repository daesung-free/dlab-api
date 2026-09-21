package com.dlab.domain.user.service;

import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 학생의 담임 = <b>예외 지정 ?? 반 담임</b>. 이 해석은 여기서만 한다.
 *
 * <h2>★ 어디에 쓰고 어디에 안 쓰나 — 이게 핵심이다</h2>
 * <table>
 *   <tr><th>사용처</th><th>기준</th></tr>
 *   <tr><td>승인 이양 대상(방화벽·사유)</td><td><b>학생</b> — 이걸 쓴다</td></tr>
 *   <tr><td>상담 담당자 판정·표시</td><td><b>학생</b> — 이걸 쓴다</td></tr>
 *   <tr><td>학생 목록의 담임 표시</td><td><b>학생</b> — 이걸 쓴다</td></tr>
 *   <tr><td>반공지·반설문 작성 권한</td><td><b>반</b> — 쓰지 않는다</td></tr>
 *   <tr><td>전년도 복사</td><td><b>반</b> — 쓰지 않는다(예외는 등록 건 속성이라 새 기수에 사라진다)</td></tr>
 * </table>
 * 반 권한까지 이걸로 판정하면 <b>예외 학생 하나 때문에 그 반 전체를 건드릴 수 있게</b> 된다.
 * 예외 지정은 「그 학생의 담임」이지 「그 반의 담임」이 아니다.
 */
@Component
@RequiredArgsConstructor
public class HomeroomResolver {

    private final ClassAssignmentRepository classAssignmentRepository;

    public Teacher of(StudentEnrollment enrollment) {
        if (enrollment.getHomeroomOverride() != null) {
            return enrollment.getHomeroomOverride();
        }
        return classAssignmentRepository.findActiveFixedByEnrollmentId(enrollment.getId())
                .map(ClassAssignment::getHomeroomTeacher)
                .orElse(null);
    }

    /** 반 배정을 이미 들고 있을 때 — 목록에서 학생마다 다시 조회하지 않게 */
    public static Teacher of(StudentEnrollment enrollment, ClassAssignment fixedAssignment) {
        if (enrollment.getHomeroomOverride() != null) {
            return enrollment.getHomeroomOverride();
        }
        return fixedAssignment == null ? null : fixedAssignment.getHomeroomTeacher();
    }
}
