package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모의고사 파일 식별자 ↔ 학생 연결.
 *
 * <h2>왜 두는가</h2>
 * 업로드는 이름으로 학생을 찾는다 — 파일의 식별자(학교코드·반·번호)를 우리 학생과 잇는
 * 매핑이 없어서다. 그런데 이름은 <b>동명이인에서 멈춘다.</b> 둘 중 하나를 고르면 남의
 * 성적이 들어가므로 매칭하지 않고 빼는데, 그러면 그 학생은 <b>회차마다 계속 빠진다.</b>
 * 한 번 사람이 정해준 연결을 남겨 다음 회차부터 자동으로 쓴다.
 *
 * <h2>⚠️ 반이 바뀌면 키가 끊긴다 — 그래도 괜찮다</h2>
 * 번호가 <b>"반 번호 + 3자리 순번"</b> 구조라 반 이동 시 앞자리가 바뀐다. 그때 이 행은
 * 맞지 않고 이름 매칭으로 떨어진다 — <b>틀린 학생에게 들어가는 게 아니라 다시 물어보는
 * 쪽으로 실패한다.</b> 키를 느슨하게 잡아 억지로 이으면 반대가 된다.
 */
@Getter
@Entity
@Table(name = "mock_exam_student_key")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MockExamStudentKey extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    /** 파일에서 오는 값 그대로. 문자열이다 — 숫자로 바꾸면 앞자리 0 이 사라진다 */
    @Column(name = "school_code", nullable = false, length = 20)
    private String schoolCode;

    @Column(name = "class_no", nullable = false, length = 20)
    private String classNo;

    @Column(name = "student_no", nullable = false, length = 20)
    private String studentNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    public MockExamStudentKey(Academy academy, short year, String schoolCode,
                              String classNo, String studentNo, StudentEnrollment enrollment) {
        this.academy = academy;
        this.year = year;
        this.schoolCode = schoolCode;
        this.classNo = classNo;
        this.studentNo = studentNo;
        this.enrollment = enrollment;
    }

    /** 연결 대상을 바꾼다. 같은 칸에 행을 하나 더 만들지 않는다 — 어느 쪽인지 정해지지 않는다. */
    public void relink(StudentEnrollment enrollment) {
        this.enrollment = enrollment;
    }
}
