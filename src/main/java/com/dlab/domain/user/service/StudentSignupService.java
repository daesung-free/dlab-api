package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.PasswordPolicy;
import com.dlab.common.verification.PhoneVerificationService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 학생 자가가입 (앱 A-2 · F-4.12-1).
 *
 * <h2>학부모 가입과 완전히 다르다</h2>
 * 학부모는 비승인제라 가입 즉시 {@code ACTIVE}지만, 학생은 <b>승인제</b>다 —
 * {@code PENDING}으로 만들어지고 <b>승인 전에는 로그인 자체가 거부된다</b>
 * ({@code AuthService}의 {@code SIGNUP_PENDING}). 중간 상태 없는 이분법이다(CLAUDE.md §3).
 * 두 흐름을 하나로 합치려 들지 말 것.
 *
 * <h2>★ 가입 경로는 이것 하나뿐이다</h2>
 * 0805 시트가 <i>"관리자는 계정을 직접 생성하지 않고 승인·초기화·잠금 해제를 담당"</i>으로
 * 못박았다. <b>관리자 계정 발급 API를 만들면 승인 절차를 우회하는 두 번째 가입 경로가
 * 생긴다.</b> 관리자의 {@code StudentService.admit}은 <b>등록 건만</b> 만들고 앱 계정은
 * 만들지 않으므로 이 원칙과 충돌하지 않는다.
 *
 * <h2>중복 가입 차단</h2>
 * 휴대폰번호로 대조하되 <b>승인 대기 중인 기존 요청까지</b> 본다. 대기 중인 신청을
 * "아직 계정이 아니다"로 흘려보내면, 승인이 늦어질 때 학생이 몇 번이고 다시 신청해
 * 관리자 대기 목록에 같은 사람이 여러 줄로 쌓인다.
 *
 * <h2>학번은 여기서 채번된다</h2>
 * {@code UNIQUE(academy_id, year, student_no)}가 심판이라 <b>동시 가입이 몰리면
 * 진 쪽이 떨어진다.</b> 모집 시즌에 실제로 몰리는 흐름이므로 {@link StudentService}와
 * 같은 방식으로 <b>새 트랜잭션을 열어 재시도</b>한다 — 같은 트랜잭션 안에서 다시 시도하면
 * 제약 위반 순간 rollback-only로 찍혀 무조건 실패한다.
 *
 * <h2>★ "내 가입 상태" 조회 API를 만들지 말 것</h2>
 * 승인 전에는 로그인이 막히니 무인증으로 열어야 하는데, 그러면 <b>전화번호만 넣어보면
 * 그 학생의 고유ID가 나온다.</b> 고유ID는 학부모 연결의 유일한 키이고 관계 자체는
 * 검증하지 않으므로(CLAUDE.md §3), 아무나 남의 자녀로 연결할 수 있게 된다.
 * 승인 여부는 로그인 응답의 {@code SIGNUP_PENDING}으로 이미 알 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentSignupService {

    private static final int STUDENT_NO_RETRY = 5;

    private final PhoneVerificationService phoneVerificationService;
    private final StudentService studentService;
    private final AcademyRepository academyRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final StudentSignupApprovalService approvalService;
    private final com.dlab.domain.user.repository.ParentGuardianRepository guardianRepository;
    private final com.dlab.domain.user.repository.StudentGuardianLinkRepository linkRepository;

    /**
     * 가입 신청.
     *
     * @param verificationToken 휴대폰 인증 토큰. 번호를 직접 받지 않는다 —
     *                          받으면 인증을 건너뛸 수 있다
     * @return 승인 대기 상태({@code PENDING})의 계정
     */
    public Account signup(Command command) {
        String phone = phoneVerificationService.consume(command.verificationToken()).phone();
        PasswordPolicy.validate(command.password());

        // ★★ 반려된 가입이면 재신청으로 받는다 — 새 계정을 만들지 않는다.
        //    중복 검사가 WITHDRAWN 만 빼므로 반려도 걸려서, 고치기 전에는 반려된 학생이
        //    「이미 등록된 번호」로 막혀 **재신청을 아예 할 수 없었다.**
        //    새 계정을 만드는 쪽도 안 된다 — login_id 가 UNIQUE 라 제약 위반으로 터지고,
        //    학번 채번 재시도가 그 예외까지 5번 다시 돌린다.
        Account rejected = accountRepository.findByLoginId(phone)
                .filter(a -> a.getStatus() == AccountStatus.REJECTED)
                .orElse(null);
        if (rejected != null) {
            return reapply(rejected, command);
        }

        // ★ 트랜잭션 밖에서 먼저 본다. 안에서 보면 "이미 신청함"과 학번 채번 충돌이
        //    같은 예외로 뭉뚱그려져, 재시도하면 안 되는 건까지 5번 다시 시도한다.
        if (accountRepository.existsActiveOrPendingByLoginId(phone)) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }

        Account account = withStudentNoRetry(() -> create(command, phone));
        log.info("학생 가입 신청: accountId={}, academyId={}", account.getId(), command.academyId());
        return account;
    }

    /**
     * 재신청.
     *
     * <p>★ <b>학번·등록 건을 다시 만들지 않는다.</b> 반려는 등록이 성립하지 않은 상태가
     * 아니라 <b>계정 승인이 거절된 상태</b>라, 등록 행은 이미 있다. 새로 채번하면
     * 같은 사람에게 학번이 둘 생긴다.
     *
     * <p>비밀번호는 <b>다시 받은 값으로 바꾼다</b> — 반려되고 한참 뒤에 다시 오면
     * 기억하지 못하는 쪽이 보통이고, 휴대폰 인증을 통과한 본인이다.
     */
    private Account reapply(Account account, Command command) {
        account.changePassword(passwordEncoder.encode(command.password()), clock.instant());
        approvalService.reapply(account.getId());
        log.info("학생 가입 재신청: accountId={}", account.getId());
        return account;
    }

    private Account create(Command command, String phone) {
        Academy academy = academyRepository.findById(command.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        short year = (short) LocalDate.now(clock).getYear();
        // 사람·등록 건 생성은 관리자 접수와 같은 코드를 쓴다. 따로 짜면 학번 채번 규칙이
        // 두 벌이 되어 한쪽만 고쳐질 수 있다
        StudentEnrollment enrollment = studentService.admitParsed(
                academy, year, command.name(), phone,
                command.birthDate(), command.gender(), command.schoolName(), command.address(),
                command.grade(), command.track());

        // 로그인 아이디는 전화번호다(account.login_id 규약). 학생은 PENDING으로 시작한다
        Account account = accountRepository.save(Account.forStudent(
                enrollment.getStudent(), phone, passwordEncoder.encode(command.password())));
        // 본인이 정한 비밀번호라 최초 로그인 시 변경 강제 대상이 아니다
        account.markPasswordInitialized(Instant.now(clock));

        linkGuardian(enrollment, command.guardianPhone());

        accountRepository.flush();
        return account;
    }

    /**
     * 보호자 번호를 받았으면 연결해 둔다.
     *
     * <h2>★ 번호가 같으면 기존 보호자를 재사용한다</h2>
     * 형제자매가 같은 보호자를 공유하고 {@code parent_guardian.phone} 에 UNIQUE 가
     * 걸려 있어 <b>새로 만들면 제약 위반으로 터진다</b> — 그러면 가입 자체가 실패하고,
     * 채번 재시도가 그 예외까지 다시 돌린다.
     *
     * <h2>★ 이름을 「학부모」로 둔다</h2>
     * 학생이 적는 것은 번호뿐이라 이름을 알 수 없다. 학부모가 나중에 앱에 가입하면
     * <b>본인이 적은 이름이 그 행에 들어간다</b>(번호가 같으면 같은 행이다).
     *
     * <h2>⚠️ 이것만으로는 알림톡이 가지 않는다</h2>
     * 발송 경로가 <b>학부모 앱 계정</b>을 찾는다. 「앱을 설치하지 않은 학부모에게
     * 보낼 것인가」가 미확정이라(디랩 질의) 번호 기반 발송 경로는 만들지 않았다.
     * 그래도 받아 두는 것이 맞다 — 나중에 받으려면 <b>학생 전원에게 다시 물어야 한다.</b>
     */
    private void linkGuardian(StudentEnrollment enrollment, String guardianPhone) {
        if (guardianPhone == null || guardianPhone.isBlank()) {
            return;
        }
        ParentGuardian guardian = guardianRepository
                .findByPhoneAndDeletedFalse(guardianPhone)
                .orElseGet(() -> guardianRepository.save(
                        new ParentGuardian("학부모", guardianPhone, null)));

        if (!linkRepository.existsByStudentIdAndGuardianId(
                enrollment.getStudent().getId(), guardian.getId())) {
            linkRepository.save(new StudentGuardianLink(
                    enrollment.getStudent(), guardian, (short) 1));
        }
    }

    /** {@link StudentService#admit}과 같은 이유로 <b>시도마다 새 트랜잭션</b>을 연다. */
    private Account withStudentNoRetry(java.util.function.Supplier<Account> attempt) {
        for (int i = 0; i < STUDENT_NO_RETRY; i++) {
            try {
                return transactionTemplate.execute(status -> attempt.get());
            } catch (DataIntegrityViolationException e) {
                log.info("학생 가입 채번 충돌, 재시도 {}/{}", i + 1, STUDENT_NO_RETRY);
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "가입 처리에 실패했습니다. 다시 시도해주세요.");
    }

    /**
     * 가입 입력값.
     *
     * <p>성적은 여기 없다. 가입과 <b>같은 트랜잭션에 묶지 않는다</b> — 성적 양식은
     * 학년에 따라 시험이 3회차·과목이 6개까지 되는 긴 입력이라, 한 번에 받으면 중간에
     * 실패했을 때 휴대폰 인증부터 다시 해야 한다. 가입을 먼저 끝내고
     * {@code /api/v1/app/grades}로 이어서 낸다.
     */
    public record Command(String verificationToken, String name, String password,
                          Long academyId, GradeType grade, TrackType track,
                          LocalDate birthDate, String gender, String schoolName, String address,
                          /**
                           * 보호자 전화번호. ★ 신입생 동의서의 필수 수집 항목이다.
                           *
                           * <p>⚠️ 비어 있어도 가입은 된다 — 이미 가입한 학생이 있고,
                           * 필수로 바꾸면 그 학생들의 재신청이 막힌다.
                           */
                          String guardianPhone) {
    }
}
