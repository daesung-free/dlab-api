package com.dlab.domain.audit;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link Audited} 엔티티의 변경을 감사 로그로 남긴다.
 *
 * <p><b>서비스마다 부르지 않는다.</b> 수동 호출로 두면 새 경로가 생길 때마다 빠뜨리고,
 * 감사 로그는 <b>나중에 붙여도 그 이전 기간을 복구할 수 없다</b>(CLAUDE.md §7).
 *
 * <h2>"무엇이 바뀌었는지"는 어디서 오나</h2>
 * JPA 라이프사이클 콜백은 <b>변경 전 값을 주지 않는다</b>. 그래서 전후값은
 * {@link AuditChangeInterceptor}가 플러시 시점에 모아 두고 여기서 꺼내 붙인다.
 * <b>비밀값은 {@link AuditMasked}로 가려지고 연관은 id만</b> 남는다 — 전 필드를 그대로
 * 뜨면 개인정보가 이력 테이블로 복제된다.
 *
 * <p>금액·점수처럼 서비스가 직접 의미를 붙여야 하는 값은 여전히
 * {@link AuditRecorder#recordChanges}로 명시 기록한다.
 *
 * <h2>기록이 실패해도 원래 작업은 살린다</h2>
 * 감사 로그를 못 남겼다고 벌점 부여가 롤백되면 <b>운영이 멈춘다</b>. 예외는 삼키고
 * 로그로 남긴다 — 대신 그 로그가 비면 안 되므로 {@code error}로 찍는다.
 */
@Slf4j
public class AuditEntityListener {

    /** 스프링 빈이 아니라 JPA 가 만드는 객체라 정적으로 받아 둔다. */
    private static AuditRecorder recorder;

    static void register(AuditRecorder value) {
        recorder = value;
    }

    @PostPersist
    public void onCreate(Object entity) {
        record(entity, AuditAction.CREATE);
    }

    @PostUpdate
    public void onUpdate(Object entity) {
        // soft delete 를 UPDATE 로 남기면 화면에서 삭제를 구분할 수 없다
        boolean deleted = entity instanceof BaseEntity base && base.isDeleted();
        record(entity, deleted ? AuditAction.DELETE : AuditAction.UPDATE);
    }

    private void record(Object entity, AuditAction action) {
        if (recorder == null) {
            return;
        }
        Audited audited = entity.getClass().getAnnotation(Audited.class);
        if (audited == null) {
            return;
        }
        try {
            recorder.record(new AuditRecorder.Entry(
                    audited.value().isBlank() ? entity.getClass().getSimpleName() : audited.value(),
                    idOf(entity),
                    action,
                    academyIdOf(entity),
                    AuditContext.actorId(),
                    AuditContext.actorName(),
                    AuditContext.actorIp(),
                    // 변경 전→후. 인터셉터가 이번 플러시에서 모아 둔 것을 꺼내 쓴다
                    AuditRecorder.serializeChanges(AuditChangeInterceptor.take(entity)),
                    Instant.now(),
                    targetEnrollmentOf(entity)));
        } catch (Exception e) {
            // 감사 로그를 못 남겼다고 원래 작업을 되돌리면 운영이 멈춘다
            log.error("감사 로그 기록 실패: {} {}", entity.getClass().getSimpleName(), action, e);
        }
    }

    /**
     * 대상 학생(등록 건). 등록 건 자신이거나 {@code enrollment} 연관을 가진 엔티티만.
     *
     * <p>★ 연관은 필드로 읽지 않고 {@code getId()}를 부른다 — 지연 로딩 프록시는 필드가 비어 있어
     * 필드로 읽으면 {@code null}이 나온다. 프록시의 {@code getId()}는 초기화 없이 id 를 준다.
     */
    private Long targetEnrollmentOf(Object entity) {
        if (entity instanceof com.dlab.domain.user.entity.StudentEnrollment self) {
            return self.getId();
        }
        return read(entity, "enrollment") instanceof com.dlab.domain.user.entity.StudentEnrollment e
                ? e.getId() : null;
    }

    private Long idOf(Object entity) {
        return (Long) read(entity, "id");
    }

    /**
     * 지점 필터용.
     *
     * <p>엔티티마다 지점을 갖는 방식이 다르다 — {@code academy} 연관, {@code academyId} 컬럼,
     * 아예 없는 것(마스터). <b>없으면 {@code null}이고 그대로 남긴다</b> — 전 지점 공통
     * 마스터를 누가 고쳤는지도 기록되어야 한다.
     */
    private Long academyIdOf(Object entity) {
        Object direct = read(entity, "academyId");
        if (direct instanceof Long id) {
            return id;
        }
        Object academy = read(entity, "academy");
        if (academy != null) {
            return (Long) read(academy, "id");
        }
        // ★ 학생에 붙는 기록(성적·사유신청 등)은 지점을 직접 안 갖고 등록 건이 갖는다.
        //   여기서 못 뽑으면 그 행은 지점이 빈 채로 남아 지점별 조회에서 성격이 흐려진다.
        //   연관은 프록시라 getId()로만 읽는다 — 필드로 읽으면 비어 있다
        if (read(entity, "enrollment")
                instanceof com.dlab.domain.user.entity.StudentEnrollment e) {
            return e.getAcademy() == null ? null : e.getAcademy().getId();
        }
        return null;
    }

    private Object read(Object target, String fieldName) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equals(fieldName)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 사람이 읽을 변경 목록. {@link AuditTrail}이 만든다.
     *
     * <h2>★★ 라벨을 「쓸 때」 박는다 — 읽을 때 풀지 않는다</h2>
     * {@code audit_log.entity_type} 에는 {@link Audited} 의 <b>한국어 이름</b>이 저장되므로
     * 읽는 시점에는 <b>어느 클래스였는지 되짚을 수 없다.</b> 쓸 때는 엔티티 클래스가 손에
     * 있으니 {@link AuditLabel} 을 그 자리에서 읽어 박는다.
     *
     * <p>★ 그래서 <b>같은 칸 이름이 영역마다 다른 뜻인 문제가 구조적으로 사라진다.</b>
     * 화면이 사전 하나로 덮던 때는 청구의 {@code status}(발행 전 → 취소)가
     * <b>「계정 상태: 승인 대기 → CANCELLED」로 보였다</b> — 칸 이름도 값도 틀린
     * <b>그럴듯한 한국어 거짓말</b>이고, 영어로 보이는 것보다 나쁘다(영어는 「모른다」가
     * 드러나는데 틀린 한국어는 담당자가 믿는다).
     *
     * @param label       필드 한국어 이름. {@link AuditLabel} 이 없으면 {@code null} —
     *                    ★ 그때는 화면이 {@code field} 를 그대로 보여준다. 숨기지 않는다:
     *                    <i>"모르는 코드는 그대로 보인다 — 숨기면 무슨 일이 있었는지
     *                    아예 사라진다."</i>
     * @param beforeLabel 값의 한국어 이름(enum {@code displayName()}). 없으면 {@code null}
     */
    public record Change(String field, String label,
                         String before, String after,
                         String beforeLabel, String afterLabel) {

        public Change(String field, String before, String after) {
            this(field, null, before, after, null, null);
        }

        /** 수동 기록용 — 라벨 없이. 가능하면 {@link #labeled} 를 쓸 것. */
        public static List<Change> of(String... fieldBeforeAfter) {
            List<Change> changes = new ArrayList<>();
            for (int i = 0; i + 2 < fieldBeforeAfter.length; i += 3) {
                changes.add(new Change(fieldBeforeAfter[i],
                        fieldBeforeAfter[i + 1], fieldBeforeAfter[i + 2]));
            }
            return changes;
        }

        /**
         * 수동 기록 한 건 — <b>라벨을 함께 넣는다.</b>
         *
         * <p>엔티티 변경이 아니라 서비스가 직접 남기는 건(계정 상태·역할·임시 비밀번호
         * 등)은 {@link AuditLabel} 이 닿지 않는다. 그런 자리는 여기로 라벨을 준다 —
         * 안 주면 화면에 {@code temporaryPassword} 가 그대로 보인다.
         */
        public static Change labeled(String field, String label,
                                     String before, String after) {
            return new Change(field, label, before, after, null, null);
        }

        /** 값 라벨까지. */
        public static Change labeled(String field, String label,
                                     String before, String after,
                                     String beforeLabel, String afterLabel) {
            return new Change(field, label, before, after, beforeLabel, afterLabel);
        }
    }
}
