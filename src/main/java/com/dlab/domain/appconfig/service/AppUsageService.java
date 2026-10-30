package com.dlab.domain.appconfig.service;

import com.dlab.domain.appconfig.entity.Terms;
import com.dlab.domain.appconfig.repository.TermAgreementRepository;
import com.dlab.domain.appconfig.repository.TermsRepository;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountStatus;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 가입·동의 현황 — 앱 운영 화면 상단 카드.
 *
 * <h2>기준은 재원생이다</h2>
 * 계정 전체를 세면 퇴원생·지난 기수 계정이 분모에 섞여 가입률이 실제보다 낮게 나온다.
 * 그래서 <b>지금 재원 중인 학생</b>에서 출발해 그 학생의 계정과 연결된 학부모 계정을 센다.
 *
 * <h2>동의율의 분모는 활성 앱 계정이다</h2>
 * 약관은 앱 사용자(학생·학부모)가 동의하는 것이라, 승인 대기(PENDING)는 아직 앱을 못 쓰므로
 * 뺀다. 동의는 이력이라 계정·약관마다 <b>마지막</b> 행으로 판정한다 — 철회했으면 미동의다.
 */
@Service
@RequiredArgsConstructor
public class AppUsageService {

    private final AcademyRepository academyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AccountRepository accountRepository;
    private final TermsRepository termsRepository;
    private final TermAgreementRepository agreementRepository;
    private final Clock clock;

    /** @param academyId {@code null}이면 전 지점. 약관은 그때 공통 약관만 본다 */
    @Transactional(readOnly = true)
    public Usage usage(Long academyId) {
        List<Long> academies = academyId != null ? List.of(academyId)
                : academyRepository.findAllActive().stream()
                        .map(com.dlab.domain.user.entity.Academy::getId).toList();

        Set<Long> studentIds = academies.stream()
                .flatMap(id -> enrollmentRepository.findCurrentByAcademyId(id).stream())
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .map(StudentEnrollment::getStudent)
                .map(com.dlab.domain.user.entity.Student::getId)
                .collect(Collectors.toSet());
        if (studentIds.isEmpty()) {
            return new Usage(0, 0, 0, null, 0, 0, null, List.of());
        }

        List<Account> studentAccounts = accountRepository.findStudentAccountsOf(studentIds);
        long activeStudents = countStatus(studentAccounts, AccountStatus.ACTIVE);
        long pendingStudents = countStatus(studentAccounts, AccountStatus.PENDING);

        Map<Long, Account> parents = new java.util.HashMap<>();
        Set<Long> studentsWithParent = new HashSet<>();
        for (Object[] row : accountRepository.findParentAccountsOf(studentIds)) {
            Account parent = (Account) row[0];
            if (parent.getStatus() != AccountStatus.ACTIVE) {
                continue;
            }
            parents.put(parent.getId(), parent);
            studentsWithParent.add((Long) row[1]);
        }

        Set<Long> appAccounts = new HashSet<>(parents.keySet());
        studentAccounts.stream().filter(a -> a.getStatus() == AccountStatus.ACTIVE)
                .forEach(a -> appAccounts.add(a.getId()));

        return new Usage(studentIds.size(), activeStudents, pendingStudents,
                rate(activeStudents, studentIds.size()),
                parents.size(), studentsWithParent.size(),
                rate(studentsWithParent.size(), studentIds.size()),
                termsRates(academyId, appAccounts));
    }

