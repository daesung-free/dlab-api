package com.dlab.api.scholarship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.scholarship.entity.*;
import com.dlab.domain.scholarship.service.ScholarshipRuleService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장학 취소 기준 관리 (0826 답변서).
 *
 * <p>지키려는 것은 넷 — <b>만들면 꺼져 있다</b>, <b>공통 기준은 본사만 만진다</b>,
 * <b>앞뒤 안 맞는 값은 막는다</b>, <b>끄면 판정에서 빠지되 검토 대상은 남는다</b>.
 */
@SpringBootTest
@Transactional
class AdminScholarshipApiTest {

    @Autowired ScholarshipRuleService ruleService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2096;

    Academy academy;
    AuthPrincipal headquarters;
    AuthPrincipal branchAdmin;

    @BeforeEach
    void setUp() {
        academy = new Academy("SR1", "기준테스트", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();

        headquarters = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
        branchAdmin = AuthPrincipal.of(2L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private ScholarshipCancelRule create(AuthPrincipal me, Long academyId, String scholarshipType,
                                         int group, int threshold, String subjects,
                                         String examCodes, ElectiveMode elective,
                                         String extraSubject, Short extraMaxGrade) {
        return ruleService.create(me, academyId, YEAR, CancelRuleType.EXAM_GRADE_SUM, threshold,
                subjects, scholarshipType, (short) group, examCodes, elective,
                extraSubject, extraMaxGrade);
    }

    // ─────────────────────────────────────────── 생성

    @Test
    @DisplayName("★ 만들면 꺼진 채로 만들어진다 — 검증 전 기준이 학생 장학을 검토 대상으로 올리면 안 된다")
    void createdInactive() {
        var rule = create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH",
                "JUNE,SEPT", ElectiveMode.SINGLE, "ENGLISH", (short) 2);

        assertThat(rule.isActive()).isFalse();
        assertThat(rule.isCommon()).isTrue();
        assertThat(rule.getElectiveMode()).isEqualTo(ElectiveMode.SINGLE);
        assertThat(rule.hasExtraCondition()).isTrue();
    }

    @Test
    @DisplayName("같은 요건에 대안을 여러 줄 넣을 수 있다 — (국+수+탐) 또는 (국+수+영)")
    void alternativesCoexist() {
        create(headquarters, null, "CSAT_100", 1, 4, "KOREAN,MATH", "CSAT",
                ElectiveMode.AVG2, null, null);
        create(headquarters, null, "CSAT_100", 2, 4, "KOREAN,MATH,ENGLISH", "CSAT",
                null, null, null);
        em.flush();

        assertThat(ruleService.findAll(headquarters, null, YEAR))
                .filteredOn(r -> "CSAT_100".equals(r.getScholarshipType()))
                .hasSize(2)
                .extracting(ScholarshipCancelRule::getAlternativeGroup)
                .containsExactlyInAnyOrder((short) 1, (short) 2);
    }

    // ─────────────────────────────────────────── 권한

    @Test
    @DisplayName("★★ 지점 관리자는 전 지점 공통 기준을 만들 수 없다 — 다른 지점 학생 장학까지 흔들린다")
    void branchCannotTouchCommonRule() {
        assertThatThrownBy(() -> create(branchAdmin, null, "KICE_30", 1, 5,
                "KOREAN,MATH,ENGLISH", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("지점 관리자는 자기 지점 기준을 만든다")
    void branchCreatesOwnRule() {
        var rule = create(branchAdmin, academy.getId(), "KICE_30", 1, 5,
                "KOREAN,MATH,ENGLISH", null, null, null, null);

        assertThat(rule.isCommon()).isFalse();
        assertThat(rule.getAcademy().getId()).isEqualTo(academy.getId());
    }

    @Test
    @DisplayName("★ 다른 지점 기준은 못 만진다")
    void otherBranchDenied() {
        Academy other = new Academy("SR2", "다른지점", LocalTime.of(9, 0));
        em.persist(other);
        var rule = create(headquarters, other.getId(), "KICE_30", 1, 5,
                "KOREAN,MATH,ENGLISH", null, null, null, null);
        em.flush();

        assertThatThrownBy(() -> ruleService.toggleActive(branchAdmin, rule.getId(), true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }

    // ─────────────────────────────────────────── 검증

    @Test
    @DisplayName("★★ AND 조건은 과목과 등급 상한을 함께 줘야 한다 — 하나만 주면 조건이 조용히 무시된다")
    void extraConditionNeedsBothParts() {
        assertThatThrownBy(() -> create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH",
                null, ElectiveMode.SINGLE, "ENGLISH", null))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH",
                null, ElectiveMode.SINGLE, null, (short) 2))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 등급합인데 대상 과목이 하나도 없으면 막는다 — 켜도 아무도 안 걸려 헤매게 된다")
    void gradeSumNeedsSubjects() {
        assertThatThrownBy(() -> create(headquarters, null, "KICE_30", 1, 5, null,
                null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("알 수 없는 회차는 막는다")
    void unknownExamCodeRejected() {
        assertThatThrownBy(() -> create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH",
                "JUNE,MARCH", null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("등급 상한은 1~9다")
    void gradeRangeChecked() {
        assertThatThrownBy(() -> create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH",
                null, ElectiveMode.SINGLE, "ENGLISH", (short) 10))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────── 수정·삭제

    @Test
    @DisplayName("켜고 끌 수 있다")
    void toggle() {
        var rule = create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH,ENGLISH",
                null, null, null, null);
        em.flush();

        assertThat(ruleService.toggleActive(headquarters, rule.getId(), true).isActive()).isTrue();
        assertThat(ruleService.toggleActive(headquarters, rule.getId(), false).isActive()).isFalse();
    }

    @Test
    @DisplayName("수정하면 나머지 축도 함께 바뀐다")
    void update() {
        var rule = create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH",
                "JUNE,SEPT", ElectiveMode.SINGLE, "ENGLISH", (short) 2);
        em.flush();

        ruleService.update(headquarters, rule.getId(), 4, "KOREAN,MATH", "KICE_50",
                (short) 1, "JUNE", ElectiveMode.AVG2, "ENGLISH", (short) 1);

        assertThat(rule.getThreshold()).isEqualTo(4);
        assertThat(rule.getScholarshipType()).isEqualTo("KICE_50");
        assertThat(rule.getElectiveMode()).isEqualTo(ElectiveMode.AVG2);
        assertThat(rule.examCodes()).containsExactly(com.dlab.domain.grade.entity.ExamCode.JUNE);
    }

    @Test
    @DisplayName("삭제는 soft — 과거 검토가 어떤 기준으로 걸렸는지 추적할 수 있어야 한다")
    void softDelete() {
        var rule = create(headquarters, null, "KICE_30", 1, 5, "KOREAN,MATH,ENGLISH",
                null, null, null, null);
        em.flush();

        ruleService.delete(headquarters, rule.getId());

        assertThat(rule.isDeleted()).isTrue();
        assertThat(ruleService.findAll(headquarters, null, YEAR))
                .noneMatch(r -> r.getId().equals(rule.getId()));
    }

    @Test
    @DisplayName("없는 기준은 못 만진다")
    void notFound() {
        assertThatThrownBy(() -> ruleService.toggleActive(headquarters, 999_999L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCHOLARSHIP_RULE_NOT_FOUND);
    }
}
