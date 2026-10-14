package com.dlab.domain.meal.service;

import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.repository.MealOrderItemRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 <b>일자별 신청 명단</b> (피드백 5-4).
 *
 * <h2>왜 월별 집계로는 안 되는가</h2>
 * 기존 화면은 {@code /meals/monthly}(달력 칸마다 인원 수)와 {@code /meals/orders}(월 단위
 * 주문 목록)뿐이라 <b>"오늘 누가 먹는지"에 답할 수 없다.</b> 배식대에서 필요한 것은 숫자가
 * 아니라 명단이고, 월 목록에서 그날을 추리려면 주문마다 항목을 펼쳐 봐야 한다.
 *
 * <h2>★ 「신청 vs 배식 대조」는 여기 없다</h2>
 * 배식 태깅이 키오스크에서 우리 쪽으로 들어오는 경로가 <b>아직 없다.</b> 대조 화면을
 * 만들어도 "실제로 먹은 사람" 칸을 채울 값이 없고, 빈 칸은 <b>"안 먹었다"로 읽힌다</b> —
 * 없는 것보다 나쁘다. 신청 명단까지가 지금 가능한 전부다.
 *
 * <h2>쿼리 수는 인원과 무관하다</h2>
 * 명단 1 + 반 1 + 좌석 1 = <b>3번으로 고정</b>이다. 반·좌석은 등록 건과 별도 테이블이라
 * 행마다 읽으면 쿼리가 신청자 수만큼 나간다({@code StudentListEnricher}와 같은 판단).
 * 장학·메모·계정까지 붙이는 그 enricher 를 그대로 쓰지 않은 이유는, 배식 명단에 필요 없는
 * 조회가 세 번 더 붙기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MealDailyRosterService {

    private final MealOrderItemRepository itemRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;

    /**
     * 그날 신청자 명단 — 점심·저녁을 나눠 돌려준다.
     *
     * <p><b>이름은 가리지 않는다.</b> 열람자에 따른 마스킹 판정은 표현 계층이 한다
     * (CLAUDE.md §7) — 도메인 서비스가 열람자를 알면 같은 데이터에 두 가지 진실이 생긴다.
     *
     * @param academyId <b>이미 검증된</b> 지점. 요청 파라미터를 그대로 넘기지 말 것 —
     *                  {@code AuthPrincipal.requireAcademyScope}를 거친 값이어야 한다
     */
    public Roster daily(Long academyId, LocalDate date) {
        List<MealOrderItem> items = itemRepository.findRosterByAcademyAndDate(academyId, date);
        if (items.isEmpty()) {
            // 빈 IN 절은 DB 에 따라 문법 오류가 되거나 전건을 훑는다
            return new Roster(date, List.of(), List.of());
        }

        List<Long> enrollmentIds = items.stream()
                .map(i -> i.getOrder().getEnrollment().getId()).distinct().toList();

        Map<Long, String> classNames = new HashMap<>();
        classAssignmentRepository.findActiveFixedByEnrollmentIds(enrollmentIds)
                .forEach(a -> classNames.put(
                        a.getEnrollment().getId(), a.getClassMaster().getName()));

        Map<Long, String> seats = new HashMap<>();
        seatAssignmentRepository.findActiveByEnrollmentIds(enrollmentIds)
                .forEach(a -> seats.put(a.getEnrollment().getId(), a.getSeat().getSeatCd()));

        List<Row> lunch = new ArrayList<>();
        List<Row> dinner = new ArrayList<>();
        for (MealOrderItem item : items) {
            StudentEnrollment enrollment = item.getOrder().getEnrollment();
            Row row = new Row(item.getId(), enrollment.getId(),
                    enrollment.getStudentNo(), enrollment.getStudent().getName(),
                    classNames.get(enrollment.getId()), seats.get(enrollment.getId()));
            (item.getMealType() == MealType.LUNCH ? lunch : dinner).add(row);
        }
        return new Roster(date, lunch, dinner);
    }

    /**
     * 한 사람 한 끼.
     *
     * @param orderItemId 끼니 항목 id. <b>관리자 취소가 이 단위</b>라
     *                    ({@code DELETE /meals/orders/items/{itemId}}) 명단에서 바로 취소할 수 있다
     * @param className   반 이름. <b>미배정이면 {@code null}</b> — 빈 문자열로 채우면
     *                    "반이 없는 학생"과 "이름이 빈 반"이 구분되지 않는다
     * @param seatCd      좌석번호. 미배정이면 {@code null}
     */
    public record Row(Long orderItemId, Long enrollmentId, String studentNo, String studentName,
                      String className, String seatCd) {
    }

    /** 끼니별 명단. 합치지 않는다 — 배식이 점심·저녁 따로 돌고 인원도 다르다. */
    public record Roster(LocalDate date, List<Row> lunch, List<Row> dinner) {
    }
}
