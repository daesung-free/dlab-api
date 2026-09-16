package com.dlab.domain.readiness;

import com.dlab.common.verification.LoggingSmsSender;
import com.dlab.common.verification.SmsSender;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.repository.ApprovalItemRepository;
import com.dlab.domain.grade.repository.ExamMasterRepository;
import com.dlab.domain.meal.repository.MealPolicyRepository;
import com.dlab.domain.menu.repository.MenuRepository;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.repository.PgSiteRepository;
import com.dlab.domain.payment.repository.TuitionMonthRepository;
import com.dlab.domain.master.repository.TuitionRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 준비 상태 점검.
 *
 * <h2>왜 만드는가</h2>
 * 이 시스템에는 <b>설정이 빠졌는데 화면은 멀쩡한</b> 실패가 반복해서 난다. 승인 정책이 없으면
 * 신청이 전부 거절되고, 사이트코드가 없으면 청구까지는 되는데 결제 링크에서만 실패하고,
 * 급식 단가가 없으면 금액이 빈 주문이 쌓인다. 전부 <b>사람이 체크리스트를 읽어야만</b>
 * 걸러지던 것들이다.
 *
 * <h2>★ 연도가 바뀌면 다시 터진다</h2>
 * 교습일수·가격·성적 양식이 연도별 데이터라 <b>해가 바뀌는 순간 그 해 청구와 가입이 막힌다</b>
 * ({@code TEACHING_DAYS_NOT_REGISTERED} · {@code EXAM_FORM_NOT_FOUND}). 그래서 점검 대상
 * 연도를 받는다 — 12월에 내년을 미리 확인할 수 있어야 한다.
 *
 * <h2>판정은 두 단계다</h2>
 * <ul>
 *   <li>{@code BLOCKER} — 그 기능이 <b>아예 안 된다</b></li>
 *   <li>{@code WARNING} — 돌기는 도는데 <b>조용히 틀린 값</b>으로 돈다</li>
 * </ul>
 * 둘을 합치면 "빨간 게 많네" 하고 전부 무시하게 된다.
 */
@Service
@RequiredArgsConstructor
public class ReadinessService {

    /** 지점 기본 등원시각. 이 값 그대로면 실제 운영 시각을 안 넣은 것이다 */
    private static final LocalTime DEFAULT_DEADLINE = LocalTime.of(9, 0);
    /** 한 해는 12달이다. 하나라도 비면 그 달 청구가 막힌다 */
    private static final int MONTHS = 12;

    private final AcademyRepository academyRepository;
    private final ApprovalItemRepository approvalItemRepository;
    private final PgSiteRepository pgSiteRepository;
    private final MealPolicyRepository mealPolicyRepository;
    private final TuitionMonthRepository tuitionMonthRepository;
    private final TuitionRepository tuitionRepository;
    private final ExamMasterRepository examMasterRepository;
    private final NotificationTemplateRepository templateRepository;
    private final MenuRepository menuRepository;
    private final SmsSender smsSender;

    @Transactional(readOnly = true)
    public Report check(short year) {
        List<Check> common = commonChecks(year);
        List<AcademyReport> academies = academyRepository.findAllActive().stream()
                .map(academy -> new AcademyReport(academy.getId(), academy.getName(),
                        academyChecks(academy, year)))
                .toList();

        long blockers = common.stream().filter(Check::blocking).count()
                + academies.stream().flatMap(a -> a.checks().stream())
                .filter(Check::blocking).count();
        long warnings = common.stream().filter(Check::warning).count()
                + academies.stream().flatMap(a -> a.checks().stream())
                .filter(Check::warning).count();

        return new Report(year, (int) blockers, (int) warnings, common, academies);
    }

    /** 지점과 무관한 것. 하나가 비면 전 지점이 같이 막힌다. */
    private List<Check> commonChecks(short year) {
        List<Check> checks = new ArrayList<>();

        int months = tuitionMonthRepository.findAllByScope(year, null).size();
        checks.add(Check.of("TEACHING_DAYS", "월별 교습일수", Severity.BLOCKER,
                months >= MONTHS,
                "%d월분 등록됨. 없는 달은 그 달 청구를 만들 수 없다".formatted(months)));

        int forms = examMasterRepository.findAllByScope(year, null).size();
        checks.add(Check.of("EXAM_FORM", "성적 입력 양식", Severity.BLOCKER,
                forms > 0,
                forms > 0 ? "%d개 회차 등록됨".formatted(forms)
                        : "없으면 그 해 가입자가 전부 EXAM_FORM_NOT_FOUND 를 받는다"));

        boolean templates = !templateRepository.findAllByDeletedFalseOrderByEventCode().isEmpty();
        checks.add(Check.of("NOTIFICATION_TEMPLATE", "알림 템플릿", Severity.WARNING,
                templates,
                "없으면 발송이 조용히 SKIPPED 로 남는다"));

        // ★ 목업이면 API 는 200 을 주는데 아무에게도 문자가 가지 않는다
        boolean realSender = !(smsSender instanceof LoggingSmsSender);
        checks.add(Check.of("SMS_SENDER", "문자 발송 수단", Severity.WARNING,
                realSender,
                realSender ? "발송 구현체 연결됨"
                        : "목업이다 — 인증번호가 로그로만 찍히는데 API 는 정상 응답한다"));

        boolean menus = !menuRepository.findAllOrdered().isEmpty();
        checks.add(Check.of("MENU_CATALOG", "메뉴 카탈로그", Severity.WARNING, menus,
                "비면 메뉴 노출 설정 화면이 빈 목록으로 뜬다"));

        return checks;
    }

