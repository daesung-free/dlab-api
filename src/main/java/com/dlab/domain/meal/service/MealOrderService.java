package com.dlab.domain.meal.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.meal.entity.CancelPath;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.repository.MealOrderItemRepository;
import com.dlab.domain.meal.repository.MealOrderRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 신청·취소 (F-4.5 · A-9).
 *
 * <h2>결제는 아직 없다</h2>
 * PG 스펙(E-3)이 미확보라 주문은 {@code PENDING}에서 움직이지 않는다.
 * 상태값 자체는 시트가 확정한 것을 쓰므로, 결제가 붙으면 전이만 이어 붙이면 된다.
 *
 * <h2>★ 앱과 데스크는 진입점을 나눈다</h2>
 * {@code bypassDeadline} 같은 플래그를 넘기지 않는다 — 앱 컨트롤러에서 {@code true}를
 * 넘기는 사고가 난다. 데스크는 기간 제한이 없다(F-4.5 <i>"관리자 취소는 기간 제한 없이 즉시"</i>).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealOrderService {

    private final MealOrderRepository orderRepository;
    private final MealOrderItemRepository itemRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final MealScheduleService scheduleService;
    private final com.dlab.domain.appconfig.service.TermsService termsService;
    private final Clock clock;

    /**
     * 앱 신청 — 한 달치 일괄.
     *
     * <p><b>전부-아니면-전무다.</b> 한 건만 어긋나도 전체를 거부한다 — 결제가 붙으면
     * 금액이 한 덩어리로 확정돼야 해서, 지금 부분 반영으로 만들면 그때 갈아엎어야 한다.
     *
     * <p>이미 그 달 주문이 있으면 <b>항목만 더한다</b>. 주문을 또 만들면 결제가 쪼개진다.
     */
    /**
     * 앱에서 학생이 직접 신청 — <b>급식업체 제3자 제공 동의를 먼저 확인한다</b>.
     *
     * <p>동의서가 <i>"급식 신청 시에만 급식업체에 정보 제공"</i>이라, 동의 없이 신청을 받으면
     * <b>제공 근거 없이 이름·좌석번호·연락처가 업체로 나간다.</b> 가입 때 미동의했더라도
     * 여기서 동의하면 신청할 수 있으므로, 앱은 이 오류를 받으면 동의 화면을 띄우면 된다.
     *
     * <p><b>관리자 대행 신청({@link #apply})은 이 검사를 타지 않는다</b> — 서면으로 받은
     * 동의를 관리자가 대신 반영하는 경우가 있어, 앱 동의가 없다고 막으면 그 업무가 멈춘다.
     *
     * <p><b>약관을 아직 등록하지 않았으면 막지 않는다</b>(문구가 미확정이다). 등록하는
     * 순간부터 시행된다 — 자세한 것은 {@code TermsService.needsAgreement}.
     */
    @Transactional
    public MealOrder applyByStudent(Long accountId, Long enrollmentId, YearMonth month,
                                    List<MealSelection> selections) {
        if (termsService.needsAgreement(accountId,
                com.dlab.domain.appconfig.entity.TermsCode.MEAL_THIRD_PARTY)) {
            throw new BusinessException(ErrorCode.MEAL_THIRD_PARTY_CONSENT_REQUIRED);
        }
        return apply(enrollmentId, month, selections);
    }

    @Transactional
    public MealOrder apply(Long enrollmentId, YearMonth month, List<MealSelection> selections) {
        StudentEnrollment enrollment = requireEnrollment(enrollmentId);
        Long academyId = enrollment.getAcademy().getId();

        if (!scheduleService.isWindowOpen(academyId, month)) {
            throw new BusinessException(ErrorCode.MEAL_WINDOW_CLOSED);
        }
        validateSelections(enrollment, month, selections);

        MealOrder order = orderRepository
                .findActiveByEnrollmentAndMonth(enrollmentId, month.atDay(1))
                .orElseGet(() -> orderRepository.save(new MealOrder(enrollment, month)));

        Set<String> existing = order.activeItems().stream()
                .map(MealOrderService::key)
                .collect(Collectors.toSet());

        // ★ 지금 단가를 항목에 박아 둔다. 나중에 마스터를 다시 읽어 계산하면
        //   단가를 올리는 순간 과거 주문 금액이 소급해서 바뀐다.
        //   단가 미등록 지점은 null이 들어가고, 그 주문은 청구를 만들 수 없다 —
        //   신청 자체를 막지는 않는다(업체 연결 전에도 운영이 돌아야 한다).
        Integer unitPrice = scheduleService.unitPrice(academyId, enrollment.getYear());
        if (unitPrice == null) {
            log.warn("급식 단가 미등록 — 청구를 만들 수 없다: academyId={}, year={}",
                    academyId, enrollment.getYear());
        }

        for (MealSelection s : selections) {
            if (existing.contains(key(s.date(), s.mealType()))) {
                throw new BusinessException(ErrorCode.MEAL_ALREADY_APPLIED,
                        "%s %s은 이미 신청했습니다.".formatted(s.date(), s.mealType()));
            }
            order.addItem(s.date(), s.mealType(), unitPrice);
        }

        log.info("급식 신청: enrollmentId={}, 대상월={}, 건수={}",
                enrollmentId, month, selections.size());
        return order;
    }

    /**
     * 앱 취소 — 항목 단위, 마감 D-n 전까지.
     *
     * <p>PG 자동환불 대상이지만 결제가 없어 지금은 취소만 남긴다.
     */
    @Transactional
    public void cancelByStudent(Long enrollmentId, Long itemId) {
        MealOrderItem item = requireItem(itemId);
        if (!item.getOrder().getEnrollment().getId().equals(enrollmentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 신청만 취소할 수 있습니다.");
        }

        short year = item.getOrder().getEnrollment().getYear();
        if (!scheduleService.isBeforeDeadline(
                item.getOrder().getAcademy().getId(), year, item.getMealDate())) {
            throw new BusinessException(ErrorCode.MEAL_DEADLINE_PASSED);
        }

        cancel(item, CancelPath.APP);
    }

    /**
     * 관리자 취소 — <b>기간 제한 없이 즉시</b>.
     *
     * <p>환불은 데스크에서 개별 처리한다(화면 정책 탭).
     */
    @Transactional
    public void cancelByAdmin(AuthPrincipal me, Long itemId) {
        MealOrderItem item = requireItem(itemId);
        if (!me.canAccessAcademy(item.getOrder().getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        cancel(item, CancelPath.DESK);
        log.info("급식 관리자 취소: itemId={}, 처리자={}", itemId, me.accountId());
    }

    /**
     * 중단일 등록으로 인한 일괄 취소.
     *
     * <p><b>환불은 여기서 하지 않는다.</b> 화면은 <i>"서버가 결제건 확인 → 환불 배치 →
     * 중단 확정"</i> 순서를 요구하지만 결제가 없다. 지금은 {@link CancelPath#CLOSURE}로
     * 취소만 남기고, payment가 붙을 때 <b>이 경로가 곧 환불 대상 조회 지점</b>이 된다.
     *
     * @return 취소된 건수. 화면이 "N건 환불 대상"으로 표시한다
     */
    @Transactional
    public int cancelByClosure(Long academyId, LocalDate date) {
        List<MealOrderItem> items = itemRepository.findActiveByAcademyAndDate(academyId, date);
        items.forEach(i -> cancel(i, CancelPath.CLOSURE));

        if (!items.isEmpty()) {
            log.warn("급식 중단일 등록으로 일괄 취소: 지점={}, 일자={}, 건수={} (환불 대상)",
                    academyId, date, items.size());
        }
        return items.size();
    }

    /**
     * 퇴원·제적·수료 정리 — <b>그날 이후</b> 신청 일괄 취소.
     *
     * <p>지난 날짜는 건드리지 않는다. 이미 먹은 급식이라 취소하면 정산이 어긋난다.
     *
     * <p><b>마감(D-n)을 보지 않는다.</b> 퇴원은 학생 사정이 아니라 등록 자체가 끝난
     * 것이라, 마감이 지났다고 남겨두면 <b>학교에 오지 않는 학생의 급식이 계속 나간다.</b>
     *
     * <p>환불은 여기서 하지 않는다 — {@link CancelPath#WITHDRAWAL}로 남기고,
     * payment가 붙을 때 이 경로가 곧 환불 대상 조회 지점이 된다.
     *
     * @return 취소된 건수. 관리자 화면이 "N건 정리됨"으로 표시한다
     */
    @Transactional
    public int cancelByWithdrawal(Long enrollmentId, LocalDate from) {
        List<MealOrderItem> items =
                itemRepository.findActiveByEnrollmentFrom(enrollmentId, from);
        items.forEach(i -> cancel(i, CancelPath.WITHDRAWAL));

        if (!items.isEmpty()) {
            log.warn("퇴원 정리로 급식 신청 취소: enrollmentId={}, 기준일={}, 건수={} (환불 대상)",
                    enrollmentId, from, items.size());
        }
        return items.size();
    }

    @Transactional(readOnly = true)
    public List<MealOrder> findByMonth(Long academyId, YearMonth month) {
        return orderRepository.findByAcademyAndMonth(academyId, month.atDay(1));
    }

    @Transactional(readOnly = true)
    public MealOrder findMine(Long enrollmentId, YearMonth month) {
        return orderRepository.findActiveByEnrollmentAndMonth(enrollmentId, month.atDay(1))
                .orElse(null);
    }

    private void cancel(MealOrderItem item, CancelPath path) {
        if (!item.isActive()) {
            return;   // 이미 취소됨 — 멱등하게 넘긴다
        }
        Instant now = Instant.now(clock);
        item.cancel(now, path);
        item.getOrder().cancelIfEmpty(now);
    }

    /**
     * 신청 검증.
     *
     * <p>날짜마다 셋을 본다 — <b>대상 월인가 · 급식 가능일인가 · 마감 전인가</b>.
     * 가능일 판정은 {@link MealScheduleService} 하나만 쓴다(주말·공휴일·중단일).
     */
    private void validateSelections(StudentEnrollment enrollment, YearMonth month,
                                    List<MealSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "신청할 날짜를 선택해주세요.");
        }
        Long academyId = enrollment.getAcademy().getId();
        Set<LocalDate> available = Set.copyOf(scheduleService.availableDates(academyId, month));

        for (MealSelection s : selections) {
            if (!YearMonth.from(s.date()).equals(month)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "대상 월이 아닌 날짜가 있습니다: " + s.date());
            }
            if (!available.contains(s.date())) {
                throw new BusinessException(ErrorCode.MEAL_DATE_NOT_AVAILABLE,
                        "급식을 신청할 수 없는 날짜입니다: " + s.date());
            }
            if (!scheduleService.isBeforeDeadline(academyId, enrollment.getYear(), s.date())) {
                throw new BusinessException(ErrorCode.MEAL_DEADLINE_PASSED,
                        "신청 마감이 지난 날짜가 있습니다: " + s.date());
            }
        }

        // 요청 안에서의 중복도 막는다 — DB 유니크가 잡기 전에 알려주는 편이 낫다
        if (selections.stream().map(s -> key(s.date(), s.mealType())).distinct().count()
                != selections.size()) {
            throw new BusinessException(ErrorCode.MEAL_ALREADY_APPLIED,
                    "같은 끼니가 중복 선택됐습니다.");
        }
    }

    private static String key(MealOrderItem item) {
        return key(item.getMealDate(), item.getMealType());
    }

    private static String key(LocalDate date, MealType type) {
        return date + ":" + type;
    }

    private MealOrderItem requireItem(Long itemId) {
        return itemRepository.findById(itemId)
                .filter(i -> !i.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEAL_ITEM_NOT_FOUND));
    }

    private StudentEnrollment requireEnrollment(Long enrollmentId) {
        return enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
    }

    /** 날짜 × 끼니 한 칸. 앱 달력에서 고른 값이다. */
    public record MealSelection(LocalDate date, MealType mealType) {
    }
}
