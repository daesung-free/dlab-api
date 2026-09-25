package com.dlab.domain.audit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;

/**
 * 변경 전→후 값을 모은다. {@link AuditEntityListener}가 이걸 감사 로그에 붙인다.
 *
 * <h2>왜 리스너만으로는 안 되나</h2>
 * JPA 라이프사이클 콜백({@code @PostUpdate})은 <b>변경 전 값을 주지 않는다</b>. 그래서
 * 지금까지 감사 로그에 "누가 언제 무엇을 건드렸나"만 남고 <b>무엇이 어떻게 바뀌었는지는
 * 비어 있었다</b> — 직원 계정만 서비스에서 직접 남기고 있었다.
 *
 * <p>Hibernate 는 플러시할 때 {@link #onFlushDirty}로 <b>이전 상태와 현재 상태를 함께</b>
 * 준다. 여기서 값만 읽어 담아 두고, 잠시 뒤 같은 스레드에서 도는 {@code @PostUpdate}가
 * 꺼내 쓴다.
 *
 * <h2>★ 여기서 엔티티를 고치지 않는다</h2>
 * {@code onFlushDirty}는 {@code currentState}를 바꿔 <b>저장될 값 자체를 바꿀 수 있는</b>
 * 자리다. 읽기만 하고 항상 {@code false}(변경 없음)를 돌려준다 — 여기서 손대면 감사 로그가
 * 원본 데이터를 조용히 바꾸는 꼴이 된다.
 *
 * <h2>남기지 않는 것</h2>
 * <ul>
 *   <li>{@code @Audited}가 없는 엔티티 — 전 테이블을 켜면 로그가 원장보다 커진다</li>
 *   <li>{@code createdAt}·{@code updatedAt}처럼 <b>사람이 바꾸지 않은 값</b> —
 *       모든 수정에 따라붙어 진짜 변경을 덮는다</li>
 *   <li>{@link AuditMasked} 필드의 <b>값</b> — 바뀌었다는 사실만 남기고 {@code ***}로 쓴다.
 *       비밀번호 해시·키오스크 시크릿을 이력에 복사하면 폐기한 값이 영구히 남는다</li>
 *   <li>연관 엔티티는 <b>id만</b> — 객체를 문자열로 풀면 개인정보가 이력으로 복제되고,
 *       지연 로딩이라 여기서 건드리면 플러시 도중에 추가 조회가 나간다</li>
 * </ul>
 */
public class AuditChangeInterceptor implements Interceptor {

    /** 사람이 바꾼 값이 아니다. 전부 남기면 진짜 변경이 묻힌다. */
    private static final Set<String> IGNORED = Set.of(
            "createdAt", "updatedAt", "createdBy", "updatedBy", "version");

    /**
     * 이번 플러시에서 모은 변경. <b>엔티티 인스턴스</b>가 열쇠다 —
     * 같은 종류가 여러 건 바뀌어도 서로 섞이지 않는다.
     */
    private static final ThreadLocal<Map<Object, List<AuditEntityListener.Change>>> PENDING =
            ThreadLocal.withInitial(IdentityHashMap::new);

    @Override
    public boolean onFlushDirty(Object entity, Object id, Object[] currentState,
                                Object[] previousState, String[] propertyNames, Type[] types) {
        if (previousState == null || entity.getClass().getAnnotation(Audited.class) == null) {
            return false;
        }

        Map<String, Field> fields = fieldsOf(entity.getClass());
        List<AuditEntityListener.Change> changes = new ArrayList<>();
        for (int i = 0; i < propertyNames.length; i++) {
            String name = propertyNames[i];
            if (IGNORED.contains(name)) {
                continue;
            }
            Object before = previousState[i];
            Object after = currentState[i];
            if (java.util.Objects.equals(before, after)) {
                continue;
            }
            boolean masked = fields.containsKey(name)
                    && fields.get(name).isAnnotationPresent(AuditMasked.class);
            changes.add(new AuditEntityListener.Change(name,
                    masked ? "***" : format(before),
                    masked ? "***" : format(after)));
        }

        if (!changes.isEmpty()) {
            PENDING.get().put(entity, changes);
        }
        return false;   // ★ 저장될 값은 건드리지 않는다
    }

    /** {@code @PostUpdate}가 꺼내 간다. 한 번 꺼내면 지워 다음 수정에 섞이지 않는다. */
    static List<AuditEntityListener.Change> take(Object entity) {
        return PENDING.get().remove(entity);
    }

    /**
     * 요청이 끝나면 비운다.
     *
     * <p>스레드 풀이라 안 비우면 <b>다음 요청이 남의 변경 내역을 물려받는다</b>.
     * 정상 경로에서는 {@link #take}가 꺼내 가지만, 저장이 롤백되면 꺼내 갈 사람이 없다.
     */
    public static void clear() {
        PENDING.remove();
    }

    /**
     * 값 표기.
     *
     * <p>연관은 <b>{@code getId()}만</b> 부른다 — 프록시를 문자열로 풀면 초기화 조회가
     * 플러시 도중에 나가고, 개인정보까지 이력으로 복제된다.
     */
    private static String format(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value.getClass().getName().startsWith("com.dlab.domain")) {
            Object id = idOf(value);
            return id == null ? value.getClass().getSimpleName() : String.valueOf(id);
        }
        String text = String.valueOf(value);
        // 메모·본문이 통째로 들어오면 목록 화면이 읽히지 않는다
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    private static Object idOf(Object value) {
        try {
            var getter = value.getClass().getMethod("getId");
            return getter.invoke(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Field> fieldsOf(Class<?> type) {
        Map<String, Field> result = new HashMap<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                result.putIfAbsent(f.getName(), f);
            }
        }
        return result;
    }
}
