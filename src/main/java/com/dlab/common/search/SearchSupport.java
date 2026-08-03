package com.dlab.common.search;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * QueryDSL 페이징·정렬 공통 처리.
 */
public final class SearchSupport {

    private SearchSupport() {
    }

    /**
     * 페이지 결과를 만든다.
     *
     * <p>{@link PageableExecutionUtils}를 쓰는 이유: 마지막 페이지이거나 첫 페이지가 덜 찼으면
     * <b>count 쿼리를 아예 실행하지 않는다.</b> 학생·출결처럼 건수가 많은 목록에서
     * 매번 count를 도는 건 낭비다.
     */
    public static <T> Page<T> page(List<T> content, Pageable pageable, Supplier<Long> countSupplier) {
        return PageableExecutionUtils.getPage(content, pageable,
                () -> {
                    Long count = countSupplier.get();
                    return count == null ? 0L : count;
                });
    }

    /** 조회 쿼리에 offset/limit을 건다. */
    public static <T> JPAQuery<T> applyPaging(JPAQuery<T> query, Pageable pageable) {
        return query.offset(pageable.getOffset()).limit(pageable.getPageSize());
    }

    /**
     * {@code Pageable}의 정렬을 QueryDSL 정렬로 옮긴다.
     *
     * <p><b>허용 목록({@code sortable})에 없는 필드는 무시한다.</b> 클라이언트가 보낸
     * 문자열을 그대로 경로로 만들면 매핑되지 않은 필드에서 예외가 나거나,
     * 의도치 않은 컬럼으로 정렬돼 인덱스를 못 탄다.
     *
     * @param entityPath 엔티티 별칭 (예: {@code student})
     * @param sortable   정렬 허용 필드명
     */
    public static OrderSpecifier<?>[] toOrders(PathBuilder<?> entityPath,
                                               Sort sort,
                                               List<String> sortable) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            if (!sortable.contains(order.getProperty())) {
                continue;
            }
            orders.add(new OrderSpecifier<>(
                    order.isAscending() ? Order.ASC : Order.DESC,
                    entityPath.getComparable(order.getProperty(), Comparable.class)));
        }
        return orders.toArray(new OrderSpecifier<?>[0]);
    }
}
