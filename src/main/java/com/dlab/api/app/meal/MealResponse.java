package com.dlab.api.app.meal;

import com.dlab.domain.meal.entity.MealClosure;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealOrderWindow;

import java.time.LocalDate;
import java.util.List;

/** 앱 급식 응답 DTO. */
public final class MealResponse {

    private MealResponse() {
    }

    /**
     * 신청 화면이 필요한 것 전부.
     *
     * <p><b>★ 앱이 가능일을 자체 판정하지 않는다</b>(A-9 명시). 주말·공휴일·중단일을 서버가
     * 빼고 내려준다 — 임시공휴일이 추가될 때마다 앱을 배포할 수는 없다.
     *
     * @param windowOpen 접수기간이 열려 있는가. <b>미등록이면 닫힘</b>이다 —
     *                   관리자가 안 열었는데 신청이 쌓이면 식수 통보가 어긋난다
     * @param closures   중단일 + 사유. 달력에 회색으로 표시하고 이유를 보여준다
     */
    public record Menu(String month, boolean windowOpen,
                       LocalDate windowFrom, LocalDate windowTo,
                       int deadlineDays, List<LocalDate> availableDates,
                       List<Closure> closures) {

        public static Menu of(java.time.YearMonth month, MealOrderWindow window,
                              short deadlineDays, List<LocalDate> availableDates,
                              List<MealClosure> closures, boolean windowOpen) {
            return new Menu(month.toString(), windowOpen,
                    window == null ? null : window.getStartsOn(),
                    window == null ? null : window.getEndsOn(),
                    deadlineDays, availableDates,
                    closures.stream().map(Closure::from).toList());
        }
    }

    public record Closure(LocalDate date, String reason) {

        public static Closure from(MealClosure c) {
            return new Closure(c.getClosureDate(), c.getReason());
        }
    }

    /**
     * 내 신청 내역.
     *
     * @param items 유효 항목만. 취소분은 빠진다 — 달력에 취소한 날이 신청된 것처럼 보이면 안 된다
     */
    public record Order(Long orderId, String month, String status, List<Item> items) {

        public static Order from(MealOrder order) {
            if (order == null) {
                return null;
            }
            return new Order(order.getId(), order.month().toString(),
                    order.getStatus().name(),
                    order.activeItems().stream().map(Item::from).toList());
        }
    }

    /**
     * @param cancelable 지금 취소할 수 있는가. 마감(D-n)이 지나면 {@code false}다 —
     *                   앱이 버튼을 비활성으로 그려야 눌렀다가 거절당하지 않는다
     */
    public record Item(Long itemId, LocalDate date, String mealType, boolean cancelable) {

        public static Item from(MealOrderItem item) {
            return new Item(item.getId(), item.getMealDate(), item.getMealType().name(), true);
        }

        public static Item from(MealOrderItem item, boolean cancelable) {
            return new Item(item.getId(), item.getMealDate(), item.getMealType().name(), cancelable);
        }
    }
}
