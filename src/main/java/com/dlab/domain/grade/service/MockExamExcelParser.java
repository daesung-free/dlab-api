package com.dlab.domain.grade.service;

import com.dlab.common.excel.TwoRowHeaderReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 모의고사 성적 엑셀 파서 (담임용 통합 파일).
 *
 * <h2>양식을 우리가 만들지 않는다</h2>
 * 대성전산이 쓰던 파일을 그대로 받는다. 더프리미엄과 평가원이 <b>같은 양식</b>을 쓰고
 * 시험 종류에 따라 채워지는 열만 다르므로, 파서 하나로 둘 다 처리된다.
 * 우리가 양식을 새로 만들면 담당자에게 없던 일을 시키는 셈이다.
 *
 * <h2>★ 헤더가 두 줄이다</h2>
 * 위 줄이 영역(국어·수학·탐구…), 아래 줄이 항목(표준점수·백분위·등급…)이고
 * <b>항목명이 심하게 겹친다</b> — 실제 파일에 "원점수"가 11번, "표준점수"가 10번 나온다.
 * 그래서 {@link TwoRowHeaderReader} 로 {@code "국어 > 표준점수"} 2단 키를 만들어 읽는다.
 * 열 위치로 읽으면 학년마다 구성이 달라 매번 깨진다.
 *
 * <h2>★ 값이 비어 있는 것은 정상이다</h2>
 * 예상 표준점수·백분위·등급은 <b>더프 응시자에게만</b> 제공된다. 평가원 회차 파일에서는
 * 그 열이 있는데 값만 비어 있다 — 파일이 잘못된 것이 아니므로 오류로 잡지 않는다.
 *
 * <h2>학생 매칭은 여기서 하지 않는다</h2>
 * 이 클래스는 <b>읽기만</b> 한다. 학교코드·반·번호를 그대로 돌려주고, 그것을 우리 학생과
 * 잇는 일은 호출부가 한다 — ⚠️ 매칭 키가 아직 확정되지 않았다(학교코드 ↔ 지점 매핑이
 * 우리 DB 에 없고, 번호가 반에 종속돼 반 이동 시 바뀌는지도 확인 중이다. CLAUDE.md §4).
 */
@Component
@RequiredArgsConstructor
public class MockExamExcelParser {

    /** 위 줄(영역). 0-based */
    private static final int BLOCK_ROW = 1;
    /** 아래 줄(항목) */
    private static final int LABEL_ROW = 2;

    /** 과목 블록 이름. 이 순서대로 응답에 담긴다. */
    private static final List<String> SUBJECT_BLOCKS = List.of(
            "국어", "수학", "영어", "한국사",
            "탐구영역-선택1과목", "탐구영역-선택2과목", "제2외국어/한문");

    private final TwoRowHeaderReader reader;

    public Result parse(InputStream input) {
        TwoRowHeaderReader.Result raw = reader.read(input, BLOCK_ROW, LABEL_ROW);

        List<StudentRow> students = new ArrayList<>();
        for (Map<String, String> row : raw.rows()) {
            String schoolCode = get(row, "학교코드");
            String name = get(row, "이름");
            // 합계·공백 행이 끼어 있는 파일이 있어 식별자가 없으면 건너뛴다
            if (schoolCode.isEmpty() || name.isEmpty()) {
                continue;
            }
            students.add(new StudentRow(
                    Integer.parseInt(row.getOrDefault(TwoRowHeaderReader.ROW_NUMBER, "0")),
                    schoolCode,
                    get(row, "학교명"),
                    get(row, "반"),
                    get(row, "번호"),
                    name,
                    get(row, "응시영역"),
                    subjects(row)));
        }
        return new Result(raw.headerKeys(), students);
    }

