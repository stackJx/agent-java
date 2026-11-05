package com.agentcore.domain.shared;

/** 文本工具。 */
public final class Texts {

    private Texts() {
    }

    /** 保留头尾、中间截断（报错信息通常在末尾，尾部也要保留）。 */
    public static String truncateMiddle(String text, int limit) {
        if (text == null || limit <= 0 || text.length() <= limit) {
            return text;
        }
        int head = (int) (limit * 0.6);
        int tail = limit - head;
        int omitted = text.length() - head - tail;
        return text.substring(0, head) + "\n\n... [已截断 " + omitted + " 个字符 / truncated " + omitted + " chars] ...\n\n"
                + text.substring(text.length() - tail);
    }

    public static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace("\n", " ⏎ ");
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, Math.max(0, limit - 1)) + "…";
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
