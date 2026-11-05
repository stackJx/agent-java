package com.agentcore.domain.shared;

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
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '[' -> {
                    int end = glob.indexOf(']', i + 1);
                    if (end < 0) {
                        sb.append("\\[");
                    } else {
                        String body = glob.substring(i + 1, end);
                        if (body.startsWith("!")) {
                            body = "^" + body.substring(1);
                        }
                        sb.append('[').append(body.replace("\\", "\\\\")).append(']');
                        i = end;
                    }
                }
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }
}
