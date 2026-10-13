package com.dlab.domain.profile.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 신상기록부 양식 (F-4.11-9).
 *
 * <h2>★★ 항목을 컬럼·enum 으로 박지 않는다</h2>
 * 양식이 <b>학년별로 갈리고 그 매핑이 미확정(I-17)</b>이다. 박으면 확정될 때마다
 * 마이그레이션을 새로 쓰게 되고, 그때 <b>과거 학생이 무엇에 답했는지가 흐려진다</b> —
 * 모의고사 과목을 enum 대신 {@code exam_master}/{@code exam_subject} 마스터로 둔 것과
 * 같은 판단이다. 확정되면 <b>행만 넣는다</b>.
 *
 * <h2>★ 고치지 않고 버전을 올린다</h2>
 * 약관과 같다. 문구를 덮어쓰면 <b>이미 작성한 학생들이 무엇에 답했는지가 바뀐다</b> —
 * 「흡연 여부」를 「기저질환」으로 고치면 그 자리에 들어 있던 값이 다른 질문의 답으로 읽힌다.
 */
@Getter
@Entity
@Table(name = "student_profile_form")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentProfileForm extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    /**
     * {@code null} 이면 전 지점 공통.
     *
     * <p>{@code holiday}·{@code terms} 와 같은 규약이다 — 0821 회신에서 <b>지점별·종류별로
     * 양식이 다를 수 있다</b>고 확인됐다(장학 동의서와 같은 사정).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    /**
     * {@code null} 이면 전 학년 공통.
     *
     * <p>신상기록부 실물이 예비고3 과 현고3 을 다른 양식으로 두는데 <b>4종 매핑(I-17)이
     * 미확정</b>이라, 확정 전에는 공통 한 행으로 쓴다. {@code null} 을 막으면 확정되기
     * 전에는 양식을 아예 등록할 수 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "grade_type", length = 10)
    private GradeType gradeType;

    @Column(nullable = false, length = 100)
    private String name;

    /** 항목 정의. 화면이 이걸 읽어 입력칸을 그린다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", columnDefinition = "jsonb", nullable = false)
    private String schemaJson;

    @Column(nullable = false)
    private short version = 1;

    /**
     * 켜졌는가.
     *
     * <p><b>기본값이 {@code false} 다</b>({@code penalty_rule.active} 와 같은 판단).
     * I-17 이 미확정이라 행을 미리 넣어두게 되는데, 켜지 않은 양식이 앱에 떠서 학생이
     * 쓰기 시작하면 <b>확정본과 어긋난 답이 쌓인다</b>.
     */
    @Column(nullable = false)
    private boolean active = false;

    public StudentProfileForm(Academy academy, short year, GradeType gradeType, String name,
                              String schemaJson, short version) {
        this.academy = academy;
        this.year = year;
        this.gradeType = gradeType;
        this.name = name;
        this.schemaJson = schemaJson;
        this.version = version;
    }

    /**
     * 켜기·끄기.
     *
     * <p>★ <b>항목(schema)은 바꾸지 않는다.</b> 바꿀 일이 생기면 버전을 올려 새 행을 넣는다 —
     * 수정 메서드를 두지 않은 것이 그 때문이다.
     */
    public void changeActive(boolean active) {
        this.active = active;
    }
}
