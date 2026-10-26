package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import com.dlab.domain.user.repository.ParentGuardianRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentGuardianLinkRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자 연락처 관리 (관리자 웹).
 *
 * <h2>★★ 이 경로가 없으면 학부모 알림톡이 한 통도 못 나간다</h2>
 * 수신처는 {@code parent_guardian.phone} 이고, 그 번호는 지금까지 <b>학부모가 앱에 가입할
 * 때만</b> 들어왔다. 가입하지 않은 학부모에게는 보낼 번호가 없다 —
 * ⚠️ <b>미등원 알림은 끌 수 없는 필수 알림인데 수신처가 없는 상태</b>가 된다.
 * 컷오버 때 기존 재원생의 보호자 번호를 넣을 경로도 이것이다.
 *
 * <h2>★★ 번호가 같으면 기존 보호자를 재사용한다</h2>
 * {@code parent_guardian.phone} 에 UNIQUE 가 걸려 있다. 형제자매가 같은 보호자를
 * 공유하므로 <b>새로 만들면 제약 위반으로 터진다</b> — 같은 번호면 그 행에 연결만 추가한다.
 * 그래서 다자녀 구조(계정 1개 + 자녀 여러 명)와도 어긋나지 않는다.
 *
 * <h2>⚠️ {@code is_approver} 는 승인 판정에 쓰이지 않는다</h2>
 * 0803 「학부모 최대 1인」을 위해 만든 컬럼인데 2026-10-12 회신으로 <b>양쪽 알림 + 선착순</b>
 * 으로 뒤집혔다. 지금 승인 경로는 이 값을 읽지 않는다 — <b>여기서 지정해도 승인 동작은
 * 바뀌지 않는다.</b> 남겨 두는 이유는 DSA {@code getParentHpList} 의 목록 순서다.
 *
 * <h2>★ 지우는 것은 연결만이다</h2>
 * 보호자 행은 지우지 않는다 — 다른 자녀가 그 보호자를 쓰고 있을 수 있고, 지난 알림 이력이
 * 그 번호를 가리킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GuardianContactService {

    private final StudentEnrollmentRepository enrollmentRepository;
    private final ParentGuardianRepository guardianRepository;
    private final StudentGuardianLinkRepository linkRepository;

    public List<StudentGuardianLink> list(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return linkRepository.findByStudentId(enrollment.getStudent().getId());
    }

    /**
     * 보호자 연락처 추가.
     *
     * <p>★ 번호가 이미 있으면 <b>그 보호자에 연결만 추가</b>한다(형제자매 공유).
     * 이름이 다르면 기존 이름을 유지한다 — 앱에 가입한 본인이 적은 이름이 더 정확하고,
     * 관리자 입력으로 덮으면 <b>다른 자녀 화면의 이름까지 바뀐다.</b>
     *
     * <p>⚠️ 마스킹된 값을 받으면 거부한다. 「내려받아 수정 후 재업로드」 흐름에서
     * {@code 010-****-1234} 가 그대로 저장되면 <b>알림톡이 아무에게도 가지 않는데
     * 화면에는 원래도 마스킹돼 보여 한동안 아무도 눈치채지 못한다</b>(§7).
     */
    @Transactional
    public StudentGuardianLink add(AuthPrincipal me, Long enrollmentId,
                                   String name, String phone, short relationOrder,
                                   boolean approver) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        Student student = enrollment.getStudent();

        if (Masking.isMasked(phone) || Masking.isMasked(name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "마스킹된 값은 저장할 수 없습니다. 실제 값을 입력해 주세요.");
        }

        ParentGuardian guardian = guardianRepository.findByPhoneAndDeletedFalse(phone)
                // 성별은 받지 않는다 — 알림 발송에 쓰이지 않고, 관리자가 추측해 넣을 값이 아니다
                .orElseGet(() -> guardianRepository.save(new ParentGuardian(name, phone, null)));

        if (linkRepository.existsByStudentIdAndGuardianId(student.getId(), guardian.getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 연결된 보호자입니다.");
        }

        StudentGuardianLink link = linkRepository.save(
                new StudentGuardianLink(student, guardian, relationOrder, approver));
        log.info("보호자 연락처 추가: enrollmentId={}, guardianId={}",
                enrollmentId, guardian.getId());
        return link;
    }

    /**
     * 연결 해제.
     *
     * <p>★ <b>보호자 행은 지우지 않는다</b> — 다른 자녀가 쓰고 있을 수 있고, 지난 알림
     * 이력이 그 번호를 가리킨다.
     */
    @Transactional
    public void unlink(AuthPrincipal me, Long enrollmentId, Long guardianId) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        StudentGuardianLink link = linkRepository
                .findByStudentId(enrollment.getStudent().getId()).stream()
                .filter(l -> l.getGuardian().getId().equals(guardianId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        linkRepository.delete(link);
        log.info("보호자 연결 해제: enrollmentId={}, guardianId={}", enrollmentId, guardianId);
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        // ★ 다른 지점 학생의 보호자를 보거나 고칠 수 없다
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return enrollment;
    }
}
