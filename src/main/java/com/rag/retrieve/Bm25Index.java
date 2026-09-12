package com.rag.retrieve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 中文 BM25 倒排索引（内存态，语料量级 ~200 条片段，构建 &lt;100ms）。
 *
 * <p><b>为什么是字符 2-gram 而不是分词器：</b>
 * 本项目只把 BM25 当作**词面补充**（语义主力是 bge-m3 向量），而在 200 条小语料上
 * 2-gram 的召回损失很小；同时它零依赖、可复现、答辩讲得清。
 * 引入 {@code jieba-analysis}（停更十年）或 {@code lucene-analyzers-smartcn}
 * （会拖进 3MB Lucene 核心）都不划算。bge-m3 只出向量、不提供分词，指望不上。
 *
 * <p><b>数字/英文必须整体保留：</b>年份、分数线、日期是高频精确匹配项，
 * 「2026」若被 2-gram 拆成 {@code 20/02/26} 会与大量无关文本糊在一起。
 */
public class Bm25Index {

    /** 索引里的一条文档：chunkId + 用于切词的文本 */
    public record Doc(long chunkId, String text) {
    }

    /** 检索命中：chunkId + BM25 分数（无上界，只在同一 query 内可比） */
    public record Hit(long chunkId, double score) {
    }

    private static final double DEFAULT_K1 = 1.2;
    private static final double DEFAULT_B = 0.75;

    private final List<Doc> docs;
    /** term -> [docIdx, tf] 列表 */
    private final Map<String, List<int[]>> postings;
    private final int[] docLen;
    private final double avgLen;
    private final double k1;
    private final double b;

    private Bm25Index(List<Doc> docs, Map<String, List<int[]>> postings, int[] docLen,
                      double avgLen, double k1, double b) {
        this.docs = docs;
        this.postings = postings;
        this.docLen = docLen;
        this.avgLen = avgLen;
        this.k1 = k1;
        this.b = b;
    }

    public static Bm25Index build(List<Doc> docs) {
        return build(docs, DEFAULT_K1, DEFAULT_B);
    }

    public static Bm25Index build(List<Doc> docs, double k1, double b) {
        List<Doc> list = docs == null ? List.of() : docs;
        Map<String, List<int[]>> postings = new HashMap<>();
        int[] docLen = new int[list.size()];
        long total = 0;

        for (int i = 0; i < list.size(); i++) {
            List<String> tokens = tokenize(list.get(i).text());
            docLen[i] = tokens.size();
            total += tokens.size();
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                postings.computeIfAbsent(e.getKey(), x -> new ArrayList<>())
                        .add(new int[]{i, e.getValue()});
            }
        }
        double avg = list.isEmpty() ? 1.0 : Math.max(1.0, (double) total / list.size());
        return new Bm25Index(list, postings, docLen, avg, k1, b);
    }

    public int size() {
        return docs.size();
    }

    /** BM25 打分并返回 topN（分数降序）。命中为空返回空列表。 */
    public List<Hit> search(String query, int topN) {
        List<String> qTokens = tokenize(query);
        if (qTokens.isEmpty() || postings.isEmpty() || topN <= 0) {
            return List.of();
        }
        int n = docs.size();
        Map<Integer, Double> scores = new HashMap<>();
        Set<String> counted = new HashSet<>();
        for (String term : qTokens) {
            if (!counted.add(term)) {
                continue;   // 同一 term 在 query 里重复出现不重复计分
            }
            List<int[]> pl = postings.get(term);
            if (pl == null || pl.isEmpty()) {
                continue;
            }
            double df = pl.size();
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            for (int[] p : pl) {
                int docIdx = p[0];
                double tf = p[1];
                double dl = docLen[docIdx];
                double denom = tf + k1 * (1 - b + b * dl / avgLen);
                scores.merge(docIdx, idf * tf * (k1 + 1) / denom, Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted((a, c) -> Double.compare(c.getValue(), a.getValue()))
                .limit(topN)
                .map(e -> new Hit(docs.get(e.getKey()).chunkId(), e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 切词：全角转半角、去空白、ASCII 小写；ASCII 字母数字串整体作为一个 token；
     * 其余（中文）按滑窗长 2 取 gram。
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String s = normalize(text);
        List<String> tokens = new ArrayList<>();
        StringBuilder cjk = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (isAsciiWord(c)) {
                int j = i;
                while (j < s.length() && isAsciiWord(s.charAt(j))) {
                    j++;
                }
                tokens.add(s.substring(i, j));
                i = j;
            } else {
                cjk.append(c);
                i++;
            }
        }
        String cs = cjk.toString();
        if (cs.length() == 1) {
            tokens.add(cs);
        }
        for (int k = 0; k + 2 <= cs.length(); k++) {
            tokens.add(cs.substring(k, k + 2));
        }
        return tokens;
    }

    /** 全角 ASCII 区间（０-９Ａ-ｚ…）转半角，全角空格转普通空格，然后去空白、ASCII 小写 */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '　') {
                sb.append(' ');
            } else if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        String noSpace = sb.toString().replaceAll("\\s+", "");
        StringBuilder lower = new StringBuilder(noSpace.length());
        for (int i = 0; i < noSpace.length(); i++) {
            char c = noSpace.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return lower.toString();
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
