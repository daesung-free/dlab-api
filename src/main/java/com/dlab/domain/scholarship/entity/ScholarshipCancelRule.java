package com.dlab.domain.scholarship.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * 장학 취소 기준 (지점 × 연도 × 요건).
 *
 * <h2>★ 코드에 박지 않고 데이터로 둔다</h2>
 * 시트가 <i>"지점별, 연도별 상이 할수 있음"</i>이라고 명시했다.
 * {@code penalty_rule}과 같은 방식이다.
 *
 * <h2>★ {@link #active}가 기본 {@code false}다</h2>
 * 행을 넣어도 <b>명시적으로 켜기 전엔 안 돈다.</b> 미검증 규칙이 실수로 돌면
 * 멀쩡한 학생이 검토 대상으로 올라온다.
 */
@Getter
@Entity
@Table(name = "scholarship_cancel_rule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipCancelRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = 전 지점 공통. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 30)
    private CancelRuleType ruleType;

    /** 벌점 40 / 등급합 5 / 미응시 2회. */
    @Column(nullable = false)
    private int threshold;

    /**
     * 등급합에 <b>고정으로</b> 들어가는 과목(콤마 구분). {@code exam_subject.subject_code}와
     * 같은 값이다. 탐구는 학생마다 과목이 달라 여기 적지 않고 {@link #electiveMode}가 다룬다.
     */
    @Column(name = "subject_codes", length = 200)
    private String subjectCodes;

    /**
     * 적용 장학. {@code null} = 장학 종류 무관(벌점 40점이 그렇다).
     *
     * <p>{@code scholarship.scholarship_type}과 같은 값이다. 거기가 자유 문자열이라
     * 여기도 enum으로 두지 않는다 — 지점마다 장학 이름이 다를 수 있다.
     */
    @Column(name = "scholarship_type", length = 20)
    private String scholarshipType;

    /**
     * ★ 같은 (장학, 요건) 안의 <b>OR 대안</b> 번호.
     *
     * <p>규정이 <i>"(국+수+탐) 또는 (국+수+영)"</i>이라 <b>하나라도 충족하면 통과</b>다.
     * 반대로 하면(하나라도 미달이면 걸림) 유리한 쪽을 골라주는 취지가 뒤집힌다.
     */
    @Column(name = "alternative_group", nullable = false)
    private short alternativeGroup = 1;

    /**
     * 대상 회차(콤마). 비면 {@code JUNE,SEPT}.
     *
     * <p>수능 기준 장학은 {@code CSAT}를, 평가원 기준은 {@code JUNE,SEPT}를 본다.
     * 여러 회차면 <b>좋은 쪽</b>을 쓴다 — 한 번 못 본 시험 때문에 장학이 날아가면 안 된다.
     */
    @Column(name = "exam_codes", length = 50)
    private String examCodes;

    /**
     * 탐구 집계 방식. {@code null}이면 탐구를 안 본다(국+수+영 대안).
     *
     * <p>⚠️ 답변서가 <i>"해마다 탐구 1과목만 반영하기도 한다"</i>고 해서 <b>값으로 둔다.</b>
     * 코드에 박으면 해마다 다시 물어야 한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "elective_mode", length = 10)
    private ElectiveMode electiveMode;

    /**
     * AND 조건 — 등급합과 <b>별개로</b> 충족해야 하는 과목. 평가원 계열의 "영어 2등급 이내".
     *
     * <p>등급합만 보면 영어를 아무리 못 봐도 국·수·탐으로 메울 수 있어 규정과 달라진다.
     */
    @Column(name = "extra_subject_code", length = 20)
    private String extraSubjectCode;

    /** {@link #extraSubjectCode}의 등급 상한(이하). */
    @Column(name = "extra_max_grade")
    private Short extraMaxGrade;

    @Column(nullable = false)
    private boolean active = false;

    public ScholarshipCancelRule(Academy academy, short year, CancelRuleType ruleType,
                                 int threshold, String subjectCodes) {
        this.academy = academy;
        this.year = year;
        this.ruleType = ruleType;
        this.threshold = threshold;
        this.subjectCodes = subjectCodes;
        this.active = false;
    }

    /** 등급합 대상 과목 목록. 비어 있으면 판정하지 않는다 — 임의로 고르면 안 된다. */
    public List<String> subjects() {
        return split(subjectCodes);
    }

    /** 대상 회차. 비어 있으면 6월·9월 평가원 — 기존 동작 그대로다. */
    public List<ExamCode> examCodes() {
        List<String> raw = split(examCodes);
        if (raw.isEmpty()) {
            return List.of(ExamCode.JUNE, ExamCode.SEPT);
        }
        return raw.stream().map(ExamCode::valueOf).toList();
    }

    /** AND 조건이 걸려 있는가. */
    public boolean hasExtraCondition() {
        return extraSubjectCode != null && !extraSubjectCode.isBlank() && extraMaxGrade != null;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void update(int threshold, String subjectCodes) {
        this.threshold = threshold;
        this.subjectCodes = subjectCodes;
    }

    /** 등급합 규칙의 나머지 축. 관리자 화면이 연도마다 이걸 조정한다. */
    public void updateGradeSumOptions(String scholarshipType, short alternativeGroup,
                                      String examCodes, ElectiveMode electiveMode,
                                      String extraSubjectCode, Short extraMaxGrade) {
        this.scholarshipType = scholarshipType;
        this.alternativeGroup = alternativeGroup;
        this.examCodes = examCodes;
        this.electiveMode = electiveMode;
        this.extraSubjectCode = extraSubjectCode;
        this.extraMaxGrade = extraMaxGrade;
    }

    /** 이 학생의 장학에 적용되는 규칙인가. {@code scholarshipType}이 없으면 전부에 적용된다. */
    public boolean appliesTo(String studentScholarshipType) {
        return scholarshipType == null || scholarshipType.equals(studentScholarshipType);
    }

    /** 켜고 끄기. <b>끄면 판정에서 아예 빠진다</b> — 이미 올라온 검토 대상은 남는다. */
    public void changeActive(boolean active) {
        this.active = active;
    }

    public boolean isCommon() {
        return academy == null;
    }
}
