package com.dlab.domain.master.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.master.entity.ScholarshipMaster;
import com.dlab.domain.master.repository.ScholarshipMasterRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 장학 종류 마스터 관리.
 *
 * <h2>이게 없으면 무엇이 깨지는가</h2>
 * 장학 취소 판정은 {@code scholarship_cancel_rule.scholarship_type}이 학생의
 * {@code scholarship.scholarship_type}과 <b>문자열이 정확히 같을 때만</b> 걸린다.
 * 둘 다 자유 입력이라 한 글자만 달라도 <b>그 학생이 판정에서 조용히 빠진다</b> —
 * 오류가 나지 않고 검토 목록에 안 뜰 뿐이다.
 *
 * <p>그래서 부여 경로가 이 마스터를 거치게 하고, 할인율도 여기서 복사한다.
 *
 * <h2>★ 전 지점 공통은 본사만</h2>
 * {@code tuition_price}·{@code billing_standard}와 같은 규약이다.
 */
@Service
@RequiredArgsConstructor
public class ScholarshipMasterService {

    private final ScholarshipMasterRepository masterRepository;
    private final AcademyRepository academyRepository;

    /** 관리자 화면 목록. 중지된 것도 나온다. */
    @Transactional(readOnly = true)
    public List<ScholarshipMaster> list(AuthPrincipal me, short year, Long academyId) {
        requireScope(me, academyId);
        return masterRepository.findAllByScope(year, academyId);
    }

    /**
     * 그 지점에서 <b>고를 수 있는</b> 장학 — 부여 화면 드롭다운.
     *
     * <p>목록과 축이 다르다. 여기는 지점 행이 공통본을 덮고 중지된 것이 빠진다.
     */
    @Transactional(readOnly = true)
    public List<ScholarshipMaster> selectable(short year, Long academyId) {
        return masterRepository.findApplicable(year, academyId);
    }

    /**
     * 부여할 장학을 찾는다. 없으면 막는다.
     *
     * <p><b>없는 코드를 통과시키면 안 된다</b> — 그 학생만 취소 판정에서 빠지고
     * 아무도 알아채지 못한다.
     */
    @Transactional(readOnly = true)
    public ScholarshipMaster requireApplicable(short year, String code, Long academyId) {
        return masterRepository.findApplicableByCode(year, normalizeCode(code), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHOLARSHIP_MASTER_NOT_FOUND,
                        "'%s' 장학이 %d년 마스터에 없습니다. 장학 종류를 먼저 등록해 주세요."
                                .formatted(code, year)));
    }

    @Transactional
    public ScholarshipMaster create(AuthPrincipal me, Long academyId, short year, String code,
                                    String name, BigDecimal discountRate, short sortOrder,
                                    String memo) {
        requireScope(me, academyId);
        String normalized = normalizeCode(code);
        validateRate(discountRate);
        masterRepository.findByCode(year, normalized, academyId).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.SCHOLARSHIP_MASTER_CODE_DUPLICATED);
        });

        return masterRepository.save(new ScholarshipMaster(resolveAcademy(academyId), year,
                normalized, name, discountRate, sortOrder, memo));
    }

    /**
     * 이름·할인율 수정.
     *
     * <p><b>{@code code}는 못 바꾼다</b> — 이미 부여된 장학과 취소 규칙이 그 값으로 이어져
     * 있어, 바꾸면 그 학생들이 규칙에서 통째로 빠진다.
     *
     * <p>할인율은 <b>앞으로 부여될 건에만</b> 적용된다. 이미 부여된 건은 부여 시점 값을
     * 들고 있다 — 소급해서 바뀌면 과거 청구의 할인 근거가 흔들린다.
     */
    @Transactional
    public ScholarshipMaster update(AuthPrincipal me, Long id, String name,
                                    BigDecimal discountRate, short sortOrder, String memo) {
        validateRate(discountRate);
        ScholarshipMaster master = load(me, id);
        master.update(name, discountRate, sortOrder, memo);
        return master;
    }

    /**
     * 부분 수정 — <b>안 보낸 값은 그대로 둔다.</b>
     *
     * <p>{@link #update}는 전체 교체라 이름만 바꾸려 해도 할인율까지 실어 보내야 하고,
     * <b>그 사이 다른 사람이 바꾼 할인율을 덮어쓴다</b>. 할인율은 앞으로 부여될 건의
     * 금액을 정하는 값이라 조용히 되돌아가면 곤란하다.
     *
     * <p>{@code code}는 여기서도 못 바꾼다 — 이미 부여된 장학과 취소 규칙이 그 값으로
     * 이어져 있다.
     */
    @Transactional
    public ScholarshipMaster patch(AuthPrincipal me, Long id, String name,
                                   BigDecimal discountRate, Short sortOrder, String memo) {
        ScholarshipMaster master = load(me, id);
        if (discountRate != null) {
            validateRate(discountRate);
        }
        master.update(name == null ? master.getName() : name,
                discountRate == null ? master.getDiscountRate() : discountRate,
                sortOrder == null ? master.getSortOrder() : sortOrder,
                memo == null ? master.getMemo() : memo);
        return master;
    }

    /** 사용/중지. 중지하면 새로 부여할 수 없고, 이미 부여된 건은 그대로 남는다. */
    @Transactional
    public ScholarshipMaster changeActive(AuthPrincipal me, Long id, boolean active) {
        ScholarshipMaster master = load(me, id);
        master.changeActive(active);
        return master;
    }

    /**
     * 삭제 — soft delete.
     *
     * <p>물리 삭제하면 <b>그 장학으로 부여된 이력이 무엇이었는지</b> 알 수 없게 된다.
     * 대부분은 삭제가 아니라 {@link #changeActive} 중지가 맞다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long id) {
        load(me, id).markDeleted();
    }

    private ScholarshipMaster load(AuthPrincipal me, Long id) {
        ScholarshipMaster master = masterRepository.findById(id)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHOLARSHIP_MASTER_NOT_FOUND));
        requireScope(me, master.isCommon() ? null : master.getAcademy().getId());
        return master;
    }

    private void validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "할인율은 0~100 사이여야 합니다.");
        }
    }

    /** 규칙과 대조되는 값이라 공백·대소문자를 정리한다 — 같은 장학이 둘로 갈리는 걸 막는다. */
    private String normalizeCode(String code) {
        String trimmed = code == null ? "" : code.strip().toUpperCase();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "장학 코드를 입력해 주세요.");
        }
        return trimmed;
    }

    private Academy resolveAcademy(Long academyId) {
        return academyId == null ? null
                : academyRepository.findById(academyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    private void requireScope(AuthPrincipal me, Long academyId) {
        if (academyId == null) {
            if (me.academyScopeFilter() != null) {
                throw new BusinessException(ErrorCode.SCHOLARSHIP_MASTER_SCOPE_FORBIDDEN);
            }
            return;
        }
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }
}
