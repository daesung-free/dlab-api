package com.dlab.domain.vocab.service;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.vocab.entity.VocabAnswer;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.repository.VocabAnswerRepository;
import com.dlab.domain.vocab.repository.VocabWordRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오답 확인 (v1 7장).
 *
 * <p>디랩 원문: <i>"오답 확인: 문항별 학생 답 조회 → <b>주관식 인정 표현 추가로 연결</b>"</i>.
 *
 * <h2>★★ 이 화면의 목적은 통계가 아니라 「인정 표현 넓히기」다</h2>
 * 주관식 채점을 넓히는 <b>유일한 경로</b>다(v1 Q3 가 정규화 확대를 미확정으로 두고
 * v2 5.3 이 <i>"관리자가 인정 표현으로 채운다"</i>로 정리했다). 그래서 단순히 오답률을
 * 보여주는 것이 아니라 <b>학생이 실제로 쓴 답을 같은 것끼리 묶어</b> 보여준다 —
 * 같은 표현을 10명이 썼으면 그건 인정해야 할 표현이다.
 *
 * <h2>★ 주관식만 모은다</h2>
 * 객관식 오답은 <b>보기 중 하나를 고른 것</b>이라 인정 표현이 될 수 없다. 섞으면 목록이
 * 다른 단어의 뜻으로 가득 차 정작 볼 것이 묻힌다.
 */
@Service
@RequiredArgsConstructor
public class VocabWrongAnswerService {

    private final VocabAnswerRepository answerRepository;
    private final VocabWordRepository wordRepository;

    /**
     * 단어 한 줄 — 오답이 많은 순.
     *
     * @param wrongRate 오답률(%). ★ <b>응시 수가 적으면 믿을 값이 아니다</b> —
     *                  {@code answeredCount} 를 같이 내려 화면이 가릴 수 있게 한다
     */
    public record WordRow(Long wordId, Short unitNo, String word, String meaningRaw,
                          int answeredCount, int wrongCount, Integer wrongRate) {
    }

    /**
     * 학생이 쓴 답 하나 — <b>같은 답끼리 묶은</b> 것이다.
     *
     * @param count 그 답을 쓴 건수. ★ 10명이 같은 표현을 썼으면 <b>인정해야 할 표현</b>이다
     */
    public record GivenRow(String given, int count) {
    }

    /** 단어별 오답 현황. */
    @Transactional(readOnly = true)
    public List<WordRow> words(AuthPrincipal me, Short unitNo) {
        // ⚠️ 단어는 전 지점 공통 마스터라 지점 범위가 없다 — 지점별로 다른 교재를 쓰지 않는다
        var words = unitNo != null
                ? wordRepository.findByUnitNoAndDeletedFalseOrderBySeqNoAsc(unitNo)
                : wordRepository.findByDeletedFalse();

        List<WordRow> rows = new ArrayList<>();
        for (VocabWord word : words) {
            var answers = answerRepository.findByWordIdAndDeletedFalse(word.getId()).stream()
                    // ★ 주관식만 — 객관식 오답은 보기 중 하나라 인정 표현이 될 수 없다
                    .filter(a -> a.getType() == VocabAnswer.Type.SA)
                    .filter(a -> a.getCorrect() != null)
                    .toList();
            if (answers.isEmpty()) {
                continue;   // 아직 아무도 안 푼 단어는 목록에 넣지 않는다
            }
            int wrong = (int) answers.stream().filter(a -> Boolean.FALSE.equals(a.getCorrect()))
                    .count();
            rows.add(new WordRow(word.getId(), word.getUnitNo(), word.getWord(),
                    word.getMeaningRaw(), answers.size(), wrong,
                    wrong * 100 / answers.size()));
        }
        // 오답이 많은 것부터 — 손이 필요한 단어가 위로 온다
        rows.sort(Comparator.comparingInt(WordRow::wrongCount).reversed()
                .thenComparing(WordRow::unitNo, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /**
     * 그 단어에 학생들이 쓴 틀린 답 — <b>같은 답끼리 묶어</b> 많은 순.
     *
     * <p>★ <b>묶지 않으면 쓸 수 없다.</b> 200명이 푼 단어의 오답을 한 줄씩 보여주면
     * 담당자가 같은 표현을 200번 읽는다. 묶어 두면 <b>맨 위 몇 줄만 보고</b> 인정 표현을
     * 넣을지 판단할 수 있다.
     *
     * <p>★ <b>무응답은 빼지 않고 따로 보인다.</b> 빈 답도 저장하므로(v1 4.2) 그대로 세면
     * 맨 위가 빈 칸이 된다 — 그건 인정 표현 후보가 아니지만, 지우면 <b>「아무도 안 쓴 것」과
     * 「쓰지 못한 것」이 구분되지 않는다.</b>
     */
    @Transactional(readOnly = true)
    public List<GivenRow> givens(AuthPrincipal me, Long wordId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (VocabAnswer answer : answerRepository.findByWordIdAndCorrectFalseAndDeletedFalse(wordId)) {
            if (answer.getType() != VocabAnswer.Type.SA) {
                continue;
            }
            // ★ 정규화해서 묶는다 — 채점과 같은 판정을 써야 "이걸 넣으면 맞게 되는가"가 맞는다
            String key = MeaningSplitter.normalize(answer.getGiven());
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new GivenRow(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(GivenRow::count).reversed())
                .toList();
    }
}
