package com.dlab.domain.grade.service;

import java.util.Map;
import java.util.Optional;

/**
 * 과목명 대응 — 연구소 파일마다 과목 표기가 다르다 (0921 문서 6장).
 *
 * <pre>
 *   문항분석표  물리학I   (영문 대문자 I, U+0049)   · 화법과작문
 *   정답률      물리학Ⅰ   (로마숫자 Ⅰ, U+2160)     · 화법과 작문
 *   정오표      물리1                               · 화작
 * </pre>
 *
 * <p>★ <b>연구소가 과목 코드를 따로 주지 않는다</b>("과목별 코드는 별도로 존재하지 않습니다").
 * 그래서 <b>이름을 정규화한 키</b>로 잇는다.
 *
 * <p>★ <b>로마숫자는 눈으로 구분되지 않는다.</b> {@code I}(U+0049)와 {@code Ⅰ}(U+2160)가
 * 파일마다 다르다 — 반드시 코드포인트로 치환한다.
 */
public final class SubjectNames {

    private SubjectNames() {
    }

    /**
     * 정오표·답안표의 약어 → 정규 키. 26 과목 (0921 문서 6장 대응표).
     *
     * <p>통합수능 전환으로 새 과목명이 들어올 예정이다. <b>여기 없는 약어는 조용히 버리지 않고
     * 오류행으로 남긴다</b> — 호출부가 {@link Optional#empty()} 를 받아 처리한다.
     */
    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("화작", "화법과작문"), Map.entry("언매", "언어와매체"),
            Map.entry("확통", "확률과통계"), Map.entry("미적", "미적분"), Map.entry("기하", "기하"),
            Map.entry("영어", "영어"), Map.entry("한국", "한국사"),
            Map.entry("생윤", "생활과윤리"), Map.entry("윤사", "윤리와사상"),
            Map.entry("한지", "한국지리"), Map.entry("세지", "세계지리"),
            Map.entry("동아", "동아시아사"), Map.entry("세계", "세계사"),
            Map.entry("경제", "경제"), Map.entry("정법", "정치와법"), Map.entry("사문", "사회문화"),
            Map.entry("물리1", "물리학1"), Map.entry("화학1", "화학1"),
            Map.entry("생명1", "생명과학1"), Map.entry("지학1", "지구과학1"),
            Map.entry("물리2", "물리학2"), Map.entry("화학2", "화학2"),
            Map.entry("생명2", "생명과학2"), Map.entry("지학2", "지구과학2"));

    /**
     * 정규 키 — 공백을 지우고 로마숫자를 아라비아 숫자로 바꾼다.
     *
     * <p>★ <b>긴 것부터 바꾼다.</b> {@code I→1} 을 {@code II→2} 보다 먼저 하면
     * {@code 물리학II} 가 {@code 물리학11} 이 된다 — 실제로 한 번 틀렸다(0921 문서).
     */
    public static String key(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s+", "")
                .replace("Ⅲ", "3").replace("Ⅱ", "2").replace("Ⅰ", "1")
                .replace("III", "3").replace("II", "2").replace("I", "1");
    }

    /** 정오표 약어 → 정규 키. 모르는 약어는 비어 있다 — 버리지 말고 오류로 알릴 것. */
    public static Optional<String> fromAbbreviation(String abbreviation) {
        if (abbreviation == null || abbreviation.isBlank()) {
            return Optional.empty();
        }
        String direct = ABBREVIATIONS.get(abbreviation.trim());
        return Optional.ofNullable(direct).map(SubjectNames::key);
    }
}
