package com.app.knowledge.ingestion;

/**
 * 启发式 token 估算：中文 1 字≈1 token、ASCII 4 字符≈1 token、其他 2 字符≈1 token。
 *
 * <p>**结果只用于界面展示分块规模，不参与任何逻辑判断**（PRD §2 明确不做精确 token
 * 计数）。真要精确就得引入具体模型的分词器，而不同模型的分词结果本来就不一样——为了
 * 一个"这块大概多大"的展示值付这个代价不值得。
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        int cjk = 0;
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint < 128) {
                ascii++;
            } else if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + ceilDiv(ascii, 4) + ceilDiv(other, 2);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
