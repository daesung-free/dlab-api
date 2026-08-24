package com.dlab.domain.meal.service;

import com.dlab.common.holiday.HolidayCalendar;
import com.dlab.domain.meal.entity.MealClosure;
import com.dlab.domain.meal.entity.MealPolicy;
import com.dlab.domain.meal.entity.MealOrderWindow;
import com.dlab.domain.meal.repository.MealClosureRepository;
import com.dlab.domain.meal.repository.MealOrderWindowRepository;
import com.dlab.domain.meal.repository.MealPolicyRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 일정 판정 — <b>"언제 신청할 수 있고, 어느 날 급식이 있는가"의 유일한 출처</b>.
 *
 * <p>앱·데스크·월별 달력이 각자 계산하면 어긋난다. 앱 요구사항(A-9)도
 * <i>"서버 mealPolicy 기준으로 렌더 — <b>앱 자체 판정 금지</b>"</i>다.
 *
 * <h2>급식 가능일 = 주말·공휴일 제외 + 중단일 제외</h2>
 * 앞의 둘은 {@link HolidayCalendar#mealAvailableDates}가 이미 한다(시트: <i>"급식 가능일 —
 * MealPolicy.availableDates, 주말 + 공휴일 제외"</i>). <b>중단일은 그 계산기가 모른다</b> —
 * 공휴일과 다른 개념이라 여기서 얹는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MealScheduleService {

    private final HolidayCalendar holidayCalendar;
    private final MealClosureRepository closureRepository;
    private final MealPolicyRepository policyRepository;
    private final MealOrderWindowRepository windowRepository;
    private final Clock clock;

    /**
     * 그 달의 급식 가능일.
     *
     * <p>주말·공휴일은 {@link HolidayCalendar}가 빼고, 급식 중단일은 여기서 뺀다.
     */
    public List<LocalDate> availableDates(Long academyId, YearMonth month) {
        Set<LocalDate> closed = closureDates(academyId, month);
        return holidayCalendar.mealAvailableDates(academyId, month).stream()
                .filter(d -> !closed.contains(d))
                .toList();
    }

    public boolean isAvailable(Long academyId, LocalDate date) {
        return availableDates(academyId, YearMonth.from(date)).contains(date);
    }

    /** 그 달의 중단일. 달력이 사유까지 표시한다. */
    public List<MealClosure> closures(Long academyId, YearMonth month) {
        return closureRepository.findByAcademyAndPeriod(
                academyId, month.atDay(1), month.atEndOfMonth());
    }

    private Set<LocalDate> closureDates(Long academyId, YearMonth month) {
        return closures(academyId, month).stream()
                .map(MealClosure::getClosureDate)
                .collect(Collectors.toSet());
    }

    /**
     * 신청·취소 마감 D-n.
     *
     * <p>미등록 지점은 기본값 3일이다 — 화면 기본 선택과 같다.
     */
    public short deadlineDays(Long academyId, short year) {
        return policyRepository.find(academyId, year)
                .map(MealPolicy::getDeadlineDays)
                .orElse(MealPolicy.DEFAULT_DEADLINE_DAYS);
    }

    /**
     * 그 지점 한 끼 단가.
     *
     * <p><b>없으면 {@code null}이다 — 기본값을 만들지 않는다.</b> 단가는 지점마다 다르고
     * (대구만 8,000원) 바뀔 수 있어서, 임의값을 쓰면 <b>틀린 금액이 주문에 스냅샷으로
     * 박힌다.</b> 마감일수(D-n)에 기본값 3일을 두는 것과는 성격이 다르다 —
     * 그건 틀려도 신청 기간만 어긋나지만 이건 돈이다.
     */
    public Integer unitPrice(Long academyId, short year) {
        return policyRepository.find(academyId, year)
                .filter(MealPolicy::isPriced)
                .map(MealPolicy::getUnitPrice)
                .orElse(null);
    }

    /**
     * 마감 전인가.
     *
     * <p>화면: <i>"앱 신청·취소 모두 이용일 D-n일 전 23:59까지"</i>.
     * 그래서 <b>날짜 단위로 비교</b>한다 — 오늘이 (이용일 − n) 이하면 통과다.
     * 시각까지 따지면 23:59:59에 걸린 요청이 초 단위로 갈린다.
     */
    public boolean isBeforeDeadline(Long academyId, short year, LocalDate mealDate) {
        LocalDate deadline = mealDate.minusDays(deadlineDays(academyId, year));
        return !LocalDate.now(clock).isAfter(deadline);
    }

    /**
     * 그 달 접수기간이 지금 열려 있는가.
     *
     * <p><b>미등록이면 닫힘이다</b>(F-4.5 <i>"기간 밖에는 다음 달 신청 화면이 열리지 않음"</i>).
     * 급식업체에 식수를 통보해야 하는 일이라, 관리자가 안 열었는데 신청이 쌓이는 쪽이
     * 더 위험하다.
     */
    public boolean isWindowOpen(Long academyId, YearMonth targetMonth) {
        return windowRepository.find(academyId, targetMonth.atDay(1))
                .map(w -> w.isOpenOn(LocalDate.now(clock)))
                .orElse(false);
    }

    public MealOrderWindow window(Long academyId, YearMonth targetMonth) {
        return windowRepository.find(academyId, targetMonth.atDay(1)).orElse(null);
    }
}
