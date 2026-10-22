package com.dlab.domain.notification.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수동 발송 실행 1건.
 *
 * <h2>★★ 이 테이블의 존재 이유는 {@code UNIQUE (preview_id)} 하나다</h2>
 * 버튼이 두 번 눌리거나 새로고침되면 <b>같은 학부모에게 같은 알림톡이 두 번</b> 간다.
 * 알림톡은 <b>건당 과금</b>이라 그대로 돈이 된다.
 *
 * <p>★ <b>코드로만 막으면 막히지 않는다.</b> 사전 조회("이미 보냈나")는 다중 인스턴스
 * 동시 요청을 막지 못한다 — 둘 다 "안 보냈다"를 보고 둘 다 보낸다. 같은 판단으로
 * 상벌점에 이미 {@code uq_penalty_point_idempotency} 가 있다(§7).
 *
 * <p>★ 미리보기에 소비 플래그를 두는 방식을 고르지 않았다. 플래그는 UPDATE 라 조건을
 * 한 번 빠뜨리면 조용히 두 번 통과하는데, 행 INSERT 는 <b>반드시</b> 제약에 걸린다.
 * 덤으로 「누가 언제 몇 명에게 보냈나」가 남는다({@code created_by} 가 자동으로 채워진다).
 */
@Getter
@Entity
@Table(name = "notification_manual_send")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationManualSend extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ★★ 1회성의 전부. {@code UNIQUE} 다 — 조건을 지우지 말 것. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preview_id", nullable = false, unique = true)
    private NotificationManualPreview preview;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    /** 보내려고 시도한 건수. */
    @Column(name = "requested", nullable = false)
    private int requested;

    /**
     * 실제로 안 나간 건수.
     *
     * <p>문구 미확정·심사 미통과·템플릿 코드 미등록으로 걸러진 것이 여기 들어온다 —
     * 발송 경로의 게이트를 우회하지 않으므로 <b>전부 {@code SKIPPED} 이력으로 남는다.</b>
     */
    @Column(name = "skipped", nullable = false)
    private int skipped;

    public NotificationManualSend(NotificationManualPreview preview, Academy academy,
                                  short year) {
        this.preview = preview;
        this.academy = academy;
        this.year = year;
    }

    public void recordResult(int requested, int skipped) {
        this.requested = requested;
        this.skipped = skipped;
    }
}
