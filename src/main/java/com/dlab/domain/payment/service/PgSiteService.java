package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.meal.entity.MealVendor;
import com.dlab.domain.meal.repository.MealVendorRepository;
import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.entity.PgSite;
import com.dlab.domain.payment.repository.PgSiteRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 사이트코드 관리.
 *
 * <h2>왜 화면이 필요한가</h2>
 * 사이트코드·상점관리자 계정은 KCP 가 <b>가맹점마다 따로</b> 발급한다. 지점이 생기거나
 * 급식업체가 바뀌면 값이 늘어나므로 코드에 박을 수 없다 — 없으면 그 지점 결제가
 * {@code PG_SITE_NOT_FOUND} 로 막힌다.
 *
 * <h2>비밀값이 아니다</h2>
 * 인증은 서비스 인증서·개인키가 하고 그건 서버 설정에만 둔다. 사이트코드는 요청 전문에
 * 실려 나가는 식별자라 화면에서 입력·확인해도 된다.
 */
@Service
@RequiredArgsConstructor
public class PgSiteService {

    private final PgSiteRepository siteRepository;
    private final AcademyRepository academyRepository;
    private final MealVendorRepository vendorRepository;

    @Transactional(readOnly = true)
    public List<PgSite> findAll() {
        return siteRepository.findAllActive();
    }

    /**
     * 사이트코드 등록.
     *
     * <p>지점·용도·채널이 같은 행은 하나뿐이다 — 둘이면 어느 코드로 보낼지 정해지지 않고,
     * 그때 <b>급식비가 학원 코드로 결제될 수 있다.</b>
     */
    @Transactional
    public PgSite create(Long academyId, PgPurpose purpose, PgChannel channel,
                         String siteCd, String mgmtId, String displayName, Long vendorId) {
        siteRepository.findByScope(academyId, purpose, channel).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.PG_SITE_DUPLICATED,
                    "이미 「%s」(%s) 가 등록되어 있습니다. 수정으로 바꿔 주세요."
                            .formatted(existing.getDisplayName(), existing.getSiteCd()));
        });
        return siteRepository.save(new PgSite(academy(academyId), purpose, channel,
                siteCd, displayName, vendor(vendorId), mgmtId));
    }

    /** 수정. 비워 보낸 항목은 바꾸지 않는다 — 화면이 일부만 고치는 경우가 많다. */
    @Transactional
    public PgSite update(Long siteId, String siteCd, String mgmtId,
                         String displayName, Long vendorId) {
        PgSite site = require(siteId);
        site.change(siteCd, displayName, vendor(vendorId));
        if (mgmtId != null && !mgmtId.isBlank()) {
            site.changeMgmtId(mgmtId);
        }
        return site;
    }

    /**
     * 사용 여부.
     *
     * <p>내려도 지우지 않는다 — 과거 결제가 <b>어느 가맹점으로 나갔는지</b>가 정산 근거다.
     */
    @Transactional
    public PgSite changeActive(Long siteId, boolean active) {
        PgSite site = require(siteId);
        site.changeActive(active);
        return site;
    }

    private PgSite require(Long siteId) {
        return siteRepository.findById(siteId)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.PG_SITE_NOT_FOUND));
    }

    private Academy academy(Long academyId) {
        if (academyId == null) {
            return null;
        }
        return academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    private MealVendor vendor(Long vendorId) {
        if (vendorId == null) {
            return null;
        }
        return vendorRepository.findById(vendorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEAL_VENDOR_NOT_FOUND));
    }
}
