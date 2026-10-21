package com.dlab.domain.vocab.service;

import com.dlab.domain.vocab.entity.VocabWord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 객관식 보기 추출 (v2 6.1).
 *
 * <h2>★★ 같은 유닛만으로는 모자란다</h2>
 * 같은 유닛에서 <b>같은 품사</b>끼리 뽑는 것이 자연스럽다 — 동사 문제에 명사 보기가
 * 섞이면 답이 쉽게 보인다. 그런데 실측하니 <b>같은 유닛·같은 품사로 보기 4개를 못 채우는
 * 조합이 28건</b>이다(부사·접속사·전치사가 한 유닛에 1개뿐인 경우가 많다).
 *
 * <pre>
 * 1) 같은 유닛 · 같은 품사에서 오답 3개
 * 2) 모자라면 다른 유닛 · 같은 품사에서 채운다
 * 3) 그래도 모자라면 품사를 풀고 전체에서 채운다   (c·p 는 전체에도 적다)
 * </pre>
 *
 * <h2>★ 품사는 첫 품사를 쓴다</h2>
 * 복합 품사가 <b>18.7%</b>다({@code v; n}) — 단일 값으로 가정하면 그 단어들이 서로
 * 보기로 안 뽑힌다.
 *
 * <h2>★★ 같은 뜻을 가진 단어는 오답으로 뽑지 않는다</h2>
 * 유의어가 오답에 섞이면 <b>정답이 둘이 된다.</b> 학생이 맞는 뜻을 고르고도 틀린다 —
 * 그러면 그 문항은 아무것도 측정하지 못한다.
 */
public final class ChoicePicker {

    private ChoicePicker() {
    }

    /**
     * 한 문항의 보기.
     *
     * @param texts   보기 문구. ★ <b>섞여 있다</b> — 정답 위치가 고정이면 학생이 눌러서 찾는다
     * @param answerIndex 정답 위치. ⚠️ <b>앱으로 내리지 말 것</b>(v1 4.2) — 서버만 안다
     */
    public record Choices(List<String> texts, int answerIndex) {
    }

    /**
     * @param answer    정답 단어
     * @param sameUnit  같은 유닛의 단어들(정답 포함 가능 — 걸러낸다)
     * @param allWords  전체 단어. 2·3단계에서 쓴다
     * @param size      보기 수(정답 1 + 오답 size-1)
     * @param random    섞기용. ★ 테스트가 고정 시드를 넣는다
     */
    public static Choices pick(VocabWord answer, Collection<VocabWord> sameUnit,
                               Collection<VocabWord> allWords, int size, Random random) {
        String answerText = firstMeaning(answer);
        // ★★ 정답과 같은 뜻을 가진 단어는 오답에서 제외한다 — 정답이 둘이 된다
        Set<String> blocked = new LinkedHashSet<>();
        blocked.add(answerText);
        blocked.addAll(synonymsOf(answer));

        List<String> wrong = new ArrayList<>();
        int need = Math.max(0, size - 1);

        // 1) 같은 유닛 · 같은 품사
        collect(wrong, need, sameUnit, answer, blocked, true);
        // 2) 다른 유닛 · 같은 품사
        if (wrong.size() < need) {
            collect(wrong, need, allWords, answer, blocked, true);
        }
        // 3) 품사를 풀고 전체. ⚠️ c·p 는 전체에도 적어 여기까지 온다
        if (wrong.size() < need) {
            collect(wrong, need, allWords, answer, blocked, false);
        }

        List<String> texts = new ArrayList<>(wrong);
        texts.add(answerText);
        java.util.Collections.shuffle(texts, random);
        return new Choices(List.copyOf(texts), texts.indexOf(answerText));
    }

    private static void collect(List<String> into, int need, Collection<VocabWord> pool,
                                VocabWord answer, Set<String> blocked, boolean samePos) {
        String pos = answer.primaryPos();
        for (VocabWord candidate : pool) {
            if (into.size() >= need) {
                return;
            }
            if (candidate.getId() != null && candidate.getId().equals(answer.getId())) {
                continue;
            }
            if (!candidate.isActive()) {
                continue;   // 출제에서 뺀 단어는 보기로도 쓰지 않는다
            }
            if (samePos) {
                // ⚠️ 품사가 빈 단어가 1건 있다(beneath) — 3단계로 떨어진다
                if (pos == null || !pos.equals(candidate.primaryPos())) {
                    continue;
                }
            }
            String text = firstMeaning(candidate);
            if (text.isBlank() || blocked.contains(text) || into.contains(text)) {
                continue;
            }
            into.add(text);
        }
    }

    /**
     * 보기에 쓸 뜻 — <b>첫 후보</b>다.
     *
     * <p>★ 원고 뜻 전체({@code 기업, 회사; 확고한, 단단한})를 보기에 넣으면 길어서 읽히지
     * 않고, <b>품사가 다른 뜻이 한 보기에 섞인다.</b> 쪼갠 첫 후보를 쓴다.
     */
    private static String firstMeaning(VocabWord word) {
        var split = MeaningSplitter.split(word.getMeaningRaw());
        return split.isEmpty() ? "" : split.get(0);
    }

    /**
     * 유의어 열에서 뽑은 뜻 — 오답에서 제외할 대상이다.
     *
     * <p>유의어 열은 <b>영어 단어</b>가 들어 있다({@code economic}). 그 단어가 교재에 있으면
     * 뜻이 겹칠 수 있으니, 여기서는 <b>문자열 그대로</b> 담아 두고 뜻 비교는
     * {@link #collect} 가 한다 — 유의어 단어의 뜻까지 찾으려면 조회가 한 번 더 늘고,
     * 그 이득이 크지 않다.
     */
    private static Set<String> synonymsOf(VocabWord word) {
        if (word.getSynonym() == null || word.getSynonym().isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : word.getSynonym().split("[,;]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
