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
 * 학생별 정오표 · 답안표 — 둘은 모양이 같고 칸 값만 다르다(O·X / 고른 번호).
 *
 * <h2>★ 헤더 이름으로 영역을 가르지 않는다</h2>
 * 위 줄 영역명이 파일 안에서도 흔들린다 — 수학이 {@code "2교시 수학 영역"} 으로 시작해
 * {@code "2교시 수리영역"} 으로 이어지고, 탐구 두 번째 과목은 영역명 없이 {@code "선택2"} 다.
 * 이름으로 찾으면 파일마다 깨진다.
 *
 * <p>대신 <b>아래 줄</b>을 본다. 숫자가 아닌 칸(선택과목·과목·선택1·선택2)이 영역의 시작이고
 * 그 칸 값이 학생이 고른 과목 약어(언매·미적·생윤…)다. 뒤따르는 숫자 칸이 문항 번호다.
 * 영역의 종류는 <b>순서</b>로 안다 — 국어·수학·영어·탐구1·탐구2·한국사·제2외국어.
 */
@Component
@RequiredArgsConstructor
public class ItemSheetParser {

    /** 영역 순서. 파일이 이 순서로 온다(8월 더프 실측). */
    public static final int KOREAN = 0, MATH = 1, ENGLISH = 2, INQUIRY1 = 3, INQUIRY2 = 4,
            HISTORY = 5, FOREIGN = 6;

    private final TwoRowHeaderReader reader;

    public List<StudentSheet> parse(InputStream input) {
        TwoRowHeaderReader.Grid grid = reader.readGrid(input, 2);
        List<BlockColumns> blocks = blocks(grid.labels());

        List<StudentSheet> students = new ArrayList<>();
        for (TwoRowHeaderReader.GridRow row : grid.rows()) {
            String school = row.at(0);
            String name = row.at(4);
            if (school.isBlank() || name.isBlank()) {
                continue;
            }
            List<Block> taken = new ArrayList<>();
            for (int b = 0; b < blocks.size(); b++) {
                BlockColumns cols = blocks.get(b);
                String abbreviation = row.at(cols.subjectColumn());
                if (abbreviation.isEmpty()) {
                    continue;   // 응시하지 않은 영역
                }
                Map<Integer, String> values = new LinkedHashMap<>();
                cols.questions().forEach((no, column) -> values.put(no, row.at(column)));
                taken.add(new Block(b, abbreviation, values));
            }
            students.add(new StudentSheet(row.rowNumber(), school, row.at(2), row.at(3),
                    name, taken));
        }
        return students;
    }

    /** 아래 줄을 훑어 영역을 나눈다 — 숫자가 아닌 칸이 새 영역의 시작이다. */
    private List<BlockColumns> blocks(List<String> labels) {
        List<BlockColumns> blocks = new ArrayList<>();
        BlockColumns current = null;
        for (int c = 5; c < labels.size(); c++) {   // 앞 5칸은 학교·학교명·반·번호·이름
            String label = labels.get(c);
            if (label.isEmpty()) {
                continue;
            }
            if (label.chars().allMatch(Character::isDigit)) {
                if (current != null) {
                    current.questions().put(Integer.parseInt(label), c);
                }
            } else {
                current = new BlockColumns(c, new LinkedHashMap<>());
                blocks.add(current);
            }
        }
        return blocks;
    }

    private record BlockColumns(int subjectColumn, Map<Integer, Integer> questions) {
    }

    /** @param index 영역 순서({@link #KOREAN} …) · @param abbreviation 학생이 고른 과목 약어 */
    public record Block(int index, String abbreviation, Map<Integer, String> values) {
    }

    public record StudentSheet(int rowNumber, String schoolCode, String classNo, String studentNo,
                               String name, List<Block> blocks) {
    }
}