    private List<TermsRate> termsRates(Long academyId, Set<Long> appAccounts) {
        List<Terms> current = termsRepository.findCurrent(Instant.now(clock), academyId);
        if (current.isEmpty() || appAccounts.isEmpty()) {
            return current.stream()
                    .map(t -> TermsRate.of(t, appAccounts.size(), 0, 0))
                    .toList();
        }
        List<Object[]> rows = agreementRepository.findLatestOf(appAccounts,
                current.stream().map(Terms::getId).toList()).stream()
                .filter(r -> (Boolean) r[2])
                .toList();

        Map<Long, Long> agreed = rows.stream()
                .collect(Collectors.groupingBy(r -> (Long) r[1], Collectors.counting()));

        // ★★ 유효기간이 있는 약관은 ★ 동의했는지만으로 세면 안 된다 — 마케팅은 동의일부터
        //    2년이고, 그것 없이 세면 ★ 2년 지난 동의자가 「동의자」로 잡힌다
        //    (2026-10-30 프런트가 발견).
        Map<Long, Long> effective = rows.stream()
                .filter(r -> !expired(termsById(current, (Long) r[1]), (Instant) r[3]))
                .collect(Collectors.groupingBy(r -> (Long) r[1], Collectors.counting()));

        return current.stream()
                .map(t -> TermsRate.of(t, appAccounts.size(),
                        agreed.getOrDefault(t.getId(), 0L),
                        effective.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    private static long countStatus(List<Account> accounts, AccountStatus status) {
        return accounts.stream().filter(a -> a.getStatus() == status).count();
    }

    /** 백분율(정수). 분모가 0이면 {@code null} — 0%로 두면 "아무도 안 했다"로 읽힌다. */
    static Integer rate(long part, long whole) {
        return whole == 0 ? null : (int) Math.round(part * 100.0 / whole);
    }

    /**
     * @param enrolledStudents     재원생 수 — 가입률의 분모
     * @param studentAccounts      앱 가입(승인 완료)한 재원생
     * @param pendingStudents      가입했지만 승인 대기
     * @param parentAccounts       연결된 학부모 계정(활성). 형제에 함께 연결돼도 한 번
     * @param studentsWithParent   학부모가 한 명 이상 연결된 재원생
     */
    public record Usage(int enrolledStudents, long studentAccounts, long pendingStudents,
                        Integer studentSignupRate, int parentAccounts, int studentsWithParent,
                        Integer parentLinkRate, List<TermsRate> terms) {
    }

    /**
     * 현재 시행 중인 약관 하나의 동의율.
     *
     * @param targetAccounts    분모 — 활성 앱 계정(학생 + 학부모)
     * @param agreedAccounts    마지막 행이 동의인 계정. 이전 버전에만 동의했으면 세지 않는다
     * @param effectiveAccounts ★ 그중 <b>유효기간 안</b>인 계정. 마케팅은 동의일부터 2년이다.
     *                          <p>★ 기간이 없는 약관은 {@code agreedAccounts} 와 같다.
     *                          <p>⚠️ <b>{@code agreedAccounts} 를 유효 기준으로 바꾸지 않은
     *                          이유</b>: 숫자가 조용히 줄면 담당자는 <b>「동의자가 사라졌다」</b>
     *                          로 읽는다. 두 값을 나란히 두고 화면이
     *                          <b>「동의 80% (유효 55%)」</b>처럼 쓰는 쪽이 맞다.
     * @param rate              동의율 — {@code agreedAccounts} 기준
     * @param effectiveRate     ★ 유효율 — {@code effectiveAccounts} 기준
     */
    public record TermsRate(Long termsId, String code, String version, String title,
                            boolean required, int targetAccounts, long agreedAccounts,
                            long effectiveAccounts, Integer rate, Integer effectiveRate) {

        static TermsRate of(Terms t, int target, long agreed, long effective) {
            return new TermsRate(t.getId(), t.getCode(), t.getVersion(), t.getTitle(),
                    t.isRequired(), target, agreed, effective,
                    AppUsageService.rate(agreed, target),
                    AppUsageService.rate(effective, target));
        }
    }

    /** ★ 유효기간이 있는 약관 — {@code TermsService} 와 <b>같은 값</b>을 쓴다. */
    private static final String MARKETING_CODE = "MARKETING";
    private static final java.time.Period MARKETING_VALID_FOR = java.time.Period.ofYears(2);

    private static Terms termsById(List<Terms> terms, Long id) {
        return terms.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
    }

    /**
     * ★ 동의했지만 유효기간이 지났는가.
     *
     * <p>⚠️ 판정이 {@code TermsService.isExpired} 와 <b>두 벌</b>이다. 하나로 모으려면
     * 그쪽을 공개하거나 공통으로 빼야 하는데, <b>지금은 둘 다 「마케팅 2년」 한 줄</b>이라
     * 그 비용이 더 크다. ★ 기간이 있는 약관이 하나 더 생기면 <b>그때 합친다</b> —
     * 그 전에 갈리면 동의율과 발송 대상이 어긋난다.
     */
    private boolean expired(Terms terms, Instant agreedAt) {
        if (terms == null || agreedAt == null || !MARKETING_CODE.equals(terms.getCode())) {
            return false;
        }
        java.time.LocalDate agreedOn = agreedAt
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return java.time.LocalDate.now(clock).isAfter(agreedOn.plus(MARKETING_VALID_FOR));
    }
}
