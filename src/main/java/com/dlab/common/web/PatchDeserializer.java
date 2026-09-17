package com.dlab.common.web;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link Patch} 역직렬화.
 *
 * <h2>핵심은 {@link #getNullValue}다</h2>
 * 필드가 <b>아예 없으면</b> Jackson 이 이 클래스를 부르지 않아 필드가 {@code null} 로 남고,
 * <b>명시적 {@code null}</b>이면 {@code getNullValue()} 가 불린다. 이 차이가 "안 보냄"과
 * "비워 달라"를 가른다 — 보통의 DTO 는 둘 다 {@code null} 이 되어 구분이 사라진다.
 *
 * <p>⚠️ {@code jackson-databind 3}({@code tools.jackson})이다. {@code com.fasterxml} 쪽
 * {@code JsonDeserializer} 를 상속하면 웹 계층에서 아예 안 걸린다(CLAUDE.md §7).
 */
public class PatchDeserializer extends ValueDeserializer<Patch<?>> {

    /** 상자 안의 실제 타입. {@code Patch<Integer>} 면 {@code Integer}. */
    private final JavaType contentType;

    public PatchDeserializer() {
        this(null);
    }

    private PatchDeserializer(JavaType contentType) {
        this.contentType = contentType;
    }

    /**
     * 필드마다 제네릭 인자가 다르므로 그때그때 만든다.
     *
     * <p>{@code contentType} 을 못 구하면(원시 {@code Patch}) 문자열로 읽는다 — 그런 선언은
     * 실수이고, 여기서 터뜨리면 원인이 요청 처리 시점에야 보인다.
     */
    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext ctxt,
                                                 BeanProperty property) {
        JavaType wrapper = property == null ? null : property.getType();
        JavaType content = wrapper == null || wrapper.containedTypeCount() == 0
                ? ctxt.constructType(String.class)
                : wrapper.containedType(0);
        return new PatchDeserializer(content);
    }

    @Override
    public Patch<?> deserialize(JsonParser p, DeserializationContext ctxt)
            throws JacksonException {
        return Patch.of(ctxt.readValue(p, contentType));
    }

    /** 명시적 {@code null} — "비워 달라". */
    @Override
    public Object getNullValue(DeserializationContext ctxt) {
        return Patch.cleared();
    }

    /**
     * 필드가 <b>아예 없을 때</b> — "건드리지 마라".
     *
     * <p>★ 이걸 안 쓰면 {@link #getNullValue} 가 대신 불려서 <b>빈 요청 {@code {}} 이
     * 모든 값을 지운다.</b> record 의 생성자 인자는 없으면 "absent" 로 채워지는데,
     * 기본 구현이 그 자리를 {@code getNullValue()} 로 메우기 때문이다.
     */
    @Override
    public Object getAbsentValue(DeserializationContext ctxt) {
        return null;
    }
}
