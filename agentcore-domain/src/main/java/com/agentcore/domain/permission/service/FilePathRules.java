package com.agentcore.domain.permission.service;

import com.agentcore.domain.shared.util.FilePaths;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件路径类规则的匹配准备（声明了 {@code Tool#permissionPath} 的工具：read_file、write_file、glob……）。
 *
 * <p>直接拿参数里的原始写法去匹配不安全：{@code src/../../etc/passwd} 能匹配 allow 规则 {@code write_file(src/*)}，
 * {@code ../../etc/passwd} 又躲得过 deny 规则 {@code write_file(/etc/*)}。所以两边先换成同一种形式再比较：
 * <ul>
 *   <li>调用里的路径：按工作目录解析成绝对路径，去掉 . 和 ..，跟随符号链接；</li>
 *   <li>规则里的模式：相对路径按工作目录补全；第一个通配符之前的目录部分同样跟随符号链接
 *       （例如 macOS 上 /etc 其实是 /private/etc，不处理的话 deny 规则就对不上了）；
 *       以通配符开头的模式（例如 {@code *.env}）原样使用，匹配任何位置。</li>
 * </ul>
 * 分隔符统一成 /。不知道工作目录时（没有调用上下文），路径只去掉 . 和 ..，模式原样使用。
 */
final class FilePathRules {

    /** 通配符：* ? [ 。模式里第一个通配符之前的部分是确定的目录。 */
    private static final Pattern WILDCARD = Pattern.compile("[*?\\[]");
    /** Windows 的绝对路径，例如 C:/work。 */
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:/.*");
    private static final String SEPARATOR = "/";

    private FilePathRules() {
    }

    /**
     * 调用里的路径 → 用来匹配的绝对路径（工作目录未知时是去掉 . 和 .. 的相对路径）。
     * 不是合法路径时返回 null：工具执行时自己会报错，这里按原始写法匹配。
     */
    static Path resolveCallPath(String rawPath, Path workingDirectory) {
        try {
            if (workingDirectory == null) {
                return Path.of(FilePaths.expandHome(rawPath)).normalize();
            }
            return FilePaths.realPath(FilePaths.resolve(workingDirectory, rawPath));
        } catch (InvalidPathException notAPath) {
            return null;
        }
    }

    /** 规则里的模式 → 和 {@link #resolveCallPath} 同一种形式的模式。 */
    static String resolvePattern(String rawPattern, Path workingDirectory) {
        String pattern = rawPattern.replace('\\', '/');
        if (workingDirectory == null || startsWithWildcard(pattern)) {
            return pattern;
        }
        return followSymlinksBeforeFirstWildcard(toAbsolutePattern(pattern, workingDirectory));
    }

    /** 路径是否在工作目录之内（都按真实路径比较）。 */
    static boolean isInside(Path resolvedPath, Path workingDirectory) {
        Path root = FilePaths.realPath(workingDirectory.toAbsolutePath().normalize());
        return resolvedPath.startsWith(root);
    }

    private static boolean startsWithWildcard(String pattern) {
        Matcher wildcard = WILDCARD.matcher(pattern);
        return wildcard.find() && wildcard.start() == 0;
    }

    /** ~/x → 主目录下的 x；/x、C:/x 原样；其他按工作目录补全。 */
    private static String toAbsolutePattern(String pattern, Path workingDirectory) {
        String expanded = FilePaths.expandHome(pattern).replace('\\', '/');
        if (expanded.startsWith(SEPARATOR) || WINDOWS_ABSOLUTE.matcher(expanded).matches()) {
            return expanded;
        }
        return withoutTrailingSeparator(FilePaths.withForwardSlashes(workingDirectory.toAbsolutePath().normalize()))
                + SEPARATOR + expanded;
    }

    /**
     * 模式拆成"确定的目录 + 剩下的部分"，确定的目录换成真实路径再拼回去：
     * {@code /etc/*} → {@code /private/etc/*}；没有通配符时整个模式就是一个路径。
     */
    private static String followSymlinksBeforeFirstWildcard(String absolutePattern) {
        try {
            Matcher wildcard = WILDCARD.matcher(absolutePattern);
            if (!wildcard.find()) {
                return FilePaths.withForwardSlashes(FilePaths.realPath(Path.of(absolutePattern)));
            }
            int directoryEnd = absolutePattern.lastIndexOf(SEPARATOR, wildcard.start());
            String directory = absolutePattern.substring(0, directoryEnd);
            String rest = absolutePattern.substring(directoryEnd + 1);
            String realDirectory = directory.isEmpty() ? "" : FilePaths.withForwardSlashes(FilePaths.realPath(Path.of(directory)));
            return withoutTrailingSeparator(realDirectory) + SEPARATOR + rest;
        } catch (InvalidPathException notAPath) {
            return absolutePattern;
        }
    }

    private static String withoutTrailingSeparator(String path) {
        return path.endsWith(SEPARATOR) ? path.substring(0, path.length() - 1) : path;
    }
}
