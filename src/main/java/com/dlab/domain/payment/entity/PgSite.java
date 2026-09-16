package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.meal.entity.MealVendor;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * KCP 사이트코드.
 *
 * <p>발급이 <b>사업자 × 채널</b>로 나뉜다 — 학원 바이링크·학원 단말기·급식업체 바이링크·
 * 급식업체 단말기. 한 칸에 뭉쳐 두면 급식비를 학원 코드로 결제하게 되고, 그 돈은 업체에게
 * 가지 않는다.
 *
 * <p>⚠️ <b>사이트코드는 비밀값이 아니다.</b> 인증은 서비스 인증서·개인키가 하고 그건
 * 설정(파일)으로 둔다. 그래서 이 값은 관리자 화면에서 입력·확인할 수 있다.
 *
 * @param academy 비우면 전 지점 공용. 지점별 코드를 받으면 행을 추가하고, 조회는
 *                <b>지점별이 공용보다 우선</b>이다
 */
@Getter
@Entity
@Table(name = "pg_site")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgSite extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgChannel channel;

    @Column(name = "site_cd", nullable = false, length = 10)
    private String siteCd;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** 급식업체 명의면 그 업체. 학원 명의면 비어 있다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private MealVendor vendor;

    /**
     * KCP 상점관리자 계정({@code reg_id}).
     *
     * <p>★ <b>결제 URL 생성의 필수 항목이다.</b> 주문번호를 넣으면 {@code S005} 로 거절된다 —
     * 실제로 그렇게 보냈다가 KCP 테스트 서버가 잡아냈다.
     */
    @Column(name = "mgmt_id", length = 20)
    private String mgmtId;

    @Column(nullable = false)
    private boolean active = true;

    public PgSite(Academy academy, PgPurpose purpose, PgChannel channel,
                  String siteCd, String displayName, MealVendor vendor) {
        this(academy, purpose, channel, siteCd, displayName, vendor, null);
    }

    public PgSite(Academy academy, PgPurpose purpose, PgChannel channel,
                  String siteCd, String displayName, MealVendor vendor, String mgmtId) {
        this.mgmtId = mgmtId;
        this.academy = academy;
        this.purpose = purpose;
        this.channel = channel;
        this.siteCd = siteCd;
        this.displayName = displayName;
        this.vendor = vendor;
        this.active = true;
    }

    public void changeMgmtId(String mgmtId) {
        this.mgmtId = mgmtId;
    }

    public void change(String siteCd, String displayName, MealVendor vendor) {
        if (siteCd != null && !siteCd.isBlank()) {
            this.siteCd = siteCd;
        }
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
        this.vendor = vendor;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    /** 전 지점 공용인가. 지점별 행이 있으면 그쪽이 우선한다 */
    public boolean isShared() {
        return academy == null;
    }
}
