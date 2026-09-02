package com.dlab.domain.meal.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.meal.entity.MealPolicy;
import com.dlab.domain.meal.entity.MealVendor;
import com.dlab.domain.meal.repository.MealPolicyRepository;
import com.dlab.domain.meal.repository.MealVendorRepository;
import com.dlab.domain.user.repository.AcademyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 급식업체·단가 관리 (F-4.5 · 0820 규정).
 *
 * <h2>업체는 전역, 연결은 지점별</h2>
 * 디온푸드 한 곳이 7개 지점을 담당한다. 업체 자체는 본사가 관리하고,
 * <b>"우리 지점은 어느 업체이고 얼마인가"</b>는 지점 설정({@code meal_policy})이 든다.
 *
 * <h2>★ 단가에 기본값을 두지 않는다</h2>
 * 지점마다 다르고(대구만 8,000원) 바뀔 수 있어서, 임의값을 쓰면 <b>틀린 금액이 주문에
 * 스냅샷으로 박힌다.</b> 미등록이면 신청은 되되 청구를 만들 수 없는 상태로 둔다 —
 * 업체 연결 전에도 운영은 돌아야 하기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealVendorService {

    private final MealVendorRepository vendorRepository;
    private final MealPolicyRepository policyRepository;
    private final AcademyRepository academyRepository;

    @Transactional(readOnly = true)
    public List<MealVendor> findAll() {
        return vendorRepository.findAllActive();
    }

    /**
     * 업체 등록. 이름이 겹치면 거부한다 — 같은 업체가 두 벌이면 연락처를 고칠 때
     * 어느 쪽이 진짜인지 알 수 없다.
     */
    @Transactional
    public MealVendor create(String name, String contactName, String contactPhone,
                             String contactEmail) {
        if (vendorRepository.existsByNameAndDeletedFalse(name)) {
            throw new BusinessException(ErrorCode.MEAL_VENDOR_DUPLICATED);
        }
        MealVendor vendor = vendorRepository.save(new MealVendor(name));
        vendor.updateContact(contactName, contactPhone, contactEmail);
        return vendor;
    }

    /** 연락처 수정. {@code null}은 "변경하지 않음"이다. */
    @Transactional
    public MealVendor update(Long vendorId, String name, String contactName,
                             String contactPhone, String contactEmail) {
        MealVendor vendor = requireVendor(vendorId);
        if (name != null && !name.equals(vendor.getName())
                && vendorRepository.existsByNameAndDeletedFalse(name)) {
            throw new BusinessException(ErrorCode.MEAL_VENDOR_DUPLICATED);
        }
        vendor.rename(name);
        vendor.updateContact(contactName, contactPhone, contactEmail);
        return vendor;
    }

    /**
     * 업체 내리기.
     *
     * <p><b>지우지 않는다</b> — 과거 주문이 어느 업체 것이었는지가 정산 근거다.
     * 지점에 연결된 채로 내리면 그 지점 급식이 멈추므로 먼저 확인한다.
     */
    @Transactional
    public void deactivate(Long vendorId) {
        MealVendor vendor = requireVendor(vendorId);
        vendor.deactivate();
        log.info("급식업체 비활성: vendorId={}, 이름={}", vendorId, vendor.getName());
    }

    /**
     * 지점에 업체·단가 연결.
     *
     * <p>설정이 없는 지점이면 만들어서 연결한다 — 마감일수는 기본값으로 시작한다.
     */
    @Transactional
    public MealPolicy assignToAcademy(AuthPrincipal me, Long academyId, short year,
                                      Long vendorId, int unitPrice) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (unitPrice <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "단가는 1원 이상이어야 합니다.");
        }
        MealVendor vendor = requireVendor(vendorId);

        MealPolicy policy = policyRepository.find(academyId, year)
                .orElseGet(() -> policyRepository.save(new MealPolicy(
                        academyRepository.findById(academyId)
                                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND)),
                        year, MealPolicy.DEFAULT_DEADLINE_DAYS)));

        policy.assignVendor(vendor, unitPrice);
        log.info("급식업체 연결: academyId={}, year={}, 업체={}, 단가={}",
                academyId, year, vendor.getName(), unitPrice);
        return policy;
    }

    @Transactional(readOnly = true)
    public MealPolicy policyOf(AuthPrincipal me, Long academyId, short year) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return policyRepository.find(academyId, year)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEAL_POLICY_NOT_FOUND));
    }

    private MealVendor requireVendor(Long vendorId) {
        return vendorRepository.findById(vendorId)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEAL_VENDOR_NOT_FOUND));
    }
}
