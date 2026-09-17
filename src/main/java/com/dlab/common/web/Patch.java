package com.dlab.common.web;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * PATCH 요청에서 <b>"안 보냈다"와 "비워 달라"를 구분</b>하는 값 상자.
 *
 * <h2>왜 필요한가</h2>
 * 지금까지 PATCH DTO 는 {@code Integer capacity} 처럼 평범한 필드였고, 서비스가
 * {@code if (capacity != null)} 로 걸러 반영했다. 그래서 <b>값을 지우는 요청이 아무 일도
 * 하지 않는다</b> — {@code {"capacity": null}} 을 보내도 200 이 오고 정원은 그대로다.
 * 정원을 "제한 없음"으로 되돌리거나 설명을 지우는 화면 동작을 만들 수 없었다.
 *
 * <p>필드를 {@code Patch<Integer>} 로 바꾸면 셋이 구분된다.
 * <pre>
 *   {}                     → null            안 보냄, 건드리지 않는다
 *   {"capacity": 30}       → Patch(30)       30 으로 바꾼다
 *   {"capacity": null}     → Patch(null)     비운다
 * </pre>
 *
 * <h2>쓰는 쪽</h2>
 * <pre>
 *   if (request.capacity() != null) {          // 보냈는가
 *       lecture.changeCapacity(request.capacity().value());   // null 이면 비우기
 *   }
 * </pre>
 *
 * <p>⚠️ <b>모든 PATCH 필드에 쓰지 않는다.</b> 비울 수 <b>없는</b> 값(특강 이름·코드처럼
 * 반드시 있어야 하는 것)까지 상자에 담으면, 화면이 {@code null} 을 보냈을 때 무엇이
 * 맞는 동작인지 서버가 알 수 없다. <b>도메인이 "없어도 된다"고 말하는 필드에만</b> 쓴다.
 *
 * @param <T> 실제 값의 타입
 */
@JsonDeserialize(using = PatchDeserializer.class)
public final class Patch<T> {

    private final T value;

    private Patch(T value) {
        this.value = value;
    }

    public static <T> Patch<T> of(T value) {
        return new Patch<>(value);
    }

    /** 비우라는 요청({@code "field": null}). */
    public static <T> Patch<T> cleared() {
        return new Patch<>(null);
    }

    /** 보낸 값. <b>{@code null} 이면 "비워 달라"는 뜻</b>이지 "안 보냈다"가 아니다. */
    public T value() {
        return value;
    }

    public boolean isCleared() {
        return value == null;
    }

    @Override
    public String toString() {
        return value == null ? "Patch[cleared]" : "Patch[" + value + "]";
    }
}
