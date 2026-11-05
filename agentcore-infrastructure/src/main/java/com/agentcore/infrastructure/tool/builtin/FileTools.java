package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.conversation.message.ContentBlock;
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
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 文件系统工具。安全约束在代码里强制执行，而不只靠提示词：
 * 覆盖或编辑已有文件前必须先 read_file；文件读取后被外部修改过（mtime 变化）则拒绝写入。
 */
public final class FileTools {

    static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", ".venv", "venv", "__pycache__", "target", "build",
            "dist", ".idea", ".gradle", ".agentcore", ".mypy_cache", ".pytest_cache");
    private static final Map<String, String> IMAGE_TYPES = Map.of(".png", "image/png", ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg", ".gif", "image/gif", ".webp", "image/webp");
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_LINE = 2000;

    private FileTools() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Instant> reads(ToolContext ctx) {
        return (Map<String, Instant>) ctx.state().computeIfAbsent("file_reads", k -> new ConcurrentHashMap<String, Instant>());
    }

    private static Instant mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void markRead(Path p, ToolContext ctx) {
        reads(ctx).put(p.toString(), mtime(p));
    }

    static void checkFresh(Path p, ToolContext ctx) {
        if (!Files.exists(p)) {
            return;
        }
        Instant seen = reads(ctx).get(p.toString());
        if (seen == null) {
            throw new ToolException("File " + p + " exists but has not been read in this session. Call read_file on it first.");
        }
        if (!seen.equals(mtime(p))) {
            throw new ToolException("File " + p + " was modified since it was last read. Call read_file again before writing.");
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

    private static ToolOutput read(Map<String, Object> a, ToolContext ctx) throws IOException {
        Path p = ctx.resolve((String) a.get("path"));
        if (!Files.exists(p)) {
            throw new ToolException("File not found: " + p);
        }
        if (Files.isDirectory(p)) {
            List<String> entries;
            try (var s = Files.list(p)) {
                entries = s.sorted(Comparator.comparing((Path x) -> !Files.isDirectory(x)).thenComparing(Path::getFileName))
                        .map(x -> x.getFileName() + (Files.isDirectory(x) ? "/" : "")).toList();
            }
            String more = entries.size() > 500 ? "\n... (" + (entries.size() - 500) + " more)" : "";
            return ToolOutput.text("Directory " + p + ":\n" + String.join("\n", entries.subList(0, Math.min(500, entries.size()))) + more);
        }
        String name = p.getFileName().toString().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        if (IMAGE_TYPES.containsKey(ext)) {
            long size = Files.size(p);
            if (size > MAX_IMAGE_BYTES) {
                throw new ToolException("Image too large (" + size + " bytes, max " + MAX_IMAGE_BYTES + ").");
            }
            String data = Base64.getEncoder().encodeToString(Files.readAllBytes(p));
            markRead(p, ctx);
            return new ToolOutput(List.of(new TextBlock("Image " + p + " (" + size + " bytes):"),
                    ImageBlock.base64(IMAGE_TYPES.get(ext), data)), false, Map.of("path", p.toString()));
        }
        byte[] raw = Files.readAllBytes(p);
        for (int i = 0; i < Math.min(raw.length, 8192); i++) {
            if (raw[i] == 0) {
                throw new ToolException(p + " looks like a binary file; refusing to print it.");
            }
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(java.nio.ByteBuffer.wrap(raw)).toString();
        List<String> lines = text.lines().toList();
        markRead(p, ctx);
        if (lines.isEmpty()) {
            return ToolOutput.text("(file " + p + " is empty)");
        }
        int offset = Math.max(1, num(a.get("offset"), 1));
        int limit = Math.max(1, num(a.get("limit"), 2000));
        int end = Math.min(lines.size(), offset - 1 + limit);
        StringBuilder sb = new StringBuilder();
        for (int i = offset - 1; i < end; i++) {
            String line = lines.get(i);
            if (line.length() > MAX_LINE) {
                line = line.substring(0, MAX_LINE) + " …[line truncated]";
            }
            sb.append(String.format("%6d\t%s%n", i + 1, line));
        }
        if (end < lines.size()) {
            sb.append("... (").append(lines.size() - end).append(" more lines; use offset=").append(end + 1).append(" to continue)");
        }
        return ToolOutput.text(sb.toString().stripTrailing(), Map.of("path", p.toString(), "lines", lines.size()));
    }

    // ------------------------------------------------------------------ write_file / edit_file

    public static Tool writeFile() {
        return FunctionTool.builder("write_file")
                .description("Create a new file or completely overwrite an existing one. Prefer edit_file for small changes to existing files.")
                .schema(Schemas.object()
                        .string("path", "File path, absolute or relative to the working directory.", true)
                        .string("content", "The full content to write.", true)
                        .build())
                .handler((a, ctx) -> {
                    Path p = ctx.resolve((String) a.get("path"));
                    boolean existed = Files.exists(p);
                    checkFresh(p, ctx);
                    if (p.getParent() != null) {
                        Files.createDirectories(p.getParent());
                    }
                    String content = (String) a.get("content");
                    Files.writeString(p, content);
                    markRead(p, ctx);
                    long n = content.isEmpty() ? 0 : content.lines().count();
                    return ToolOutput.text((existed ? "Updated " : "Created ") + p + " (" + n + " lines).");
                }).build();
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
                .handler((a, ctx) -> {
                    Path p = ctx.resolve((String) a.get("path"));
                    if (!Files.exists(p)) {
                        throw new ToolException("File not found: " + p + ". Use write_file to create new files.");
                    }
                    checkFresh(p, ctx);
                    String oldS = (String) a.get("old_string");
                    String newS = (String) a.get("new_string");
                    boolean all = Boolean.TRUE.equals(a.get("replace_all"));
                    if (oldS.equals(newS)) {
                        throw new ToolException("old_string and new_string are identical; nothing to do.");
                    }
                    String text = Files.readString(p);
                    int count = countOf(text, oldS);
                    if (count == 0) {
                        throw new ToolException("old_string was not found in the file. Re-read the file and copy the text exactly.");
                    }
                    if (count > 1 && !all) {
                        throw new ToolException("old_string occurs " + count + " times. Add more context to make it unique, or set replace_all=true.");
                    }
                    int first = text.indexOf(oldS);
                    String updated = all ? text.replace(oldS, newS) : text.substring(0, first) + newS + text.substring(first + oldS.length());
                    Files.writeString(p, updated);
                    markRead(p, ctx);
                    long line = text.substring(0, first).chars().filter(c -> c == '\n').count() + 1;
                    return ToolOutput.text("Edited " + p + ": replaced " + (all ? count : 1) + " occurrence(s) starting at line " + line + ".");
                }).build();
    }

    private static int countOf(String text, String sub) {
        int n = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            n++;
            idx += sub.length();
        }
        return n;
    }

    // ------------------------------------------------------------------ glob / grep

    public static Tool glob() {
        return FunctionTool.builder("glob")
                .description("Find files by glob pattern (e.g. \"**/*.java\", \"src/*.ts\"). Results are sorted by modification time, newest first.")
                .schema(Schemas.object()
                        .string("pattern", "Glob pattern relative to path. Use ** for recursive matching.", true)
                        .string("path", "Directory to search in (default: working directory).", false)
                        .build())
                .readOnly(true)
                .handler((a, ctx) -> {
                    Path root = ctx.resolve(a.get("path") instanceof String s ? s : ".");
                    if (!Files.isDirectory(root)) {
                        throw new ToolException("Not a directory: " + root);
                    }
                    String pattern = (String) a.get("pattern");
                    PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                    PathMatcher m2 = pattern.startsWith("**/") ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)) : null;
                    List<Path> hits = new ArrayList<>();
                    walk(root, f -> {
                        Path rel = root.relativize(f);
                        if (m.matches(rel) || (m2 != null && m2.matches(rel))) {
                            hits.add(f);
                        }
                    });
                    if (hits.isEmpty()) {
                        return ToolOutput.text("No files matched.");
                    }
                    hits.sort(Comparator.comparing(FileTools::mtime).reversed());
                    String body = hits.stream().limit(200).map(h -> root.equals(ctx.cwd()) ? root.relativize(h).toString() : h.toString())
                            .collect(Collectors.joining("\n"));
                    return ToolOutput.text(body + (hits.size() > 200 ? "\n... (" + (hits.size() - 200) + " more)" : ""));
                }).build();
    }

    public static Tool grep() {
        return FunctionTool.builder("grep")
                .description("Search file contents with a regular expression (uses ripgrep when available).")
                .schema(Schemas.object()
                        .string("pattern", "Regular expression to search for.", true)
                        .string("path", "File or directory to search (default: working directory).", false)
                        .string("glob", "Optional filename glob filter, e.g. \"*.java\".", false)
                        .bool("ignore_case", "Case-insensitive search.", false)
                        .enumOf("output", List.of("files", "content", "count"),
                                "\"files\" lists matching files, \"content\" shows matching lines, \"count\" shows counts per file.", false)
                        .integer("max_results", "Maximum number of result lines (default 200).", false)
                        .build())
                .readOnly(true)
                .timeout(Duration.ofMinutes(2))
                .handler(FileTools::grepImpl)
                .build();
    }

    private static ToolOutput grepImpl(Map<String, Object> a, ToolContext ctx) throws Exception {
        Path root = ctx.resolve(a.get("path") instanceof String s ? s : ".");
        if (!Files.exists(root)) {
            throw new ToolException("Path not found: " + root);
        }
        String pattern = (String) a.get("pattern");
        String glob = (String) a.get("glob");
        boolean ic = Boolean.TRUE.equals(a.get("ignore_case"));
        String output = a.get("output") instanceof String o ? o : "files";
        int max = num(a.get("max_results"), 200);
        List<String> lines = ripgrep(root, pattern, glob, ic, output);
        if (lines == null) {
            lines = javaGrep(root, pattern, glob, ic, output);
        }
        if (lines.isEmpty()) {
            return ToolOutput.text("No matches.");
        }
        String prefix = ctx.cwd() + java.io.File.separator;
        List<String> shown = lines.stream().limit(max).map(l -> l.startsWith(prefix) ? l.substring(prefix.length()) : l).toList();
        return ToolOutput.text(String.join("\n", shown) + (lines.size() > max ? "\n... (" + (lines.size() - max) + " more lines)" : ""));
    }

    private static List<String> ripgrep(Path root, String pattern, String glob, boolean ic, String output) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("rg", "--color=never", "--no-heading"));
        if (ic) {
            cmd.add("-i");
        }
        if (glob != null) {
            cmd.addAll(List.of("--glob", glob));
        }
        SKIP_DIRS.forEach(d -> cmd.addAll(List.of("--glob", "!" + d)));
        switch (output) {
            case "count" -> cmd.add("-c");
            case "content" -> cmd.add("-n");
            default -> cmd.add("-l");
        }
        cmd.addAll(List.of("-e", pattern, root.toString()));
        Process proc;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        } catch (IOException e) {
            return null; // 没装 rg
        }
        try {
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(110, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new ToolException("grep timed out");
            }
            if (proc.exitValue() > 1) {
                throw new ToolException("rg failed: " + err.strip());
            }
            return out.lines().toList();
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            throw e;
        }
    }

    private static List<String> javaGrep(Path root, String pattern, String glob, boolean ic, String output) throws IOException {
        Pattern rx;
        try {
            rx = Pattern.compile(pattern, ic ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("invalid regex: " + e.getMessage());
        }
        PathMatcher gm = glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<String> out = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            files.add(root);
        } else {
            walk(root, files::add);
        }
        for (Path f : files) {
            if (gm != null && !gm.matches(f.getFileName())) {
                continue;
            }
            List<String> content;
            try {
                content = Files.readAllLines(f);
            } catch (IOException | UncheckedIOException e) {
                continue;
            }
            int hits = 0;
            for (int i = 0; i < content.size(); i++) {
                if (rx.matcher(content.get(i)).find()) {
                    hits++;
                    if (output.equals("content")) {
                        out.add(f + ":" + (i + 1) + ":" + content.get(i));
                    }
                }
            }
            if (hits > 0 && output.equals("files")) {
                out.add(f.toString());
            } else if (hits > 0 && output.equals("count")) {
                out.add(f + ":" + hits);
            }
        }
        return out;
    }

    static void walk(Path root, java.util.function.Consumer<Path> onFile) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    onFile.accept(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static int num(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }

    static List<ContentBlock> textOnly(String s) {
        return List.of(new TextBlock(s));
    }
}