    private Map<String, SubjectScore> subjects(Map<String, String> row) {
        Map<String, SubjectScore> scores = new LinkedHashMap<>();
        for (String block : SUBJECT_BLOCKS) {
            // ★ 같은 항목인데 영역마다 이름이 다르다. 실제 파일 기준:
            //   국어·수학  선택과목 / 원점수(계)
            //   탐구       과목명   / 원점수
            //   영어·한국사 절대평가라 등급만 있고 석차 라벨에 "(원점수)" 가 붙는다
            //   추측으로 하나만 쓰면 그 영역이 통째로 빈다 — 실제로 탐구 과목명이 비었다.
            SubjectScore score = new SubjectScore(
                    firstOf(row, block, "선택과목", "과목명", "유형"),
                    firstOf(row, block, "원점수(계)", "원점수"),
                    value(row, block, "표준점수"),
                    value(row, block, "백분위"),
                    value(row, block, "등급"),
                    firstOf(row, block, "학급석차", "학급석차(원점수)"),
                    firstOf(row, block, "학교석차", "학교석차(원점수)"),
                    firstOf(row, block, "전국석차", "전국석차(원점수)"));
            // 응시하지 않은 영역은 통째로 비어 온다 — 담지 않는다
            if (!score.isEmpty()) {
                scores.put(block, score);
            }
        }
        return scores;
    }

    private String value(Map<String, String> row, String block, String label) {
        return row.getOrDefault(TwoRowHeaderReader.key(block, label), "");
    }

    /** 라벨 후보를 순서대로 본다. 먼저 채워진 값을 쓴다. */
    private String firstOf(Map<String, String> row, String block, String... labels) {
        for (String label : labels) {
            String value = value(row, block, label);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 앞쪽 식별 열(학교코드·반·번호·이름 …)을 찾는다.
     *
     * <p>★ <b>두 줄이 같지 않다.</b> 실제 파일은 위 줄 {@code 학교코드} 아래 줄 {@code 학교},
     * 위 줄 {@code 계열} 아래 줄 {@code 이름} 처럼 어긋난다 — 사람이 보기엔 같은 칸이지만
     * 키로는 {@code "학교코드 > 학교"} 가 된다. 그래서 <b>위 줄 이름으로도 찾는다.</b>
     */
    private String get(Map<String, String> row, String label) {
        String exact = row.get(label);
        if (exact != null) {
            return exact;
        }
        String same = row.get(TwoRowHeaderReader.key(label, label));
        if (same != null) {
            return same;
        }
        String prefix = label + " > ";
        return row.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    /**
     * @param headerKeys 파일에서 만들어진 2단 키 전체. 매핑이 어긋났을 때
     *                   <b>무엇이 있었는지</b> 보여줘야 원인을 찾을 수 있다
     */
    public record Result(List<String> headerKeys, List<StudentRow> students) {
    }

    /**
     * @param rowNumber 엑셀 기준 행 번호(1부터). 오류를 알릴 때 사용자가 그 행을 찾는다
     * @param classNo   ⚠️ 학생에 고정으로 매핑하지 말 것 — 번호가 "반 번호 + 순번" 구조라
     *                  반이 바뀌면 앞자리가 바뀐다(CLAUDE.md §2). 회차 시점 값으로 다룬다
     */
    public record StudentRow(int rowNumber, String schoolCode, String schoolName,
                             String classNo, String studentNo, String name,
                             String examArea, Map<String, SubjectScore> subjects) {
    }

    /**
     * 과목 한 칸.
     *
     * <p>전부 문자열이다 — 등급은 정수인데 한국사는 절대평가라 등급만 오고, 석차는
     * {@code "158"} 처럼 오다가 비기도 한다. 숫자로 강제 변환하면 그때마다 파싱이 깨진다.
     * 변환은 저장하는 쪽이 자기 규칙으로 한다.
     */
    public record SubjectScore(String electiveSubject, String rawScore, String standardScore,
                               String percentile, String gradeLevel,
                               String classRank, String schoolRank, String nationalRank) {

        public boolean isEmpty() {
            return electiveSubject.isEmpty() && rawScore.isEmpty() && standardScore.isEmpty()
                    && percentile.isEmpty() && gradeLevel.isEmpty();
        }
    }
}
