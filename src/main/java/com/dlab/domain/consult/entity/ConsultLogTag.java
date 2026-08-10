package com.dlab.domain.consult.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 상담 일지 ↔ 태그. */
@Getter
@Entity
@Table(name = "consult_log_tag")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultLogTag extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "log_id", nullable = false)
    private ConsultLog log;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false)
    private ConsultTag tag;

    ConsultLogTag(ConsultLog log, ConsultTag tag) {
        this.log = log;
        this.tag = tag;
    }
}
