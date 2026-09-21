package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학생 정오·답안 — 학생 × 과목 한 줄 (채점 탭).
 *
 * <p>★ <b>문항을 {@code firstNo} 부터 이어 붙인다.</b> 학생 × 문항 한 줄씩이면 회차당 약
 * 38만 행이 매월 쌓인다.
 *
 * <p>{@code results} 는 문항마다 한 글자(O·X·-), {@code answers} 는 <b>쉼표 구분</b>이다 —
 * 수학 단답형 정답이 세 자리라 한 글자씩 붙일 수 없다.
 */
@Getter
@Entity
@Table(name = "student_item_response")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentItemResponse extends BaseEntity {

    public static final char CORRECT = 'O';
    public static final char WRONG = 'X';
    public static final char BLANK = '-';

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_master_id", nullable = false)
    private ExamMaster examMaster;

    @Column(name = "subject_key", nullable = false, length = 30)
    private String subjectKey;

    @Column(name = "first_no", nullable = false)
    private short firstNo;

    @Column(length = 60)
    private String results;

    @Column(length = 400)
    private String answers;

    public StudentItemResponse(StudentEnrollment enrollment, ExamMaster examMaster,
                               String subjectKey, short firstNo, String results,
                               List<String> answers) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.examMaster = examMaster;
        this.subjectKey = subjectKey;
        this.firstNo = firstNo;
        this.results = results;
        this.answers = answers == null ? null : String.join(",", answers);
    }

    /** 문항 번호의 정오. 이 행 범위 밖이면 {@code null}. */
    public Character resultOf(int questionNo) {
        int i = questionNo - firstNo;
        return results == null || i < 0 || i >= results.length() ? null : results.charAt(i);
    }

    /** 문항 번호에 학생이 고른 답. 비었으면 {@code null}. */
    public String answerOf(int questionNo) {
        List<String> list = answerList();
        int i = questionNo - firstNo;
        if (i < 0 || i >= list.size()) {
            return null;
        }
        String a = list.get(i);
        return a.isBlank() ? null : a;
    }

    public List<String> answerList() {
        return answers == null ? List.of() : new ArrayList<>(Arrays.asList(answers.split(",", -1)));
    }

    public int questionCount() {
        return results == null ? 0 : results.length();
    }
}
