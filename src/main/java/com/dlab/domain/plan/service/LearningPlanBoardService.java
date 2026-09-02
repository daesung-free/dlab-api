package com.dlab.domain.plan.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.plan.repository.LearningPlanBoardRepository;
import com.dlab.domain.plan.repository.LearningPlanBoardRepository.ClassRef;
import com.dlab.domain.plan.repository.LearningPlanBoardRepository.PlanAggregate;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반·지점 단위 학습계획 현황 (F-4.11-2 목록).
 *
 * <p><b>왜 학생별 통계 API로는 안 되는가.</b> 화면은 반 전체를 이행률·미작성일 순으로
 * 늘어놓고 "누가 안 쓰고 있는지"를 찾는 곳이다. 학생 1명짜리 통계를 학생 수만큼 부르면
 * 30명 반에 30번, 지점 전체면 수백 번이 나가고, 정렬은 클라이언트가 전부 받아온 뒤에야
 * 할 수 있다. 그래서 목록을 서버가 한 번에 만든다.
 *
 * <p><b>계획을 한 줄도 안 쓴 학생이 목록에 남아야 한다.</b> 계획 테이블을 기준으로 뽑으면
 * 그런 학생이 통째로 빠져 화면이 "미작성"을 그리지 못한다 — 정작 이 화면이 찾으려는
 * 대상이다. 그래서 <b>재원생 명단이 기준</b>이고 계획은 거기에 붙인다.
 *
 * <p><b>조회 전용이다.</b> 담임이 학생 계획을 대신 고치는 경로는 두지 않는다
 * (0803 답변서 — 시간 배분의 주도권은 학생).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearningPlanBoardService {

    /** 지점 전체 × 장기간은 이 화면의 용도가 아니다. 주·월 단위로 본다. */
    private static final int MAX_RANGE_DAYS = 366;

    private final StudentEnrollmentRepository enrollmentRepository;
    private final LearningPlanBoardRepository boardRepository;

    /**
     * 목록 한 줄.
     *
     * <p><b>이행률은 시간 기준이다</b>(분). 줄 수 기준으로 재면 10분짜리 한 줄과
     * 3시간짜리 한 줄이 같은 무게가 되어, 짧은 항목을 여러 개 체크한 학생이 더 성실해 보인다.
     * 줄 수도 함께 내려 화면이 필요하면 그쪽을 쓸 수 있게 한다.
     *
     * <p><b>{@code completionRate}는 서버가 계산한다.</b> 클라이언트가 나누면 반올림이 갈려
     * 같은 학생의 이행률이 화면마다 다르게 보인다.
     *
     * @param missingDays 조회 기간 중 계획을 한 줄도 안 쓴 날 수
     */
    public record BoardRow(Long enrollmentId, String studentNo, String studentName,
                           Long classId, String className,
                           int plannedMinutes, int doneMinutes, int completionRate,
                           long totalItems, long doneItems,
                           long plannedDays, long missingDays) {
    }

    /**
     * 정렬 허용 목록. 여기 없는 필드는 무시한다 — 클라이언트가 보낸 문자열을 그대로
     * 정렬 키로 쓰면 없는 필드에서 예외가 나거나 의도치 않은 값으로 정렬된다.
     *
     * <p>정렬 키가 <b>DB 컬럼이 아니라 파생값</b>(이행률·미작성일)이라
     * {@code SearchSupport.toOrders()}(엔티티 경로 기반)를 쓸 수 없다. 허용 목록으로
     * 임의 정렬을 막는다는 규칙은 그대로 지키되 비교자로 구현한다.
     */
    private static final Map<String, Comparator<BoardRow>> SORTABLE = Map.of(
            "studentNo", Comparator.comparing(BoardRow::studentNo,
                    Comparator.nullsLast(Comparator.naturalOrder())),
            "studentName", Comparator.comparing(BoardRow::studentName,
                    Comparator.nullsLast(Comparator.naturalOrder())),
            "className", Comparator.comparing(BoardRow::className,
                    Comparator.nullsLast(Comparator.naturalOrder())),
            "completionRate", Comparator.comparingInt(BoardRow::completionRate),
            "missingDays", Comparator.comparingLong(BoardRow::missingDays),
            "plannedMinutes", Comparator.comparingInt(BoardRow::plannedMinutes),
            "doneMinutes", Comparator.comparingInt(BoardRow::doneMinutes));

    /**
     * 반·지점 학습계획 현황.
     *
     * <p><b>쿼리는 3개로 고정된다</b> — 재원생 명단 · 반 배정 · 기간 집계. 학생 수가 늘어도
     * 늘지 않는다.
     *
     * <p><b>정렬·페이징은 메모리에서 한다.</b> 정렬 키가 집계에서 나오는 파생값이라
     * DB에서 자르면 "이행률 낮은 순 상위 20명"을 만들 수 없다. 대상이 한 지점의 재원생으로
     * 한정되고 학생당 숫자 몇 개만 들고 있으므로 이 범위에서는 감당된다.
     *
     * @param academyId 지점. 호출부가 인증 주체로 검증한 값이어야 한다
     * @param from      조회 시작일(화면은 그 주 월요일)
     * @param to        조회 종료일(화면은 그 주 일요일)
     * @param classId   반 필터. null이면 지점 전체(미배정 포함)
     */
    public Page<BoardRow> board(Long academyId, LocalDate from, LocalDate to,
                                Long classId, Pageable pageable) {
        long days = rangeDays(from, to);

        Map<Long, ClassRef> classes = boardRepository.fixedClasses(academyId);
        Map<Long, PlanAggregate> aggregates = boardRepository.aggregate(academyId, from, to);

        List<BoardRow> rows = enrollmentRepository.findCurrentByAcademyId(academyId).stream()
                // 휴원·퇴원은 계획을 쓸 이유가 없다. 목록에 남기면 미작성자로 오인된다
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .filter(e -> matchesClass(classes.get(e.getId()), classId))
                .map(e -> toRow(e, classes.get(e.getId()),
                        aggregates.getOrDefault(e.getId(), PlanAggregate.EMPTY), days))
                .sorted(comparator(pageable.getSort()))
                .toList();

        return page(rows, pageable);
    }

    private BoardRow toRow(StudentEnrollment enrollment, ClassRef clazz,
                           PlanAggregate aggregate, long days) {
        return new BoardRow(
                enrollment.getId(),
                enrollment.getStudentNo(),
                enrollment.getStudent().getName(),
                clazz == null ? null : clazz.id(),
                clazz == null ? null : clazz.name(),
                aggregate.plannedMinutes(),
                aggregate.doneMinutes(),
                completionRate(aggregate),
                aggregate.totalItems(),
                aggregate.doneItems(),
                aggregate.plannedDays(),
                days - aggregate.plannedDays());
    }

    /**
     * 계획이 없으면 0%다.
     *
     * <p>"나눌 것이 없으니 100%"로 두면 아무것도 안 쓴 학생이 목록 맨 위에 우등생으로 뜬다.
     */
    private int completionRate(PlanAggregate aggregate) {
        if (aggregate.plannedMinutes() == 0) {
            return 0;
        }
        return (int) Math.round(aggregate.doneMinutes() * 100.0 / aggregate.plannedMinutes());
    }

    private boolean matchesClass(ClassRef clazz, Long classId) {
        if (classId == null) {
            return true;
        }
        return clazz != null && clazz.id().equals(classId);
    }

    /**
     * 정렬 지정이 없으면 <b>이행률 낮은 순</b>이다 — 이 화면은 잘하고 있는 학생이 아니라
     * 손이 필요한 학생을 찾는 곳이다. 같은 이행률이면 미작성일이 많은 쪽을 먼저 본다.
     *
     * <p>마지막에 학번을 항상 덧붙인다. 안 그러면 값이 같은 학생들의 순서가 조회마다 달라져
     * 페이지를 넘길 때 같은 학생이 두 번 나오거나 빠진다.
     */
    private Comparator<BoardRow> comparator(Sort sort) {
        Comparator<BoardRow> result = null;
        for (Sort.Order order : sort) {
            Comparator<BoardRow> next = SORTABLE.get(order.getProperty());
            if (next == null) {
                continue;
            }
            if (order.isDescending()) {
                next = next.reversed();
            }
            result = result == null ? next : result.thenComparing(next);
        }
        if (result == null) {
            result = SORTABLE.get("completionRate")
                    .thenComparing(SORTABLE.get("missingDays").reversed());
        }
        return result.thenComparing(SORTABLE.get("studentNo"));
    }

    private Page<BoardRow> page(List<BoardRow> rows, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(rows, pageable, rows.size());
        }
        int fromIndex = (int) Math.min(pageable.getOffset(), rows.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(fromIndex, toIndex), pageable, rows.size());
    }

    /**
     * 기간 검증. 끝일을 포함한 날수를 돌려준다 — 미작성일의 분모다.
     *
     * <p>주말을 빼지 않는다. 이 학원은 토요일에도 운영하고(CLAUDE.md §7), 학생이 일요일에
     * 계획을 쓰는 것도 막지 않는다. 화면이 보내는 기간이 곧 분모다.
     */
    private long rangeDays(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 종료일이 시작일보다 빠릅니다.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "조회 기간은 최대 %d일입니다.".formatted(MAX_RANGE_DAYS));
        }
        return days;
    }
}
