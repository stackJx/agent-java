package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 读写文件的工具：read_file / write_file / edit_file。搜索类工具（glob / grep）在 {@link SearchTools}。
 *
 * <p>安全约束在代码里强制执行，而不只靠提示词：
 * 覆盖或编辑已有文件前必须先 read_file；文件读取后被外部修改过（mtime 变化）则拒绝写入。
 */
public final class FileTools {

    private static final Map<String, String> IMAGE_TYPES = Map.of(".png", "image/png", ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg", ".gif", "image/gif", ".webp", "image/webp");
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    /** 单行超过这个长度就截断，防止压缩过的 JS 之类的文件一行撑爆上下文。 */
    private static final int MAX_LINE_CHARS = 2000;
    private static final int DEFAULT_LINE_LIMIT = 2000;
    private static final int MAX_DIRECTORY_ENTRIES = 500;
    /** 前多少字节里出现 0 字节就当作二进制文件。 */
    private static final int BINARY_SNIFF_BYTES = 8192;

    private FileTools() {
    }

    // ------------------------------------------------------------------ 读取记录（先读后写）

    /** 本会话读过的文件 → 读取时的修改时间。存在会话级工具状态里，同一会话的多次运行共用。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Instant> readLog(ToolContext ctx) {
        return (Map<String, Instant>) ctx.state().computeIfAbsent("file_reads", key -> new ConcurrentHashMap<String, Instant>());
    }

    static Instant modifiedTime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void markRead(Path file, ToolContext ctx) {
        readLog(ctx).put(file.toString(), modifiedTime(file));
    }

    /** 写已有文件前检查：必须读过，并且读过之后没被别人改过。新文件不检查。 */
    static void checkFresh(Path file, ToolContext ctx) {
        if (!Files.exists(file)) {
            return;
        }
        Instant seen = readLog(ctx).get(file.toString());
        if (seen == null) {
            throw new ToolException("File " + file + " exists but has not been read in this session. Call read_file on it first.");
        }
        if (!seen.equals(modifiedTime(file))) {
            throw new ToolException("File " + file + " was modified since it was last read. Call read_file again before writing.");
        }
    }

    // ------------------------------------------------------------------ read_file

    public static Tool readFile() {
        return FunctionTool.builder("read_file")
                .description("Read a text file (with line numbers) or an image. Also lists a directory if given one.")
                .schema(Schemas.object()
                        .string("path", "File path, absolute or relative to the working directory.", true)
                        .integer("offset", "1-based line number to start from (default 1).", false)
                        .integer("limit", "Maximum number of lines to return (default 2000).", false)
                        .build())
                .readOnly(true)
                .handler(FileTools::read)
                .build();
    }

    private static ToolOutput read(Map<String, Object> args, ToolContext ctx) throws IOException {
        Path path = ctx.resolve((String) args.get("path"));
        if (!Files.exists(path)) {
            throw new ToolException("File not found: " + path);
        }
        if (Files.isDirectory(path)) {
            return listDirectory(path);
        }
        String imageType = IMAGE_TYPES.get(extension(path));
        if (imageType != null) {
            return readImage(path, imageType, ctx);
        }
        int offset = Math.max(1, ToolArgs.integer(args, "offset", 1));
        int limit = Math.max(1, ToolArgs.integer(args, "limit", DEFAULT_LINE_LIMIT));
        return readText(path, offset, limit, ctx);
    }

    /** 目录：子目录在前、文件在后，按名字排序。 */
    private static ToolOutput listDirectory(Path dir) throws IOException {
        List<String> entries;
        try (Stream<Path> children = Files.list(dir)) {
            entries = children.sorted(Comparator.comparing((Path child) -> !Files.isDirectory(child)).thenComparing(Path::getFileName))
                    .map(child -> child.getFileName() + (Files.isDirectory(child) ? "/" : ""))
                    .toList();
        }
        List<String> shown = entries.subList(0, Math.min(MAX_DIRECTORY_ENTRIES, entries.size()));
        String more = entries.size() > shown.size() ? "\n... (" + (entries.size() - shown.size()) + " more)" : "";
        return ToolOutput.text("Directory " + dir + ":\n" + String.join("\n", shown) + more);
    }

    /** 图片：作为图片块返回，模型能直接看到。 */
    private static ToolOutput readImage(Path file, String mediaType, ToolContext ctx) throws IOException {
        long size = Files.size(file);
        if (size > MAX_IMAGE_BYTES) {
            throw new ToolException("Image too large (" + size + " bytes, max " + MAX_IMAGE_BYTES + ").");
        }
        String data = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        markRead(file, ctx);
        return new ToolOutput(List.of(new TextBlock("Image " + file + " (" + size + " bytes):"), ImageBlock.base64(mediaType, data)),
                false, Map.of("path", file.toString()));
    }

    /** 文本：带行号（与 cat -n 相同的格式），从 offset 行开始最多 limit 行；没读完时提示下一次的 offset。 */
    private static ToolOutput readText(Path file, int offset, int limit, ToolContext ctx) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (looksBinary(raw)) {
            throw new ToolException(file + " looks like a binary file; refusing to print it.");
        }
        List<String> lines = decodeLenient(raw).lines().toList();
        markRead(file, ctx);
        if (lines.isEmpty()) {
            return ToolOutput.text("(file " + file + " is empty)");
        }
        int end = Math.min(lines.size(), offset - 1 + limit);
        StringBuilder numbered = new StringBuilder();
        for (int i = offset - 1; i < end; i++) {
            String line = lines.get(i);
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + " …[line truncated]";
            }
            numbered.append(String.format("%6d\t%s%n", i + 1, line));
        }
        if (end < lines.size()) {
            numbered.append("... (").append(lines.size() - end).append(" more lines; use offset=").append(end + 1).append(" to continue)");
        }
        return ToolOutput.text(numbered.toString().stripTrailing(), Map.of("path", file.toString(), "lines", lines.size()));
    }

    private static boolean looksBinary(byte[] raw) {
        for (int i = 0; i < Math.min(raw.length, BINARY_SNIFF_BYTES); i++) {
            if (raw[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 按 UTF-8 解码，非法字节替换成 �，不报错。 */
    private static String decodeLenient(byte[] raw) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(raw)).toString();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
    }

    // ------------------------------------------------------------------ write_file / edit_file

    public static Tool writeFile() {
        return FunctionTool.builder("write_file")
                .description("Create a new file or completely overwrite an existing one. Prefer edit_file for small changes to existing files.")
                .schema(Schemas.object()
                        .string("path", "File path, absolute or relative to the working directory.", true)
                        .string("content", "The full content to write.", true)
                        .build())
                .handler(FileTools::write)
                .build();
    }

    private static ToolOutput write(Map<String, Object> args, ToolContext ctx) throws IOException {
        Path file = ctx.resolve((String) args.get("path"));
        boolean existed = Files.exists(file);
        checkFresh(file, ctx);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String content = (String) args.get("content");
        Files.writeString(file, content);
        markRead(file, ctx);
        long lineCount = content.isEmpty() ? 0 : content.lines().count();
        return ToolOutput.text((existed ? "Updated " : "Created ") + file + " (" + lineCount + " lines).");
    }

    public static Tool editFile() {
        return FunctionTool.builder("edit_file")
                .description("Replace an exact string in a file. old_string must match exactly (including whitespace) and be unique unless replace_all is true.")
                .schema(Schemas.object()
                        .string("path", "File path.", true)
                        .string("old_string", "Exact text to replace. Include enough surrounding context to make it unique.", true)
                        .string("new_string", "Replacement text.", true)
                        .bool("replace_all", "Replace every occurrence instead of requiring a unique match.", false)
                        .build())
                .handler(FileTools::edit)
                .build();
    }

    private static ToolOutput edit(Map<String, Object> args, ToolContext ctx) throws IOException {
        Path file = ctx.resolve((String) args.get("path"));
        if (!Files.exists(file)) {
            throw new ToolException("File not found: " + file + ". Use write_file to create new files.");
        }
        checkFresh(file, ctx);
        String oldString = (String) args.get("old_string");
        String newString = (String) args.get("new_string");
        boolean replaceAll = ToolArgs.flag(args, "replace_all");
        if (oldString.equals(newString)) {
            throw new ToolException("old_string and new_string are identical; nothing to do.");
        }
        String text = Files.readString(file);
        int count = countOccurrences(text, oldString);
        if (count == 0) {
            throw new ToolException("old_string was not found in the file. Re-read the file and copy the text exactly.");
        }
        if (count > 1 && !replaceAll) {
            throw new ToolException("old_string occurs " + count + " times. Add more context to make it unique, or set replace_all=true.");
        }
        int first = text.indexOf(oldString);
        String updated = replaceAll ? text.replace(oldString, newString)
                : text.substring(0, first) + newString + text.substring(first + oldString.length());
        Files.writeString(file, updated);
        markRead(file, ctx);
        long line = text.substring(0, first).chars().filter(ch -> ch == '\n').count() + 1;
        return ToolOutput.text("Edited " + file + ": replaced " + (replaceAll ? count : 1) + " occurrence(s) starting at line " + line + ".");
    }

    private static int countOccurrences(String text, String part) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(part, from)) >= 0) {
            count++;
            from += part.length();
        }
        return count;
    }
}
