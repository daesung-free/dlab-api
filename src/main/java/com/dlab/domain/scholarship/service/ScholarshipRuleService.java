package com.dlab.domain.scholarship.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.scholarship.entity.CancelRuleType;
import com.dlab.domain.scholarship.entity.ElectiveMode;
import com.dlab.domain.scholarship.entity.ScholarshipCancelRule;
import com.dlab.domain.scholarship.repository.ScholarshipCancelRuleRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 장학 취소 기준 관리 (0820 규정 · 0826 답변서).
 *
 * <h2>★ 기준을 코드가 아니라 여기서 고친다</h2>
 * 답변서가 <i>"해마다 탐구 1과목만 반영하기도 하고, 기준도 전년도 난이도에 따라
 * 변경된다"</i>고 명시했다. 이 화면이 없으면 <b>매년 마이그레이션을 새로 써야 하고</b>,
 * 그때마다 개발자에게 물어야 한다.
 *
 * <h2>★ 한 요건이 여러 행일 수 있다</h2>
 * {@code alternativeGroup}이 다르면 <b>OR 대안</b>이라 <i>"(국+수+탐) 또는 (국+수+영)"</i>이
 * 두 행으로 들어간다. 화면은 이걸 <b>한 묶음으로 보여줘야</b> 한다 — 따로 보이면
 * 담당자가 대안을 하나만 지우고 규정이 조용히 바뀐다.
 *
 * <h2>★ 켜는 것은 별도 동작이다</h2>
 * 만들자마자 돌면 <b>검증 전 기준이 학생 장학을 검토 대상으로 올린다.</b>
 * {@code penalty_rule}과 같은 판단이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScholarshipRuleService {

    private final ScholarshipCancelRuleRepository ruleRepository;
    private final AcademyRepository academyRepository;

    /** 꺼진 것까지 전부. 화면이 on/off 토글을 그린다. */
    public List<ScholarshipCancelRule> findAll(AuthPrincipal me, Long academyId, short year) {
        verifyScope(me, academyId);
        return ruleRepository.findAllByScope(year, academyId);
    }

    /**
     * 기준 생성. <b>항상 꺼진 채로 만들어진다.</b>
     *
     * @param academyId {@code null}이면 전 지점 공통 — <b>본사만</b> 만들 수 있다
     */
    @Transactional
    public ScholarshipCancelRule create(AuthPrincipal me, Long academyId, short year,
                                        CancelRuleType ruleType, int threshold,
                                        String subjectCodes, String scholarshipType,
                                        short alternativeGroup, String examCodes,
                                        ElectiveMode electiveMode, String extraSubjectCode,
                                        Short extraMaxGrade) {
        verifyScope(me, academyId);
        verify(ruleType, threshold, subjectCodes, examCodes, electiveMode,
                extraSubjectCode, extraMaxGrade);

        Academy academy = academyId == null ? null : requireAcademy(academyId);
        ScholarshipCancelRule rule = new ScholarshipCancelRule(
                academy, year, ruleType, threshold, subjectCodes);
        rule.updateGradeSumOptions(trimToNull(scholarshipType), alternativeGroup,
                trimToNull(examCodes), electiveMode, trimToNull(extraSubjectCode), extraMaxGrade);

        log.info("장학 취소 기준 생성: academyId={}, year={}, {}/{}#{} (꺼진 상태)",
                academyId, year, ruleType, scholarshipType, alternativeGroup);
        return ruleRepository.save(rule);
    }

    /**
     * 기준 수정.
     *
     * <p>⚠️ <b>이미 올라온 검토 대상은 안 바뀐다</b> — {@code scholarship_review}가 판정
     * 당시 임계값을 복사해 두기 때문이다. 기준을 고쳐도 <b>"그때 왜 걸렸는지"</b>는 남는다.
     */
    @Transactional
    public ScholarshipCancelRule update(AuthPrincipal me, Long ruleId, int threshold,
                                        String subjectCodes, String scholarshipType,
                                        short alternativeGroup, String examCodes,
                                        ElectiveMode electiveMode, String extraSubjectCode,
                                        Short extraMaxGrade) {
        ScholarshipCancelRule rule = require(me, ruleId);
        verify(rule.getRuleType(), threshold, subjectCodes, examCodes, electiveMode,
                extraSubjectCode, extraMaxGrade);

        rule.update(threshold, subjectCodes);
        rule.updateGradeSumOptions(trimToNull(scholarshipType), alternativeGroup,
                trimToNull(examCodes), electiveMode, trimToNull(extraSubjectCode), extraMaxGrade);
        return rule;
    }

    /**
     * 켜고 끄기.
     *
     * <p><b>끄면 판정에서 아예 빠진다.</b> 이미 올라온 검토 대상은 남는다 —
     * 지우면 담당자가 처리 중이던 건이 사라진다.
     */
    @Transactional
    public ScholarshipCancelRule toggleActive(AuthPrincipal me, Long ruleId, boolean active) {
        ScholarshipCancelRule rule = require(me, ruleId);
        rule.changeActive(active);
        log.info("장학 취소 기준 {}: ruleId={}, {}", active ? "켬" : "끔", ruleId, rule.getRuleType());
        return rule;
    }

    /** 삭제(soft). 과거 검토 이력이 어떤 기준으로 걸렸는지 추적할 수 있어야 한다. */
    @Transactional
    public void delete(AuthPrincipal me, Long ruleId) {
        require(me, ruleId).markDeleted();
    }

    // ─────────────────────────────────────────── 검증

    /**
     * 값이 서로 앞뒤가 맞는가.
     *
     * <p>화면에서 걸러지길 기대하지 않는다 — <b>기준이 조용히 어긋나면 학생 장학이
     * 잘못 취소된다.</b>
     */
    private void verify(CancelRuleType ruleType, int threshold, String subjectCodes,
                        String examCodes, ElectiveMode electiveMode,
                        String extraSubjectCode, Short extraMaxGrade) {
        if (threshold < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "임계값이 올바르지 않습니다.");
        }
        if (extraMaxGrade != null && (extraMaxGrade < 1 || extraMaxGrade > 9)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "등급은 1~9입니다.");
        }
        // 과목만 있고 등급 상한이 없으면(또는 반대면) 조건이 조용히 무시된다
        boolean hasSubject = trimToNull(extraSubjectCode) != null;
        if (hasSubject != (extraMaxGrade != null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "추가 조건은 과목과 등급 상한을 함께 지정해야 합니다.");
        }
        if (examCodes != null && !examCodes.isBlank()) {
            for (String code : examCodes.split(",")) {
                try {
                    ExamCode.valueOf(code.trim());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "알 수 없는 회차입니다: " + code.trim());
                }
            }
        }
        if (ruleType != CancelRuleType.EXAM_GRADE_SUM) {
            return;
        }
        // ★ 등급합인데 대상 과목이 없으면 판정 자체가 안 돈다. 만들어두고 켰다가
        //   "왜 아무도 안 걸리지"로 헤매는 것보다 여기서 막는 게 낫다
        if (trimToNull(subjectCodes) == null && electiveMode == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "등급합 기준은 대상 과목이나 탐구 집계 방식이 있어야 합니다.");
        }
    }

    private ScholarshipCancelRule require(AuthPrincipal me, Long ruleId) {
        ScholarshipCancelRule rule = ruleRepository.findById(ruleId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHOLARSHIP_RULE_NOT_FOUND));
        verifyScope(me, rule.isCommon() ? null : rule.getAcademy().getId());
        return rule;
    }

    /**
     * ★ 전 지점 공통 기준은 <b>본사만</b> 만지고, 지점은 자기 지점 것만 만진다.
     *
     * <p>휴일 등록과 같은 규약이다(§7) — 지점 관리자가 공통 기준을 고치면
     * <b>다른 지점 학생 장학까지 같이 흔들린다.</b>
     */
    private void verifyScope(AuthPrincipal me, Long academyId) {
        if (academyId == null) {
            if (!me.allAcademy()) {
                throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED,
                        "전 지점 공통 기준은 본사만 관리할 수 있습니다.");
            }
            return;
        }
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }

    private Academy requireAcademy(Long academyId) {
        return academyRepository.findById(academyId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
