package com.dlab.domain.kiosk.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지점 설정 변경 감사로그 (F-4.10-7).
 *
 * <p><b>{@code branch_config}만으로는 "누가 재발급했나"에 답할 수 없다.</b>
 * {@code created_by}는 행을 처음 만든 사람이고({@code updatable = false}), 수정자 컬럼은
 * 전 테이블에 없다. 지점 설정은 지점당 1행이라 계속 UPDATE만 되므로 이력이 없으면
 * 최초 생성자만 영원히 남는다.
 *
 * <p><b>값 자체는 남기지 않는다.</b> 시크릿·MID를 이력에 복사하면 비밀값이 두 곳으로
 * 늘어나고 폐기한 시크릿이 영구 보존된다 — "언제 누가 무엇을"이면 감사 목적은 끝난다.
 */
@Getter
@Entity
@Table(name = "branch_config_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchConfigHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BranchConfigAction action;

    @Column(length = 200)
    private String detail;

    public BranchConfigHistory(Long academyId, short year,
                              BranchConfigAction action, String detail) {
        this.academyId = academyId;
        this.year = year;
        this.action = action;
        this.detail = detail;
    }
}
