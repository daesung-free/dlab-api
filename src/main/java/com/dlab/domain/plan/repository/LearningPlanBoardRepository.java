package com.dlab.domain.plan.repository;

import com.dlab.domain.plan.entity.QLearningPlan;
import com.dlab.domain.plan.entity.QLearningPlanItem;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.QClassAssignment;
import com.dlab.domain.user.entity.QClassMaster;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 반·지점 단위 학습계획 현황 집계 (F-4.11-2 목록 화면).
 *
 * <p><b>학생 한 명씩 조회하지 않는다.</b> 화면이 반 전체를 이행률·미작성일 순으로 그리는데,
 * 학생별 통계 API를 학생 수만큼 부르면 30명 반에 30번, 지점 전체면 수백 번이 나간다.
 * 여기서는 <b>지점·기간 조건으로 한 번에 group by</b> 해서 학생 수와 무관하게 쿼리가
 * 일정하게 유지된다.
 *
 * <p><b>합계를 DB에서 낸다.</b> 항목 행을 전부 읽어 애플리케이션에서 더하면 학생 수 ×
 * 기간 일수만큼 엔티티가 올라온다 — 지점 전체 한 달이면 수만 행이다. 화면이 필요로 하는
 * 것은 학생당 숫자 몇 개뿐이라 행을 올릴 이유가 없다.
 */
@Repository
@RequiredArgsConstructor
public class LearningPlanBoardRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * 학생별 집계 한 줄.
     *
     * <p>{@code plannedDays}는 <b>항목이 실제로 남아 있는 날</b>의 수다. 계획 행만 있고
     * 항목을 전부 지운 날은 세지 않는다 — 화면의 "미작성일"이 학생 입장에서
     * "아무것도 안 쓴 날"을 뜻하기 때문이다.
     *
     * @param plannedMinutes 계획된 분
     * @param doneMinutes    이행(O) 처리된 분. 실제 학습시간이 아니라 체크된 계획 시간이다
     * @param totalItems     계획 줄 수
     * @param doneItems      이행 체크된 줄 수
     * @param plannedDays    계획을 쓴 날 수
     */
    public record PlanAggregate(int plannedMinutes, int doneMinutes,
                                long totalItems, long doneItems, long plannedDays) {

        public static final PlanAggregate EMPTY = new PlanAggregate(0, 0, 0, 0, 0);
    }

    /**
     * 지점·기간의 학생별 집계. 키는 {@code enrollment_id}다.
     *
     * <p><b>계획이 하나도 없는 학생은 결과에 없다.</b> 목록에서 빼라는 뜻이 아니라
     * 더할 것이 없다는 뜻이다 — 호출부가 재원생 명단을 기준으로 삼고 여기 없는 학생을
     * {@link PlanAggregate#EMPTY}로 채워야 한다. 그러지 않으면 미작성 학생이 화면에서
     * 사라져 정작 찾아야 할 대상이 안 보인다.
     */
    public Map<Long, PlanAggregate> aggregate(Long academyId, LocalDate from, LocalDate to) {
        QLearningPlan plan = QLearningPlan.learningPlan;
        QLearningPlanItem item = QLearningPlanItem.learningPlanItem;

        NumberExpression<Integer> minutes = item.durationMinutes.castToNum(Integer.class);
        NumberExpression<Integer> doneMinutes = new CaseBuilder()
                .when(item.done.isTrue()).then(minutes).otherwise(0);
        NumberExpression<Integer> doneFlag = new CaseBuilder()
                .when(item.done.isTrue()).then(1).otherwise(0);

        // sumLong(): 이 QueryDSL 포크의 sum()은 Class를 요구한다. 합계 자릿수를 넉넉히
        // 두는 편이 안전하기도 하다 — 분 단위 합은 지점·연 단위로 쉽게 커진다
        NumberExpression<Long> plannedSum = minutes.sumLong();
        NumberExpression<Long> doneSum = doneMinutes.sumLong();
        NumberExpression<Long> doneCount = doneFlag.sumLong();

        List<Tuple> rows = queryFactory
                .select(plan.enrollment.id,
                        plannedSum,
                        doneSum,
                        item.count(),
                        doneCount,
                        plan.planDate.countDistinct())
                .from(item)
                .join(item.plan, plan)
                .where(plan.academy.id.eq(academyId),
                        plan.planDate.between(from, to),
                        plan.deleted.isFalse(),
                        item.deleted.isFalse())
                .groupBy(plan.enrollment.id)
                .fetch();

        Map<Long, PlanAggregate> result = new HashMap<>();
        for (Tuple row : rows) {
            result.put(row.get(plan.enrollment.id), new PlanAggregate(
                    intOf(row.get(plannedSum)),
                    intOf(row.get(doneSum)),
                    longOf(row.get(item.count())),
                    longOf(row.get(doneCount)),
                    longOf(row.get(plan.planDate.countDistinct()))));
        }
        return result;
    }

    /**
     * 지점 학생의 현재 고정반. 키는 {@code enrollment_id}.
     *
     * <p><b>학생마다 따로 조회하지 않는다.</b> 반 이름은 목록의 한 칸일 뿐인데 학생 수만큼
     * 쿼리가 나가면 집계를 한 번에 한 의미가 없어진다.
     *
     * <p>이동수업반은 제외한다 — 화면의 "반"은 담임이 붙는 고정반이다.
     */
    public Map<Long, ClassRef> fixedClasses(Long academyId) {
        QClassAssignment assignment = QClassAssignment.classAssignment;
        QClassMaster classMaster = QClassMaster.classMaster;

        List<Tuple> rows = queryFactory
                .select(assignment.enrollment.id, classMaster.id, classMaster.name)
                .from(assignment)
                .join(assignment.classMaster, classMaster)
                .where(assignment.academy.id.eq(academyId),
                        assignment.classType.eq(ClassType.FIXED),
                        assignment.active.isTrue(),
                        assignment.deleted.isFalse())
                .fetch();

        Map<Long, ClassRef> result = new HashMap<>();
        for (Tuple row : rows) {
            result.put(row.get(assignment.enrollment.id),
                    new ClassRef(row.get(classMaster.id), row.get(classMaster.name)));
        }
        return result;
    }

    /** 목록에 표시할 반. 미배정 학생은 이 값이 없다. */
    public record ClassRef(Long id, String name) {
    }

    private static int intOf(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }

    private static long longOf(Long value) {
        return value == null ? 0L : value;
    }
}
