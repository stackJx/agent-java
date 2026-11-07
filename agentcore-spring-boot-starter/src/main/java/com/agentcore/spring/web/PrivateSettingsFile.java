package com.agentcore.spring.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * 只有当前用户能读写的配置文件（里面有明文 API key）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>文件路径上不能有符号链接，文件不能有多个硬链接 —— 防止被链接到别处，读写了不该碰的文件；</li>
 *   <li>文件权限必须是 600、所在目录必须是 700（不允许组和其他用户访问）。新建时直接这样创建；
 *       已经存在、但权限更宽的目录或文件不会被悄悄改掉，而是报错，由用户自己确认；</li>
 *   <li>写入先写临时文件、刷到磁盘，再原子地替换，写到一半失败也不会留下损坏的文件。</li>
 * </ul>
 * 不满足时抛 {@link Problem}，里面是给人看的具体原因（不含文件内容）。
 */
final class PrivateSettingsFile {

    /** 配置文件的大小上限，防止误把大文件当成配置读进内存。 */
    static final int MAX_FILE_BYTES = 1_048_576;
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");

    /** 文件不满足安全约束或读写失败；reason 是固定格式的说明，可以直接展示给管理员。 */
    static final class Problem extends Exception {
        Problem(String reason) {
            super(reason);
        }
    }

    private final Path file;

    PrivateSettingsFile(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Path path() {
        return file;
    }

    /** 读出文件内容；文件还不存在时返回空。 */
    Optional<String> read() throws Problem {
        rejectSymbolicLinks(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        requireOwnerOnlyDirectory(file.getParent());
        requireOwnerOnlyFile(file);
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new Problem("file is larger than 1 MB");
            }
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new Problem("file could not be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 原子地写入新内容。 */
    void write(String content) throws Problem {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FILE_BYTES) {
            throw new Problem("settings would be larger than 1 MB");
        }
        rejectSymbolicLinks(file);
        Path directory = file.getParent();
        createOwnerOnlyDirectoryIfMissing(directory);
        requireOwnerOnlyDirectory(directory);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            requireOwnerOnlyFile(file);
        }
        Path temporary = null;
        try {
            temporary = createOwnerOnlyTemporaryFile(directory);
            writeAndSync(temporary, data);
            // 不提供非原子的退路：不支持原子替换的文件系统上宁可报错
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (IOException e) {
            throw new Problem("file could not be written (" + e.getClass().getSimpleName() + ")");
        } finally {
            deleteQuietly(temporary);
        }
    }

    // ------------------------------------------------------------------ 安全检查

    /** 路径本身和每一级上级目录都不能是符号链接。 */
    private static void rejectSymbolicLinks(Path path) throws Problem {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new Problem("path contains a symbolic link: " + current);
            }
        }
    }

    private static void requireOwnerOnlyDirectory(Path directory) throws Problem {
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new Problem("parent is not a directory: " + directory);
        }
        if (allowsGroupOrOthers(directory)) {
            throw new Problem("directory " + directory + " must only be accessible by its owner (chmod 700)");
        }
    }

    private static void requireOwnerOnlyFile(Path path) throws Problem {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new Problem("not a regular file");
        }
        if (hasMultipleHardLinks(path)) {
            throw new Problem("file has more than one hard link");
        }
        if (allowsGroupOrOthers(path)) {
            throw new Problem("file must only be readable by its owner (chmod 600)");
        }
    }

    private static boolean hasMultipleHardLinks(Path path) throws Problem {
        try {
            if (!Files.getFileStore(path).supportsFileAttributeView("unix")) {
                return false;
            }
            Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return ((Number) links).longValue() != 1;
        } catch (IOException e) {
            throw new Problem("file attributes could not be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 不支持 POSIX 权限的文件系统（例如 Windows）上不检查。 */
    private static boolean allowsGroupOrOthers(Path path) throws Problem {
        try {
            if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
                return false;
            }
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
                    .anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"));
        } catch (IOException e) {
            throw new Problem("permissions of " + path + " could not be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ------------------------------------------------------------------ 创建与写入

    /** 缺的目录逐级创建成 700；已经存在的上级目录不动它的权限。 */
    private static void createOwnerOnlyDirectoryIfMissing(Path directory) throws Problem {
        if (directory == null || directory.getParent() == null) {
            throw new Problem("settings file must not be in the file system root");
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        createOwnerOnlyDirectoryIfMissing(directory.getParent());
        try {
            if (Files.getFileStore(directory.getParent()).supportsFileAttributeView("posix")) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
            } else {
                Files.createDirectory(directory);
            }
        } catch (IOException e) {
            throw new Problem("directory " + directory + " could not be created (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Path createOwnerOnlyTemporaryFile(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            return Files.createTempFile(directory, ".web-models-", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE));
        }
        return Files.createTempFile(directory, ".web-models-", ".tmp");
    }

    private static void writeAndSync(Path target, byte[] data) throws IOException {
        try (FileChannel output = FileChannel.open(target, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(data);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
            output.force(true);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响结果
        }
    }
}
