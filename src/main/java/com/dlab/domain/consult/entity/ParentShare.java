package com.dlab.domain.consult.entity;

/**
 * 상담 일지의 학부모 공유 범위.
 *
 * <p><b>기본은 공유 안 함이다.</b> 학생과 나눈 말을 그대로 전달하면 안 되는 상담이 있고,
 * 공유를 기본값으로 두면 작성자가 의식하지 못한 채 열린다.
 */
public enum ParentShare {

    /** 공유하지 않는다. 학부모 앱에 뜨지 않는다. */
    NONE,

    /** 요약본만. 담임이 정리한 내용이 나간다. */
    SUMMARY,

    /** 상담 내용 전체. */
    FULL
}
