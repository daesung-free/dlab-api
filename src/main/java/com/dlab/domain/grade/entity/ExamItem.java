package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회차별 문항 한 줄 — 채점 탭의 근거 (시안 4.5).
 *
 * <p>문항분석표(정답·배점·단원·평가요소)와 정답률(전국 정답률·선택지 응답률·변별도)을
 * <b>(정규 과목명, 문항번호)</b>로 합친다. 과목명이 파일마다 달라(물리학I / 물리학Ⅰ)
 * {@code subjectKey} 로 잇는다.
 *
 * <p>★ <b>학생 정오와 분리한다</b> — 문항 정보는 전 학생이 공유한다. 한 테이블에 두면
 * 전국 정답률이 학생 수만큼 복제된다.
 */
@Getter
@Entity
@Table(name = "exam_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_master_id", nullable = false)
    private ExamMaster examMaster;

    @Column(name = "subject_code", length = 10)
    private String subjectCode;

    @Column(name = "subject_name", nullable = false, length = 30)
    private String subjectName;

    @Column(name = "subject_key", nullable = false, length = 30)
    private String subjectKey;

    @Column(name = "question_no", nullable = false)
    private short questionNo;

    private Short answer;
    private Short points;

    @Column(nullable = false)
    private boolean elective;

    @Column(name = "unit_code", length = 10)
    private String unitCode;
    @Column(name = "unit_name", length = 50)
    private String unitName;
    @Column(name = "skill_code", length = 10)
    private String skillCode;
    @Column(name = "skill_name", length = 50)
    private String skillName;

    @Column(name = "national_rate", precision = 5, scale = 2)
    private BigDecimal nationalRate;
    @Column(name = "choice1_rate", precision = 5, scale = 2)
    private BigDecimal choice1Rate;
    @Column(name = "choice2_rate", precision = 5, scale = 2)
    private BigDecimal choice2Rate;
    @Column(name = "choice3_rate", precision = 5, scale = 2)
    private BigDecimal choice3Rate;
    @Column(name = "choice4_rate", precision = 5, scale = 2)
    private BigDecimal choice4Rate;
    @Column(name = "choice5_rate", precision = 5, scale = 2)
    private BigDecimal choice5Rate;
    @Column(precision = 6, scale = 3)
    private BigDecimal discrimination;

    public ExamItem(ExamMaster examMaster, String subjectCode, String subjectName,
                    short questionNo, Short answer, Short points, boolean elective,
                    String unitCode, String unitName, String skillCode, String skillName) {
        this.examMaster = examMaster;
        this.subjectCode = subjectCode;
        this.subjectName = subjectName;
        this.subjectKey = com.dlab.domain.grade.service.SubjectNames.key(subjectName);
        this.questionNo = questionNo;
        this.answer = answer;
        this.points = points;
        this.elective = elective;
        this.unitCode = unitCode;
        this.unitName = unitName;
        this.skillCode = skillCode;
        this.skillName = skillName;
    }

    /** 정답률 파일 값을 얹는다. 두 파일이 따로 오므로 문항분석표를 먼저 만들고 여기에 붙인다. */
    public void applyRates(BigDecimal nationalRate, BigDecimal[] choiceRates,
                           BigDecimal discrimination) {
        this.nationalRate = nationalRate;
        this.choice1Rate = at(choiceRates, 0);
        this.choice2Rate = at(choiceRates, 1);
        this.choice3Rate = at(choiceRates, 2);
        this.choice4Rate = at(choiceRates, 3);
        this.choice5Rate = at(choiceRates, 4);
        this.discrimination = discrimination;
    }

    private static BigDecimal at(BigDecimal[] values, int i) {
        return values != null && values.length > i ? values[i] : null;
    }

    /** 선택지별 응답률 1~5번. 함정 오답(가장 많이 고른 오답)을 찾는 데 쓴다. */
    public BigDecimal[] choiceRates() {
        return new BigDecimal[]{choice1Rate, choice2Rate, choice3Rate, choice4Rate, choice5Rate};
    }
}
