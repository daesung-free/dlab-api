package com.dlab.domain.appconfig.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.appconfig.entity.TermAgreement;
import com.dlab.domain.appconfig.entity.Terms;
import com.dlab.domain.appconfig.repository.TermAgreementRepository;
import com.dlab.domain.appconfig.repository.TermsRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 약관 · 동의 이력.
 *
 * <p><b>★ 동의는 "동의함" 플래그가 아니라 이력이다.</b> 분쟁이 생기면
 * <i>"그때 어떤 문구에 동의했는가"</i>에 답해야 하므로, <b>동의 시점의 약관 버전</b>을
 * 가리키고 철회도 행으로 남긴다.
 *
 * <p>약관을 고칠 때는 <b>기존 행을 수정하지 말고 버전을 올려 새 행을 추가</b>한다 —
 * 덮어쓰면 이미 동의한 사람들의 근거가 통째로 사라진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TermsService {

    private final TermsRepository termsRepository;
    private final TermAgreementRepository agreementRepository;
    private final AccountRepository accountRepository;
    private final AcademyRepository academyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /**
     * 약관 한 건과 그 계정의 현재 동의 여부.
     *
     * @param agreed {@code null}이면 아직 응답한 적이 없다 — "동의 안 함"과 구분해야
     *               가입 흐름에서 다시 물어볼지 판단할 수 있다
     */
    public record TermsStatus(Terms terms, Boolean agreed, Instant agreedAt) {
    }

    /**
     * 시행 중인 약관 목록. 필수가 앞에 온다.
     *
     * @param academyId 지점 전용 약관까지 보려면 지정한다. {@code null}이면 공통본만
     */
    @Transactional(readOnly = true)
    public List<Terms> currentTerms(Long academyId) {
        return termsRepository.findCurrent(Instant.now(clock), academyId);
    }

    /**
     * 계정이 속한 지점.
     *
     * <p><b>학생은 등록 건에서 나온다.</b> 학부모는 자녀를 통해서만 지점을 알 수 있는데,
     * <b>자녀가 여럿이면 지점이 갈릴 수 있어</b> 하나로 정할 수 없다 — 그래서 학부모·직원은
     * {@code null}로 두고 공통 약관만 본다. 지점 약관(장학 동의 등)은 학생 대상이라
     * 실무상 문제가 없다.
     */
    private Long academyOf(Account account) {
        if (account.getStudent() == null) {
            return null;
        }
        return enrollmentRepository.findCurrentByStudentId(account.getStudent().getId())
                .map(e -> e.getAcademy().getId())
                .orElse(null);
    }

    /**
     * 시행 중인 약관 + 이 계정의 동의 상태.
     *
     * <p>약관이 개정되면 <b>새 버전에 대한 동의는 없는 상태</b>가 되어 다시 뜬다 —
     * 이력이 버전을 가리키기 때문에 자동으로 그렇게 된다.
     */
    @Transactional(readOnly = true)
    public List<TermsStatus> statusOf(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        Map<Long, TermAgreement> latest = new LinkedHashMap<>();
        for (TermAgreement agreement : agreementRepository.findLatestByAccount(accountId)) {
            latest.put(agreement.getTerms().getId(), agreement);
        }
        return currentTerms(academyOf(account)).stream()
                .map(terms -> {
                    TermAgreement agreement = latest.get(terms.getId());
                    return new TermsStatus(terms,
                            agreement == null ? null : agreement.isAgreed(),
                            agreement == null ? null : agreement.getAgreedAt());
                })
                .toList();
    }

    /**
     * 동의·철회 기록.
     *
     * <p><b>필수 약관은 철회할 수 없다</b> — 철회를 허용하면 서비스를 계속 쓰면서
     * 이용약관에 동의하지 않은 상태가 되어 모순이 생긴다. 그건 탈퇴로 처리할 일이다.
     *
     * <p>같은 값으로 다시 눌러도 <b>행을 새로 남긴다</b>. 중복처럼 보이지만
     * "언제 다시 확인했는지"가 기록이고, 눌렀는데 아무것도 안 남는 쪽이 더 위험하다.
     */
    @Transactional
    public TermAgreement record(Long accountId, Long termsId, boolean agreed) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Terms terms = termsRepository.findById(termsId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.TERMS_NOT_FOUND));

        if (terms.isRequired() && !agreed) {
            throw new BusinessException(ErrorCode.REQUIRED_TERMS_CANNOT_BE_REVOKED);
        }
        return agreementRepository.save(
                new TermAgreement(account, terms, agreed, Instant.now(clock)));
    }

    /**
     * 필수 약관에 전부 동의했는가. 가입 완료 조건으로 쓴다.
     */
    @Transactional(readOnly = true)
    public boolean hasAgreedAllRequired(Long accountId) {
        return statusOf(accountId).stream()
                .filter(status -> status.terms().isRequired())
                .allMatch(status -> Boolean.TRUE.equals(status.agreed()));
    }

    /**
     * 이 코드의 <b>현재 시행본</b>에 동의했는가.
     *
     * <p>급식 개인정보 제3자 제공 동의서가 <i>"급식 신청 시에만 급식업체에 정보 제공"</i>이라
     * 신청 시점에 확인이 필요하다. 가입 때 한 번 받고 끝나는 값이 아니다.
     *
     * <p><b>"응답 없음"과 "미동의"를 여기서는 구분하지 않는다</b> — 둘 다 "동의하지 않았다"라
     * 제공하면 안 되는 것은 같다. 구분이 필요한 쪽(동의 화면을 다시 띄울지)은
     * {@link #statusOf}가 답한다.
     *
     * <p>약관이 개정되면 새 버전에 대한 동의가 없는 상태가 되어 <b>다시 받아야 한다</b> —
     * 급식업체가 바뀌어 문구가 바뀌면 예전 업체에 대한 동의로 새 업체에 정보를 주는 일이
     * 생기지 않는다.
     */
    @Transactional(readOnly = true)
    public boolean hasAgreed(Long accountId, String code) {
        return statusOf(accountId).stream()
                .filter(status -> status.terms().getCode().equals(code))
                .anyMatch(status -> Boolean.TRUE.equals(status.agreed()));
    }

    /**
     * 이 동의가 없어서 <b>행위를 막아야 하는가</b>.
     *
     * <p><b>★ 약관 행이 아예 없으면 막지 않는다.</b> {@link #hasAgreed}만으로 판정하면
     * 약관을 아직 등록하지 않은 상태에서 <b>전원이 막힌다</b> — 문구가 미확정인데 급식이
     * 통째로 멈추는 것이라, 받을 동의가 존재하지도 않는데 거부하는 셈이 된다.
     * 상벌점 규칙이 없으면 아무 일도 일어나지 않는 것과 같은 판단이다.
     *
     * <p>반대로 <b>약관을 등록하는 순간부터는 실제로 막힌다</b> — 등록이 곧 시행이다.
     */
    @Transactional(readOnly = true)
    public boolean needsAgreement(Long accountId, String code) {
        List<TermsStatus> matched = statusOf(accountId).stream()
                .filter(status -> status.terms().getCode().equals(code))
                .toList();

        return !matched.isEmpty()
                && matched.stream().noneMatch(status -> Boolean.TRUE.equals(status.agreed()));
    }

    /** 전체 이력 — 감사·분쟁 대응용. */
    @Transactional(readOnly = true)
    public List<TermAgreement> history(Long accountId) {
        return agreementRepository.findByAccountIdOrderByAgreedAtDesc(accountId);
    }

    /**
     * 약관 등록.
     *
     * <p>같은 {@code code}+{@code version}은 막는다 — 덮어쓰려는 시도이기 때문이다.
     * 문구를 고치려면 버전을 올려야 한다.
     */
    @Transactional
    public Terms create(String code, String version, String title, String content,
                        boolean required, Instant effectiveAt) {
        return create(null, code, version, title, content, required, effectiveAt);
    }

    /**
     * @param academyId 지점 전용이면 지정한다. {@code null}이면 전 지점 공통
     */
    @Transactional
    public Terms create(Long academyId, String code, String version, String title, String content,
                        boolean required, Instant effectiveAt) {
        if (termsRepository.existsSameVersion(code, version, academyId)) {
            throw new BusinessException(ErrorCode.TERMS_VERSION_DUPLICATED);
        }
        Academy academy = academyId == null ? null
                : academyRepository.findById(academyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        Terms terms = termsRepository.save(new Terms(academy, code, version, title, content,
                required, effectiveAt == null ? Instant.now(clock) : effectiveAt));
        log.info("약관 등록: code={}, version={}, academyId={}", code, version, academyId);
        return terms;
    }
}
