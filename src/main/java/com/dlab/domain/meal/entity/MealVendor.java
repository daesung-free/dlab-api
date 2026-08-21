package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 급식업체 (0820 규정).
 *
 * <h2>★ 지점에 속하지 않는다</h2>
 * 디온푸드 한 곳이 7개 지점을 담당한다. 업체를 지점마다 복제하면 연락처를 고칠 때
 * 7군데를 고쳐야 하고, <b>한 곳만 빠뜨려도 알 수 없다.</b>
 * 지점과의 연결과 단가는 {@code meal_policy}가 든다.
 *
 * <h2>연락처는 자리만 만들어 뒀다</h2>
 * 클라이언트가 <i>"환불정보를 업체에 자동 발송"</i>을 요청했지만(지금은 단톡방 수기)
 * 발송 수단이 미확정이다. ⚠️ <b>환불계좌는 개인정보라 제3자 제공 동의 범위 확인이
 * 먼저</b>다 — 발송 구현은 그 뒤다.
 */
@Getter
@Entity
@Table(name = "meal_vendor")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealVendor extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "contact_name", length = 30)
    private String contactName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email", length = 120)
    private String contactEmail;

    @Column(nullable = false)
    private boolean active = true;

    public MealVendor(String name) {
        this.name = name;
        this.active = true;
    }

    /** {@code null}은 "변경하지 않음"이다 — 빈 값으로 덮어쓰지 않는다. */
    public void updateContact(String contactName, String contactPhone, String contactEmail) {
        if (contactName != null) {
            this.contactName = contactName;
        }
        if (contactPhone != null) {
            this.contactPhone = contactPhone;
        }
        if (contactEmail != null) {
            this.contactEmail = contactEmail;
        }
    }

    public void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    /**
     * 업체 교체 시 이전 업체를 내린다. <b>지우지 않는다</b> —
     * 과거 주문이 어느 업체 것이었는지가 정산 근거다.
     */
    public void deactivate() {
        this.active = false;
    }
}
