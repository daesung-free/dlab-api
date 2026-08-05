package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.PasswordPolicy;
import com.dlab.common.verification.PhoneVerificationService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 학부모 가입 · 자녀 연결 (앱 요구사항 A-2).
 *
 * <p><b>학생 가입과 완전히 다른 흐름이다.</b> 학생은 승인제(관리자 승인 전 앱 전면 차단)지만
 * 학부모는 <b>비승인제</b> — 휴대폰 인증과 학생 고유ID만 맞으면 즉시 {@code ACTIVE}다.
 * 두 흐름을 하나로 합치려 들지 말 것.
 *
 * <p><b>연결 키가 둘로 나뉘는 이유</b>
 * <ul>
 *   <li><b>휴대폰 인증</b> — "이 번호의 주인이 맞다"(본인확인). 비밀번호 찾기 수단이자
 *       알림톡 수신처다. SMS 폴백이 폐기(E-5)돼 번호가 틀리면 대체 수단이 없다.</li>
 *   <li><b>학생 고유ID</b> — "이 학생의 보호자다"(연결 대상 지정)</li>
 * </ul>
 *
 * <p><b>⚠️ 관계 자체는 검증하지 않는다.</b> 고유ID를 아는 사람이면 누구나 연결할 수 있고,
 * 이건 클라이언트가 인지·감수한 부분이다(CLAUDE.md §3). <b>추가 검증 로직을 임의로 만들지 말 것.</b>
 * 휴대폰 인증이 막아주는 건 "남의 번호로 가입"까지다.
 *
 * <p>계정은 <b>자녀 수만큼 나누지 않는다</b> — 계정 1개에 자녀 여러 명을 연결하고
 * 앱이 자녀 전환 UI로 고른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParentSignupService {

    private final PhoneVerificationService phoneVerificationService;
    private final ParentGuardianRepository guardianRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianLinkRepository linkRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * 자녀 한 명 분의 표시 정보.
     *
     * <p>{@code enrollment}가 {@code null}일 수 있다 — 사람은 있는데 올해 등록 건이 없는
     * 경우(수료·퇴원 후)다. 앱은 이때도 자녀를 목록에 보여주되 조회 기능은 막아야 한다.
     */
    public record Child(Student student, StudentEnrollment enrollment) {
    }

    /**
     * 학부모 회원가입.
     *
     * @param verificationToken {@link PhoneVerificationService#confirm}이 내준 인증 토큰.
     *                          전화번호를 직접 받지 않는다 — 받으면 인증을 건너뛸 수 있다
     * @param studentUniqueCode 학생 마이페이지에 노출되는 고유ID
     */
    @Transactional
    public Account signup(String verificationToken, String name, String rawPassword,
                          String studentUniqueCode) {
        String phone = phoneVerificationService.consume(verificationToken);
        PasswordPolicy.validate(rawPassword);

        // 재가입이 아니라 중복가입이다 — 같은 번호로 두 계정이 생기면
        // 알림톡이 어느 계정 기준으로 갈지 모호해진다.
        if (guardianRepository.existsByPhoneAndDeletedFalse(phone)) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }

        Student child = findStudentByCode(studentUniqueCode);
        // 학생당 학부모 최대 1인(I-12 0803). DB 유니크 제약이 최후 방어선이지만,
        // 먼저 걸러야 "이미 연결됨"이라는 제대로 된 안내가 나간다.
        if (linkRepository.existsByStudentId(child.getId())) {
            throw new BusinessException(ErrorCode.GUARDIAN_ALREADY_LINKED);
        }

        ParentGuardian guardian = guardianRepository.save(new ParentGuardian(name, phone, null));
        linkRepository.save(new StudentGuardianLink(child, guardian, (short) 1));

        // 로그인 아이디는 전화번호다(account.login_id 규약). 학부모는 승인 없이 즉시 ACTIVE.
        Account account = accountRepository.save(
                Account.forGuardian(guardian, phone, passwordEncoder.encode(rawPassword)));
        account.markPasswordInitialized(Instant.now(clock));

        log.info("학부모 가입 완료: guardianId={}, 자녀 studentId={}", guardian.getId(), child.getId());
        return account;
    }

    /**
     * 자녀 추가 연결 (다자녀).
     *
     * <p>가입 때와 같은 검사를 한다 — 이미 다른 학부모가 붙은 학생은 연결할 수 없다.
     */
    @Transactional
    public Student linkChild(Long guardianId, String studentUniqueCode) {
        ParentGuardian guardian = guardianRepository.findById(guardianId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Student child = findStudentByCode(studentUniqueCode);

        if (linkRepository.existsByStudentIdAndGuardianId(child.getId(), guardianId)) {
            throw new BusinessException(ErrorCode.CHILD_ALREADY_LINKED);
        }
        if (linkRepository.existsByStudentId(child.getId())) {
            throw new BusinessException(ErrorCode.GUARDIAN_ALREADY_LINKED);
        }

        linkRepository.save(new StudentGuardianLink(child, guardian, (short) 1));
        return child;
    }

    /** 자녀 목록 — 앱의 자녀 전환 UI. */
    @Transactional(readOnly = true)
    public List<Child> children(Long guardianId) {
        return linkRepository.findChildrenOf(guardianId).stream()
                .map(link -> new Child(link.getStudent(),
                        enrollmentRepository.findCurrentByStudentId(link.getStudent().getId())
                                .orElse(null)))
                .toList();
    }

    /**
     * <b>이 학부모의 자녀가 맞는지 확인하고 등록 건을 돌려준다.</b>
     *
     * <p>학부모용 조회 API는 예외 없이 이걸 거쳐야 한다 — 자녀 ID를 요청 파라미터로 받는
     * 구조라, 검사하지 않으면 <b>남의 자녀 ID를 넣는 것만으로 그 학생의 출결·성적이 열린다.</b>
     * 지점 필터(SearchScope)와 같은 급의 규칙이다.
     */
    @Transactional(readOnly = true)
    public StudentEnrollment requireMyChild(Long guardianId, Long studentId) {
        if (!linkRepository.existsByStudentIdAndGuardianId(studentId, guardianId)) {
            throw new BusinessException(ErrorCode.NOT_MY_CHILD);
        }
        return enrollmentRepository.findCurrentByStudentId(studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
    }

    private Student findStudentByCode(String uniqueCode) {
        return Optional.ofNullable(uniqueCode)
                .flatMap(studentRepository::findByUniqueCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_CODE_NOT_FOUND));
    }
}
