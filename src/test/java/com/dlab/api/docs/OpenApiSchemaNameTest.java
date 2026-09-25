package com.dlab.api.docs;

import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 스펙의 스키마 이름 충돌.
 *
 * <p>springdoc 은 스키마 이름을 <b>클래스 단순 이름</b>으로 짓는다. 서로 다른 레코드가 둘 다
 * {@code CreateRequest} 면 스펙에는 하나만 남고, 다른 쪽 엔드포인트가 <b>엉뚱한 모양을 가리킨다</b> —
 * 에러 없이 화면만 틀어진다({@code POST /billings} 가 결제 요청 스키마를 가리키던 원인).
 *
 * <p>충돌하면 한쪽에 {@code @Schema(name = "...")} 를 붙인다.
 */
@SpringBootTest
class OpenApiSchemaNameTest {

    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    @Test
    @DisplayName("★ 요청·응답 타입의 스키마 이름이 겹치지 않는다")
    void schemaNamesAreUnique() {
        Map<String, Set<String>> byName = new TreeMap<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Type> queue = new ArrayDeque<>();

        handlerMapping.getHandlerMethods().values().forEach(handler -> {
            Method m = handler.getMethod();
            if (!m.getDeclaringClass().getName().startsWith("com.dlab")) {
                return;
            }
            queue.add(m.getGenericReturnType());
            for (var p : handler.getMethodParameters()) {
                if (p.hasParameterAnnotation(RequestBody.class)) {
                    queue.add(p.getGenericParameterType());
                }
            }
        });

        while (!queue.isEmpty()) {
            Type type = queue.poll();
            if (type instanceof ParameterizedType pt) {
                queue.add(pt.getRawType());
                queue.addAll(List.of(pt.getActualTypeArguments()));
                continue;
            }
            if (type instanceof GenericArrayType at) {
                queue.add(at.getGenericComponentType());
                continue;
            }
            if (!(type instanceof Class<?> cls) || cls.isPrimitive()) {
                continue;
            }
            if (cls.isArray()) {
                queue.add(cls.getComponentType());
                continue;
            }
            if (!cls.getName().startsWith("com.dlab") || !seen.add(cls)) {
                continue;
            }
            byName.computeIfAbsent(schemaName(cls), k -> new TreeSet<>()).add(cls.getName());
            if (cls.isRecord()) {
                for (RecordComponent rc : cls.getRecordComponents()) {
                    queue.add(rc.getGenericType());
                }
            }
        }

        Map<String, Set<String>> clashes = new TreeMap<>();
        byName.forEach((name, classes) -> {
            if (classes.size() > 1) {
                clashes.put(name, classes);
            }
        });
        assertThat(clashes).as("스키마 이름 충돌 — 한쪽에 @Schema(name) 을 붙일 것").isEmpty();
    }

    private static String schemaName(Class<?> cls) {
        Schema schema = AnnotatedElementUtils.findMergedAnnotation(cls, Schema.class);
        return schema != null && !schema.name().isBlank() ? schema.name() : cls.getSimpleName();
    }
}
