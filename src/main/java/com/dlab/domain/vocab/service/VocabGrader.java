package com.dlab.domain.vocab.service;

import java.util.Collection;

/**
 * 주관식 채점 (v1 4.2 · v2 5.2).
 *
 * <h2>★★ 채점은 서버에서만 한다</h2>
 * <i>"정답이나 보기의 정답 여부를 앱으로 내려보내지 않는다."</i>(v1 4.2) 앱에 정답을
 * 내리면 <b>화면을 뜯어 보면 답이 보인다.</b>
 *
 * <h2>★ 후보와 학생 답에 같은 규칙을 쓴다</h2>
 * {@link MeaningSplitter#normalize} 하나로 양쪽을 다듬는다 — 다르면 <b>같은 글자인데
 * 안 맞는</b> 경우가 생긴다.
 *
 * <h2>⚠️ 조사·어순 차이는 여기서 허용하지 않는다</h2>
 * v1 Q3 가 미확정이고 v2 5.3 이 <b>관리자가 인정 표현으로 채우는 쪽</b>으로 정리했다.
 * 「조사를 떼고 비교」처럼 보이는 규칙을 넣으면 <b>「도」와 「도시」가 같아지는</b> 식으로
 * 엉뚱한 정답이 생긴다 — 넓히는 것은 사람이 판단한다.
 */
public final class VocabGrader {

    private VocabGrader() {
    }

    /**
     * 주관식 정오.
     *
     * @param candidates {@code vocab_meaning} 의 정답 후보 — {@code SPLIT} + {@code MANUAL}
     * @return 후보 중 하나와 일치하면 {@code true}. ★ 빈 답은 항상 {@code false} 다
     */
    public static boolean isCorrect(String given, Collection<String> candidates) {
        String normalized = MeaningSplitter.normalize(given);
        if (normalized.isBlank()) {
            // ⚠️ 무응답을 정답으로 보면 안 된다. 후보가 비어 있어도 그렇다 —
            //    뜻이 없는 단어(원고 오류)가 있고, 그 문항은 아무도 맞힐 수 없는 것이 맞다
            return false;
        }
        return candidates.stream()
                .map(MeaningSplitter::normalize)
                .anyMatch(normalized::equals);
    }

    /**
     * 객관식 정오.
     *
     * <p>★ <b>인덱스로 받지 않고 고른 문구로 받는다.</b> 보기 순서를 섞어 내리므로
     * 인덱스는 앱과 서버가 어긋날 수 있고(재요청·캐시), 그러면 <b>맞게 고른 학생이
     * 틀린다.</b> {@code choices} 스냅샷이 저장돼 있어 문구 비교가 가능하다.
     */
    public static boolean isCorrectChoice(String given, String answerText) {
        return MeaningSplitter.normalize(given)
                .equals(MeaningSplitter.normalize(answerText));
    }
}
