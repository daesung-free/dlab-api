package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 선생님 — <b>담당선생님(사감)만</b> 들어온다. 행정선생님은 {@link Employee} 쪽이다.
 *
 * <p>이 테이블에 담당선생님만 있기 때문에 {@code class_master.homeroom_teacher_id}와
 * {@code approval_request.escalation_teacher_id}의 FK가 곧 "담당선생님 보장"이 된다.
 *
 * <p>레거시도 {@code TB_TEACHER_MST}와 직원관리 테이블이 분리돼 있었다.
 *
 * <p>겸직(한 강사가 여러 지점)은 없다고 확인돼 {@code academy}를 직접 갖는다.
 *
 * <p>컬럼은 요구사항(F-4.10-2)에 필요한 만큼만 둔다 — 레거시에 있던 본명·사진·사번·직급·
 * 강사료 계좌는 대응 요구사항이 없어 옮기지 않았다. 레거시는 참고 사전이지 설계 기준이 아니다.
 * 담당선생님/행정선생님 구분은 여기 두지 않는다 — RBAC 5단계 role(TEACHER/STAFF)이
 * 이미 담당하므로 같은 것을 두 곳에서 표현하지 않는다.
 */
@Getter
@Entity
@Table(name = "teacher")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Teacher extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(length = 20)
    private String phone;

    @Column(length = 128)
    private String email;

    @Column(name = "hired_date")
    private LocalDate hiredDate;

    @Column(name = "resigned_date")
    private LocalDate resignedDate;

    public Teacher(Academy academy, String name, String phone) {
        this.academy = academy;
        this.name = name;
        this.phone = phone;
    }
}
