package com.agentcore.domain.shared.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** fnmatch 风格的通配符匹配：{@code *} 匹配任意字符（包括 /），{@code ?} 匹配单个字符，{@code [...]} 字符集。 */
public final class GlobPattern {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private GlobPattern() {
    }

    public static boolean matches(String glob, String text) {
        if (glob == null || text == null) {
            return false;
        }
        return CACHE.computeIfAbsent(glob, GlobPattern::compile).matcher(text).matches();
    }

    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    int end = glob.indexOf(']', i + 1);
                    if (end < 0) {
                        regex.append("\\[");
                    } else {
                        String body = glob.substring(i + 1, end);
                        if (body.startsWith("!")) {
                            body = "^" + body.substring(1);
                        }
                        regex.append('[').append(body.replace("\\", "\\\\")).append(']');
                        i = end;
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