    private List<Check> academyChecks(Academy academy, short year) {
        Long id = academy.getId();
        List<Check> checks = new ArrayList<>();

        List<RequestType> missing = java.util.Arrays.stream(RequestType.values())
                .filter(type -> approvalItemRepository
                        .findByAcademyIdAndYearAndRequestTypeAndDeletedFalse(id, year, type)
                        .isEmpty())
                .toList();
        checks.add(Check.of("APPROVAL_ITEM", "승인 정책", Severity.BLOCKER, missing.isEmpty(),
                missing.isEmpty() ? "3종 모두 등록됨"
                        : "미설정: %s — 그 유형의 신청이 전부 거절된다".formatted(missing)));

        checks.add(pgCheck(id, PgPurpose.TUITION, "학원비 결제 사이트코드"));
        checks.add(pgCheck(id, PgPurpose.MEAL, "급식비 결제 사이트코드"));

        boolean mealPriced = mealPolicyRepository.find(id, year)
                .map(policy -> policy.isPriced())
                .orElse(false);
        checks.add(Check.of("MEAL_PRICE", "급식 단가", Severity.BLOCKER, mealPriced,
                "없으면 신청은 되는데 금액이 빈 주문이 쌓여 청구를 만들 수 없다"));

        checks.add(Check.of("ATTENDANCE_DEADLINE", "등원 기준시각", Severity.WARNING,
                !DEFAULT_DEADLINE.equals(academy.getAttendanceDeadline()),
                "%s — 기본값 그대로면 미등원 감지와 지각 판정이 틀린 시각으로 돈다"
                        .formatted(academy.getAttendanceDeadline())));

        int prices = tuitionRepository.findAllOfYear(id, year).size();
        checks.add(Check.of("TUITION_PRICE", "지점 교습비", Severity.WARNING, prices > 0,
                prices > 0 ? "%d건 등록됨".formatted(prices)
                        : "지점 가격이 없어 공통 가격이 적용된다 — 목동·분당·1인실은 값이 다르다"));

        return checks;
    }

    /**
     * 결제 준비 상태.
     *
     * <p>바이링크는 <b>상점관리자 계정까지</b> 있어야 한다 — 없으면 사이트코드가 등록돼
     * 있어도 결제 링크 생성에서 거절된다.
     */
    private Check pgCheck(Long academyId, PgPurpose purpose, String title) {
        var site = pgSiteRepository.findForUse(academyId, purpose, PgChannel.BUYLINK);
        if (site.isEmpty()) {
            return Check.of("PG_SITE_" + purpose, title, Severity.BLOCKER, false,
                    "사이트코드가 없어 결제 링크를 만들 수 없다");
        }
        String mgmtId = site.get().getMgmtId();
        boolean ready = mgmtId != null && !mgmtId.isBlank();
        return Check.of("PG_SITE_" + purpose, title, Severity.BLOCKER, ready,
                ready ? "%s 등록됨".formatted(site.get().getSiteCd())
                        : "상점관리자 계정이 비어 있다 — 결제 시점에 거절된다");
    }

    public enum Severity {
        /** 그 기능이 아예 안 된다 */
        BLOCKER,
        /** 돌기는 도는데 조용히 틀린 값으로 돈다 */
        WARNING
    }

    /**
     * @param ok     통과 여부
     * @param detail <b>무엇이 빠졌고 그래서 무슨 일이 나는지</b>를 적는다 — "설정 필요" 만
     *               적으면 읽는 사람이 급한지 아닌지 판단할 수 없다
     */
    public record Check(String code, String title, Severity severity, boolean ok, String detail) {

        static Check of(String code, String title, Severity severity, boolean ok, String detail) {
            return new Check(code, title, severity, ok, detail);
        }

        public boolean blocking() {
            return !ok && severity == Severity.BLOCKER;
        }

        public boolean warning() {
            return !ok && severity == Severity.WARNING;
        }
    }

    public record AcademyReport(Long academyId, String academyName, List<Check> checks) {
    }

    /**
     * @param blockerCount 0 이 되어야 그 해 운영이 가능하다
     */
    public record Report(short year, int blockerCount, int warningCount,
                         List<Check> common, List<AcademyReport> academies) {
    }
}
