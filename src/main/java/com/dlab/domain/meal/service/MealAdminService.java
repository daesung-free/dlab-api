package com.dlab.domain.meal.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.meal.entity.MealClosure;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealOrderWindow;
import com.dlab.domain.meal.entity.MealPolicy;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.repository.MealClosureRepository;
import com.dlab.domain.meal.repository.MealOrderItemRepository;
import com.dlab.domain.meal.repository.MealOrderWindowRepository;
import com.dlab.domain.meal.repository.MealPolicyRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 관리자 기능 (F-4.5) — 월별 현황 · 중단일 · 마감 정책 · 접수기간.
 *
 * <p>화면 "급식 일정 관리" 탭이 이 셋을 한 화면에서 저장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealAdminService {

    private final MealClosureRepository closureRepository;
    private final MealPolicyRepository policyRepository;
    private final MealOrderWindowRepository windowRepository;
    private final MealOrderItemRepository itemRepository;
    private final AcademyRepository academyRepository;
    private final MealScheduleService scheduleService;
    private final MealOrderService orderService;

    /**
     * 월별 신청 현황 — 달력 한 칸씩.
     *
     * <p>화면이 색을 고를 수 있게 <b>왜 닫혔는지</b>까지 내린다.
     * 주말인지·공휴일인지·중단일인지 구분이 없으면 관리자가 원인을 알 수 없다.
     */
    @Transactional(readOnly = true)
    public List<DayStatus> monthly(AuthPrincipal me, Long academyId, YearMonth month) {
        Long resolved = requireAcademy(me, academyId).getId();

        Set<LocalDate> available = Set.copyOf(scheduleService.availableDates(resolved, month));
        Map<LocalDate, String> closures = new LinkedHashMap<>();
        scheduleService.closures(resolved, month)
                .forEach(c -> closures.put(c.getClosureDate(), c.getReason()));

        Map<LocalDate, long[]> counts = countByDate(resolved, month);

        return month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                .map(d -> {
                    long[] c = counts.getOrDefault(d, new long[2]);
                    return new DayStatus(d, available.contains(d), closures.get(d),
                            scheduleService.isAvailable(resolved, d) ? null : reasonOf(d, closures),
                            c[0], c[1]);
                })
                .toList();
    }

    private String reasonOf(LocalDate date, Map<LocalDate, String> closures) {
        if (closures.containsKey(date)) {
            return "CLOSURE";
        }
        return date.getDayOfWeek().getValue() >= 6 ? "WEEKEND" : "HOLIDAY";
    }

    private Map<LocalDate, long[]> countByDate(Long academyId, YearMonth month) {
        Map<LocalDate, long[]> result = new LinkedHashMap<>();
        itemRepository.findActiveByAcademyAndPeriod(academyId, month.atDay(1), month.atEndOfMonth())
                .forEach(i -> {
                    long[] c = result.computeIfAbsent(i.getMealDate(), k -> new long[2]);
                    if (i.getMealType() == MealType.LUNCH) {
                        c[0]++;
                    } else {
                        c[1]++;
                    }
                });
        return result;
    }

    /**
     * 중단일 등록.
     *
     * <p><b>이미 신청된 건을 함께 취소한다.</b> 화면 안내: <i>"이미 결제된 날을 중단으로
     * 바꾸면 해당 건은 전부 환불 대상"</i>. 환불은 결제가 붙을 때 이 취소분에서 찾는다.
     *
     * @return 함께 취소된 신청 건수 — 화면이 "N건 환불 대상"으로 표시한다
     */
    @Transactional
    public ClosureResult addClosure(AuthPrincipal me, Long academyId, LocalDate date,
                                    String reason) {
        Academy academy = requireAcademy(me, academyId);

        closureRepository.findByAcademyAndDate(academy.getId(), date).ifPresent(c -> {
            throw new BusinessException(ErrorCode.MEAL_CLOSURE_DUPLICATED);
        });

        MealClosure closure = closureRepository.save(new MealClosure(
                academy, (short) date.getYear(), date, reason));
        int canceled = orderService.cancelByClosure(academy.getId(), date);

        log.info("급식 중단일 등록: 지점={}, 일자={}, 사유={}, 취소={}건",
                academy.getId(), date, reason, canceled);
        return new ClosureResult(closure, canceled);
    }

    /**
     * 중단일 해제.
     *
     * <p><b>취소된 신청은 되살리지 않는다.</b> 그 사이 마감이 지났을 수도 있고,
     * 학생이 여전히 원하는지 알 수 없다 — 다시 신청하게 한다.
     */
    @Transactional
    public void removeClosure(AuthPrincipal me, Long closureId) {
        MealClosure closure = closureRepository.findById(closureId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "중단일을 찾을 수 없습니다."));
        requireAcademy(me, closure.getAcademy().getId());
        closure.markDeleted();
    }

    @Transactional(readOnly = true)
    public List<MealClosure> closures(AuthPrincipal me, Long academyId, YearMonth month) {
        return scheduleService.closures(requireAcademy(me, academyId).getId(), month);
    }

    /**
     * 마감 D-n 조회. <b>없으면 비어 있는 값을 돌려준다</b> — 미등록도 정상 상태다
     * (신청 판정이 기본값으로 돈다). 화면 쪽에서 기본값과 등록값을 구분한다.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<MealPolicy> findPolicy(AuthPrincipal me, Long academyId, short year) {
        return policyRepository.find(requireAcademy(me, academyId).getId(), year);
    }

    /** 마감 D-n 저장. 없으면 만든다. */
    @Transactional
    public MealPolicy saveDeadline(AuthPrincipal me, Long academyId, short year,
                                   short deadlineDays) {
        Academy academy = requireAcademy(me, academyId);
        if (deadlineDays < 0 || deadlineDays > 30) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "마감 일수가 올바르지 않습니다.");
        }
        return policyRepository.find(academy.getId(), year)
                .map(p -> {
                    p.changeDeadlineDays(deadlineDays);
                    return p;
                })
                .orElseGet(() -> policyRepository.save(
                        new MealPolicy(academy, year, deadlineDays)));
    }

    /** 접수기간 저장. 없으면 만든다. */
    @Transactional
    public MealOrderWindow saveWindow(AuthPrincipal me, Long academyId, YearMonth targetMonth,
                                      LocalDate startsOn, LocalDate endsOn) {
        Academy academy = requireAcademy(me, academyId);
        if (endsOn.isBefore(startsOn)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "종료일이 시작일보다 빠릅니다.");
        }
        return windowRepository.find(academy.getId(), targetMonth.atDay(1))
                .map(w -> {
                    w.changePeriod(startsOn, endsOn);
                    return w;
                })
                .orElseGet(() -> windowRepository.save(new MealOrderWindow(
                        academy, (short) targetMonth.getYear(), targetMonth, startsOn, endsOn)));
    }

    @Transactional(readOnly = true)
    public List<MealOrderWindow> windows(AuthPrincipal me, Long academyId, short year) {
        return windowRepository.findByYear(requireAcademy(me, academyId).getId(), year);
    }

    private Academy requireAcademy(AuthPrincipal me, Long academyId) {
        Long resolved = me.requireAcademyScope(academyId);
        return academyRepository.findById(resolved)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    /**
     * 달력 한 칸.
     *
     * @param closedReason 닫힌 이유 — {@code WEEKEND}/{@code HOLIDAY}/{@code CLOSURE}.
     *                     열려 있으면 {@code null}. 구분이 없으면 관리자가 원인을 모른다
     */
    public record DayStatus(LocalDate date, boolean available, String closureReason,
                            String closedReason, long lunchCount, long dinnerCount) {
    }

    /** @param canceledCount 중단일 등록으로 함께 취소된 신청 수. 환불 대상이다 */
    public record ClosureResult(MealClosure closure, int canceledCount) {
    }
}
