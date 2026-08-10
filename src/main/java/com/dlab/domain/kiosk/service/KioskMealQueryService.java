package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.common.privacy.Masking;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.repository.MealOrderItemRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 급식 조회 (DSA 3.30 · 3.31).
 *
 * <p><b>이 두 개가 없으면 급식이 전부 뚫린다.</b> 키오스크는 {@code getMealApplyYN} 호출이
 * 실패하면 <b>전부 허용</b>으로 폴백한다(그쪽 {@code DsaMealService}에 {@code return true}가
 * 네 군데). 에러 화면조차 안 뜨고 신청 안 한 학생이 그대로 배식받는다 —
 * "아직 급식 도메인이 없다"가 안전한 상태가 아니라 <b>가장 위험한 상태</b>다.
 *
 * <p>범위는 <b>신청 여부 조회</b>까지다. 결제·환불은 PG 스펙(E-3)·데스크 결제방식(I-13)
 * 미확정이라 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskMealQueryService {

    private final MealOrderItemRepository mealOrderItemRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /**
     * 3.31 {@code getMealApplyYN} — 금일 그 끼니를 신청했는가.
     *
     * <p><b>모르면 {@code N}이다.</b> 카드가 등록돼 있지 않거나 값이 이상하면 거절한다 —
     * 우리가 허용으로 기울면 키오스크 폴백까지 겹쳐 이중으로 뚫린다.
     */
    public boolean isApplied(Long academyId, String rfidNo, String mealGb) {
        MealType mealType = MealType.fromRequestCode(mealGb);
        if (mealType == null) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);

        return mealOrderItemRepository.isApplied(
                enrollment.getId(), LocalDate.now(clock), mealType);
    }

    /**
     * 3.30 {@code getMealApplyStdInfo} — 지점의 월별 신청 내역.
     *
     * <p>⚠️ <b>{@code day}는 "일"만 넣는다</b>(예: {@code "5"}). 키오스크가
     * {@code month.atDay(Integer.parseInt(day))}로 조립하므로 {@code "2026-08-05"}처럼
     * 전체 날짜를 보내면 <b>파싱에 실패해 그 행이 조용히 버려진다</b>.
     */
    public List<MealApplyRow> monthlyApplications(Long academyId, String month) {
        YearMonth target = parseMonth(month);

        return mealOrderItemRepository.findActiveByAcademyAndPeriod(
                        academyId, target.atDay(1), target.atEndOfMonth()).stream()
                .map(this::toRow)
                .toList();
    }

    private MealApplyRow toRow(MealOrderItem m) {
        return new MealApplyRow(
                // 공용 화면에 뜨는 목록이라 이름은 마스킹한다(§ getStdInfoList와 같은 기준)
                Masking.name(m.getOrder().getEnrollment().getStudent().getName()),
                m.getOrder().getEnrollment().getStudentNo(),
                String.valueOf(m.getMealDate().getDayOfMonth()),
                m.getMealType().responseLabel());
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now(clock));
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new DsaApiException(DsaCode.INVALID_MONTH);
        }
    }

    private StudentEnrollment requireEnrollment(Long academyId, String rfidNo) {
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .filter(e -> e.getAcademy().getId().equals(academyId))
                .orElseThrow(() -> new DsaApiException(DsaCode.INVALID_KEY, "등록되지 않은 카드입니다."));
    }

    /** @param day 일(day of month)만. 전체 날짜를 넣으면 키오스크가 못 읽는다 */
    public record MealApplyRow(String stdNm, String stdNo, String day, String mealGb) {
    }
}
