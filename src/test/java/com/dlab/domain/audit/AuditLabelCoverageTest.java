package com.dlab.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * {@link Audited} 엔티티의 필드에 {@link AuditLabel} 이 달려 있는지 본다.
 *
 * <h2>★★ 왜 테스트로 막는가</h2>
 * 라벨 사전을 <b>화면</b>에 두던 때 계정 관련 7개만 채워져 있었고, 나머지는 서버 필드명
 * ({@code graduationYear}·{@code deleted})이 그대로 보였다. 디랩이 그걸 지적했다 —
 * <i>"변경항목과 변경 전/후 내용이 실제 사용하는 메뉴명과 항목명칭으로 보여지면"</i>.
 *
 * <p>사전을 서버로 옮기는 것만으로는 <b>같은 일이 다시 생긴다</b> — 누가 필드를 추가하고
 * 라벨을 안 달면 끝이다. 그리고 그건 <b>화면에서야 드러난다.</b> 그래서 여기서 센다.
 *
 * <p>이 레포에서 그 부류를 실제로 막아 준 것은 테스트뿐이다 —
 * {@code OpenApiSchemaNameTest} 가 스키마 이름 충돌을 하루에 세 번 잡았다.
 *
 * <h2>라벨을 달지 않아도 되는 필드</h2>
 * 감사 로그에 올라오지 않는 것들이다. {@link #SKIPPED} 가 그 목록이고, 늘릴 때는
 * <b>이유를 함께 적는다</b> — 안 그러면 「귀찮아서 넣은 것」과 구분되지 않는다.
 */
class AuditLabelCoverageTest {

    /**
     * 라벨이 필요 없는 필드.
     *
     * <ul>
     *   <li>{@code id}·연관관계 — 변경 대상이 아니거나 바뀌면 다른 행이다</li>
     *   <li>{@code createdAt}·{@code updatedAt}·{@code createdBy}·{@code version}
     *       — {@code AuditChangeInterceptor.IGNORED} 가 애초에 뺀다</li>
     *   <li>{@code year}·{@code academy}·{@code enrollment} — 소속 축이다.
     *       바뀌면 그건 「수정」이 아니라 다른 학생·다른 해의 행이다</li>
     *   <li>파생·캐시 값 — 사람이 고치는 값이 아니다</li>
     * </ul>
     */
    private static final Set<String> SKIPPED = Set.of(
            "id", "createdAt", "updatedAt", "createdBy", "updatedBy", "version",
            "deleted", "year", "academy", "enrollment", "student",
            "searchNameNormalized", "idempotencyKey", "copiedFromId",
            "transactions", "items", "subjects", "approvalRequest", "penaltyItem",
            "onboardingStatus", "classType", "uniqueCode", "source", "category");

    @Test
    @DisplayName("★★ @Audited 엔티티의 사람이 고치는 필드에 @AuditLabel 이 달려 있다")
    void auditedFieldsHaveLabels() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Audited.class));

        List<String> missing = new ArrayList<>();
        int checked = 0;

        for (var candidate : scanner.findCandidateComponents("com.dlab.domain")) {
            Class<?> type;
            try {
                type = Class.forName(((AnnotatedBeanDefinition) candidate)
                        .getMetadata().getClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()
                            || SKIPPED.contains(f.getName())) {
                        continue;
                    }
                    checked++;
                    if (!f.isAnnotationPresent(AuditLabel.class)) {
                        missing.add(type.getSimpleName() + "." + f.getName());
                    }
                }
            }
        }

        // 스캔 자체가 비면 테스트가 아무것도 안 본 것이다 — 그걸 통과로 읽지 않는다
        assertThat(checked)
                .as("@Audited 엔티티 필드를 하나도 못 찾았다 — 스캔 경로를 확인할 것")
                .isGreaterThan(20);

        assertThat(missing)
                .as("@AuditLabel 이 없다. 달거나, 감사 로그에 올라오지 않는 이유를 "
                        + "SKIPPED 에 적을 것 — 라벨이 없으면 화면에 서버 필드명이 그대로 보인다")
                .isEmpty();
    }
}
