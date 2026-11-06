package com.agentcore.domain.shared.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 文件路径的解析：工具参数里的写法 → 绝对路径；解析符号链接后的真实路径。 */
public final class FilePaths {

    private static final String HOME_PREFIX = "~/";

    private FilePaths() {
    }

    /** {@code ~/x} → 用户主目录下的 x；其他写法原样返回。 */
    public static String expandHome(String path) {
        return path.startsWith(HOME_PREFIX) ? System.getProperty("user.home") + path.substring(1) : path;
    }

    /** 工具参数里的路径 → 绝对路径：支持 ~/ 开头，相对路径按工作目录解析，去掉 . 和 ..（不跟随符号链接）。 */
    public static Path resolve(Path workingDirectory, String path) {
        Path candidate = Path.of(expandHome(path));
        return (candidate.isAbsolute() ? candidate : workingDirectory.resolve(candidate)).normalize().toAbsolutePath();
    }

    /**
     * 解析符号链接后的真实路径。路径本身不存在时（例如要新建的文件），解析离它最近的、存在的上级目录，再接上剩下的部分 ——
     * 否则 {@code link/还不存在的文件} 看起来在工作目录里，而 link 其实指向外面。读不了时返回规范化后的路径。
     */
    public static Path realPath(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path.normalize();
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(path)).normalize();
        } catch (IOException e) {
            return path.normalize();
        }
    }

    /** 统一用 / 作分隔符：规则里写的是 /，Windows 的路径用的是 \。 */
    public static String withForwardSlashes(Path path) {
        return path.toString().replace('\\', '/');
    }
}
