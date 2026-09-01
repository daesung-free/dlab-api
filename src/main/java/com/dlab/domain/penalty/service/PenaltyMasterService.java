package com.dlab.domain.penalty.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.repository.PenaltyItemRepository;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import com.dlab.domain.penalty.repository.PenaltyRuleRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상벌점 항목·규칙 관리 (F-4.1-3, I-5).
 *
 * <p>클라이언트가 <b>"점수 생성 페이지에서 직접 입력"</b>으로 답을 줬다. 항목(무엇에 몇 점)과
 * 규칙(어떤 상황에 그 항목을 자동 부여)을 관리자가 화면에서 만든다.
 *
 * <h2>★ 항목만 만들면 자동부여는 안 돈다</h2>
 * {@link PenaltyRuleEngine}이 읽는 건 <b>규칙</b>이다. 항목만 있으면 수기 부여만 되고
 * 지각·결석은 여전히 아무 일도 일어나지 않는다.
 *
 * <h2>★ 규칙은 기본이 꺼짐이다</h2>
 * 만들자마자 돌면 검증 전 규칙이 전교생에게 벌점을 뿌린다. 명시적으로 켜야 동작한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PenaltyMasterService {

    private final PenaltyItemRepository itemRepository;
    private final PenaltyRuleRepository ruleRepository;
    private final PenaltyPointRepository pointRepository;
    private final AcademyRepository academyRepository;

    // ── 항목 ──────────────────────────────────────────────────

    public List<PenaltyItem> items(AuthPrincipal me, Long academyId, short year) {
        return itemRepository.findByAcademyIdAndYearAndDeletedFalseOrderByItemNameAsc(
                scope(me, academyId), year);
    }

    /**
     * 항목 생성.
     *
     * <p>부호는 엔티티가 구분에 맞춰 맞춘다 — 화면이 벌점을 양수로 보내도 음수로 저장된다.
     * 통계가 부호로 상점·벌점을 가르기 때문이다.
     */
    @Transactional
    public PenaltyItem createItem(AuthPrincipal me, Long requestedAcademyId, short year,
                                  String itemName, int pointValue, PenaltyCategory category) {
        Long academyId = scope(me, requestedAcademyId);
        verifyItem(itemName, pointValue);

        itemRepository.findByAcademyIdAndYearAndItemNameAndDeletedFalse(
                        academyId, year, itemName.trim())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "같은 이름의 항목이 있습니다.");
                });

        return itemRepository.save(new PenaltyItem(
                requireAcademy(academyId), year, itemName.trim(), pointValue, category));
    }

    /**
     * 항목 수정.
     *
     * <p><b>이미 부여된 상벌점은 안 바뀐다</b> — 부여 시점 점수를 복사해두기 때문이다.
     * 항목을 3점에서 5점으로 바꿔도 지난달에 받은 학생은 3점 그대로다.
     */
    @Transactional
    public PenaltyItem updateItem(AuthPrincipal me, Long itemId, String itemName,
                                  int pointValue, PenaltyCategory category) {
        verifyItem(itemName, pointValue);
        PenaltyItem item = requireItem(me, itemId);
        item.change(itemName.trim(), pointValue, category);
        return item;
    }

    /**
     * 항목 삭제.
     *
     * <p><b>규칙이 걸려 있으면 막는다.</b> 지우면 그 규칙이 존재하지 않는 항목을 가리켜
     * 자동부여가 터지거나 조용히 멈춘다 — 규칙을 먼저 지우게 한다.
     *
     * <p>이미 부여된 이력은 지우지 않는다. soft delete라 과거 이력의 항목명은 그대로 보인다.
     */
    @Transactional
    public void deleteItem(AuthPrincipal me, Long itemId) {
        PenaltyItem item = requireItem(me, itemId);

        if (!ruleRepository.findByPenaltyItemIdAndDeletedFalse(itemId).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이 항목을 쓰는 자동부여 규칙이 있습니다. 규칙을 먼저 삭제해 주세요.");
        }
        item.markDeleted();
    }

    // ── 규칙 ──────────────────────────────────────────────────

    /** 꺼진 규칙까지 전부. 화면이 on/off 토글을 그린다. */
    public List<PenaltyRule> rules(AuthPrincipal me, Long academyId, short year) {
        return ruleRepository.findAllOfYear(scope(me, academyId), year);
    }

    /**
     * 규칙 생성. <b>기본은 꺼짐이다.</b>
     *
     * @param triggerCondition 트리거 조건값. 출결이면 {@code att_gn}(A=지각 등),
     *                         루틴이면 결과 상태, 정기일정이면 {@code NOT_RECOGNIZED}.
     *                         <b>필수다</b> — 비워두면 그 트리거의 모든 상황에 벌점이 나간다
     */
    @Transactional
    public PenaltyRule createRule(AuthPrincipal me, Long requestedAcademyId, short year,
                                  PenaltyTriggerType triggerType,
                                  String triggerCondition, Long itemId) {
        Long academyId = scope(me, requestedAcademyId);
        verifyCondition(triggerCondition);
        PenaltyItem item = requireItem(me, itemId);

        if (item.getYear() != year) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "규칙과 항목의 연도가 다릅니다.");
        }
        return ruleRepository.save(new PenaltyRule(
                requireAcademy(academyId), year, triggerType, triggerCondition.trim(), item));
    }

    @Transactional
    public PenaltyRule updateRule(AuthPrincipal me, Long ruleId, PenaltyTriggerType triggerType,
                                  String triggerCondition, Long itemId) {
        verifyCondition(triggerCondition);
        PenaltyRule rule = requireRule(me, ruleId);
        PenaltyItem item = requireItem(me, itemId);
        rule.change(triggerType, triggerCondition.trim(), item);
        return rule;
    }

    /**
     * 규칙 on/off.
     *
     * <p><b>끄기만 하고 지우지 않는 이유</b>가 있다 — 규칙을 지우면 그 규칙으로 부여된
     * 과거 상벌점의 근거가 사라진다. 운영에서 "잠깐 멈춤"이 필요한 경우가 대부분이다.
     */
    @Transactional
    public PenaltyRule toggleRule(AuthPrincipal me, Long ruleId, boolean active) {
        PenaltyRule rule = requireRule(me, ruleId);
        if (active) {
            rule.activate();
        } else {
            rule.deactivate();
        }
        return rule;
    }

    @Transactional
    public void deleteRule(AuthPrincipal me, Long ruleId) {
        requireRule(me, ruleId).markDeleted();
    }

    // ─────────────────────────────────────────────────────────

    /** 점수 0점은 막는다 — 부여해도 합계가 안 바뀌어 아무 효과가 없다. */
    private void verifyItem(String itemName, int pointValue) {
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "항목명을 입력해 주세요.");
        }
        if (pointValue == 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "점수는 0점일 수 없습니다.");
        }
    }

    private PenaltyItem requireItem(AuthPrincipal me, Long itemId) {
        PenaltyItem item = itemRepository.findById(itemId)
                .filter(i -> !i.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "상벌점 항목을 찾을 수 없습니다."));

        if (!me.canAccessAcademy(item.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return item;
    }

    private PenaltyRule requireRule(AuthPrincipal me, Long ruleId) {
        PenaltyRule rule = ruleRepository.findById(ruleId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "자동부여 규칙을 찾을 수 없습니다."));

        if (!me.canAccessAcademy(rule.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return rule;
    }

    private Academy requireAcademy(Long academyId) {
        return academyRepository.findById(academyId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    /**
     * 지점 스코프.
     *
     * <p><b>전 지점 권한자도 지점을 골라야 한다</b> — 항목·규칙은 지점마다 다르고,
     * "전 지점에 한 번에" 만드는 기능은 요구된 적이 없다. 필요하면 전년도 복사처럼
     * 별도 기능으로 두는 게 맞다.
     */
    private Long scope(AuthPrincipal me, Long requested) {
        return me.requireAcademyScope(requested);
    }

    /**
     * 조건은 필수다.
     *
     * <p>비워두면 그 트리거의 <b>모든 상황</b>에 걸린다 — 출결 규칙 하나가 등원·하원·외출까지
     * 전부 벌점 대상으로 만든다. 스키마도 {@code NOT NULL}이다.
     */
    private void verifyCondition(String triggerCondition) {
        if (triggerCondition == null || triggerCondition.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "트리거 조건을 입력해 주세요.");
        }
    }
}
