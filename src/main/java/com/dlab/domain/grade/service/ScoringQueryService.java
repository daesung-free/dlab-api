package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.grade.entity.ExamItem;
import com.dlab.domain.grade.entity.StudentItemResponse;
import com.dlab.domain.grade.repository.ExamItemRepository;
import com.dlab.domain.grade.repository.StudentItemResponseRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채점 탭 (시안 4.5) — 개요 · 복습 우선순위 · 평가요소별 · 단원별.
 *
 * <h2>영역 단위로 묶는다</h2>
 * 저장은 과목 단위(국어 / 언어와매체)지만 화면은 <b>영역</b>(국어 영역 = 공통 + 선택)이다.
 * 같은 연구소 과목 코드(01)끼리 묶는다.
 *
 * <h2>★ 복습 우선순위 = 남들은 맞혔는데 나만 틀린 문항</h2>
 * 틀린 문항을 <b>전국 정답률이 높은 순</b>으로 놓는다 — 가장 빨리 올릴 수 있는 점수다.
 * 내가 고른 오답이 <b>가장 많이 고른 오답</b>이면 함정에 걸린 것으로 표시한다.
 */
@Service
@RequiredArgsConstructor
public class ScoringQueryService {

    private final StudentItemResponseRepository responseRepository;
    private final ExamItemRepository itemRepository;

    @Transactional(readOnly = true)
    public List<Area> scoring(StudentEnrollment enrollment, Long examMasterId) {
        List<StudentItemResponse> rows = responseRepository.findOf(enrollment.getId(), examMasterId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "채점 결과를 찾을 수 없습니다.");
        }
        Map<String, ExamItem> items = itemRepository.findByExamMasterId(examMasterId).stream()
                .collect(Collectors.toMap(i -> i.getSubjectKey() + "#" + i.getQuestionNo(),
                        Function.identity(), (a, b) -> a));

        // 문항 하나하나로 편다
        List<Answered> answered = new ArrayList<>();
        for (StudentItemResponse row : rows) {
            for (int n = 0; n < row.questionCount(); n++) {
                int no = row.getFirstNo() + n;
                ExamItem item = items.get(row.getSubjectKey() + "#" + no);
                if (item == null) {
                    continue;
                }
                answered.add(new Answered(item, row.resultOf(no), row.answerOf(no)));
            }
        }

        // 영역 = 같은 과목 코드. 코드가 없으면 과목 자체가 영역이다
        Map<String, List<Answered>> byArea = answered.stream().collect(Collectors.groupingBy(
                a -> a.item().getSubjectCode() == null ? a.item().getSubjectKey() : a.item().getSubjectCode(),
                LinkedHashMap::new, Collectors.toList()));

        return byArea.values().stream().map(ScoringQueryService::area).toList();
    }

    private static Area area(List<Answered> list) {
        // 영역 이름 — 공통 과목이 있으면 그것(국어), 없으면 그 과목(생활과윤리)
        String name = list.stream().filter(a -> !a.item().isElective())
                .map(a -> a.item().getSubjectName()).findFirst()
                .orElse(list.get(0).item().getSubjectName());
        String elective = list.stream().filter(a -> a.item().isElective())
                .map(a -> a.item().getSubjectName()).findFirst().orElse(null);

        int correct = (int) list.stream().filter(Answered::correct).count();
        int lost = list.stream().filter(a -> !a.correct())
                .mapToInt(a -> a.item().getPoints() == null ? 0 : a.item().getPoints()).sum();

        List<Review> review = list.stream().filter(a -> !a.correct())
                .sorted(Comparator.comparing((Answered a) -> a.item().getNationalRate(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(Review::of)
                .toList();

        return new Area(name, elective, list.size(), correct, list.size() - correct, lost, review,
                breakdown(list, i -> i.getSkillName()), breakdown(list, i -> i.getUnitName()));
    }

    /** 평가요소·단원별 — 내 정답률과 전국 정답률(문항 평균) */
    private static List<Breakdown> breakdown(List<Answered> list, Function<ExamItem, String> keyOf) {
        Map<String, List<Answered>> groups = list.stream()
                .filter(a -> keyOf.apply(a.item()) != null)
                .collect(Collectors.groupingBy(a -> keyOf.apply(a.item()),
                        LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream().map(e -> {
            List<Answered> g = e.getValue();
            BigDecimal mine = percent(g.stream().filter(Answered::correct).count(), g.size());
            List<BigDecimal> national = g.stream().map(a -> a.item().getNationalRate())
                    .filter(Objects::nonNull).toList();
            BigDecimal nationalAvg = national.isEmpty() ? null
                    : national.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(national.size()), 1, RoundingMode.HALF_UP);
            return new Breakdown(e.getKey(), g.size(), mine, nationalAvg);
        }).toList();
    }

    private static BigDecimal percent(long part, int total) {
        return total == 0 ? null : BigDecimal.valueOf(part * 100)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private record Answered(ExamItem item, Character result, String answer) {
        boolean correct() {
            return result != null && result == StudentItemResponse.CORRECT;
        }
    }

    /**
     * @param lostPoints 틀리거나 비운 문항의 배점 합
     * @param elective   선택과목(언어와매체 등). 없으면 {@code null}
     */
    public record Area(String name, String elective, int total, int correct, int wrong,
                       int lostPoints, List<Review> review, List<Breakdown> bySkill,
                       List<Breakdown> byUnit) {
    }

    /**
     * 복습할 문항 하나.
     *
     * @param trap 내가 고른 오답이 <b>가장 많이 고른 오답</b>이다 — 함정에 걸렸다
     */
    public record Review(String subjectName, int questionNo, String myAnswer, Short correctAnswer,
                         BigDecimal nationalRate, Short points, String unitName, String skillName,
                         boolean trap) {

        static Review of(Answered a) {
            ExamItem i = a.item();
            return new Review(i.getSubjectName(), i.getQuestionNo(), a.answer(), i.getAnswer(),
                    i.getNationalRate(), i.getPoints(), i.getUnitName(), i.getSkillName(),
                    isTrap(i, a.answer()));
        }

        private static boolean isTrap(ExamItem item, String myAnswer) {
            if (myAnswer == null || item.getAnswer() == null) {
                return false;
            }
            BigDecimal[] rates = item.choiceRates();
            int best = -1;
            BigDecimal bestRate = null;
            for (int c = 0; c < rates.length; c++) {
                if (c + 1 == item.getAnswer() || rates[c] == null) {
                    continue;   // 정답은 오답 후보가 아니다
                }
                if (bestRate == null || rates[c].compareTo(bestRate) > 0) {
                    bestRate = rates[c];
                    best = c + 1;
                }
            }
            return best > 0 && myAnswer.trim().equals(String.valueOf(best));
        }
    }

    /** @param myRate 내 정답률(%) · @param nationalRate 전국 정답률(문항 평균 %) */
    public record Breakdown(String name, int questions, BigDecimal myRate, BigDecimal nationalRate) {
    }
}
