package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/** 搜索文件的工具：glob（按文件名）、grep（按内容，有 ripgrep 就用它，没有就用纯 Java 实现）。 */
public final class SearchTools {

    /** 遍历目录时跳过的目录：版本库、依赖、构建产物、缓存。 */
    static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", ".venv", "venv", "__pycache__", "target", "build",
            "dist", ".idea", ".gradle", ".agentcore", ".mypy_cache", ".pytest_cache");
    private static final int MAX_GLOB_RESULTS = 200;
    private static final int DEFAULT_GREP_RESULTS = 200;
    /** 比工具本身的 2 分钟超时短一点，超时时还能给出可读的错误。 */
    private static final long RIPGREP_TIMEOUT_SECONDS = 110;

    private SearchTools() {
    }

    // ------------------------------------------------------------------ glob

    public static Tool glob() {
        return FunctionTool.builder("glob")
                .description("Find files by glob pattern (e.g. \"**/*.java\", \"src/*.ts\"). Results are sorted by modification time, newest first.")
                .schema(Schemas.object()
                        .string("pattern", "Glob pattern relative to path. Use ** for recursive matching.", true)
                        .string("path", "Directory to search in (default: working directory).", false)
                        .build())
                .readOnly(true)
                .handler(SearchTools::findFiles)
                .build();
    }

    private static ToolOutput findFiles(Map<String, Object> args, ToolContext ctx) throws IOException {
        Path root = ctx.resolve(ToolArgs.string(args, "path", "."));
        if (!Files.isDirectory(root)) {
            throw new ToolException("Not a directory: " + root);
        }
        String pattern = (String) args.get("pattern");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        // Java 的 "**/*.java" 不匹配根目录下的文件，再用去掉 "**/" 的模式补一次
        PathMatcher topLevel = pattern.startsWith("**/") ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)) : null;
        List<Path> hits = new ArrayList<>();
        walk(root, file -> {
            Path relative = root.relativize(file);
            if (matcher.matches(relative) || (topLevel != null && topLevel.matches(relative))) {
                hits.add(file);
            }
        });
        if (hits.isEmpty()) {
            return ToolOutput.text("No files matched.");
        }
        hits.sort(Comparator.comparing(FileTools::modifiedTime).reversed());
        String listing = hits.stream().limit(MAX_GLOB_RESULTS)
                .map(hit -> root.equals(ctx.cwd()) ? root.relativize(hit).toString() : hit.toString())
                .collect(Collectors.joining("\n"));
        String more = hits.size() > MAX_GLOB_RESULTS ? "\n... (" + (hits.size() - MAX_GLOB_RESULTS) + " more)" : "";
        return ToolOutput.text(listing + more);
    }

    // ------------------------------------------------------------------ grep

    /** grep 的三种输出：只列文件名、列出匹配行、每个文件的匹配数。 */
    private enum OutputMode {
        FILES, CONTENT, COUNT;

        static OutputMode of(String value) {
            return switch (value) {
                case "content" -> CONTENT;
                case "count" -> COUNT;
                default -> FILES;
            };
        }
    }

    private record GrepQuery(Path root, String pattern, String glob, boolean ignoreCase, OutputMode mode) {
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
                .handler(SearchTools::searchContents)
                .build();
    }

    private static ToolOutput searchContents(Map<String, Object> args, ToolContext ctx) throws Exception {
        Path root = ctx.resolve(ToolArgs.string(args, "path", "."));
        if (!Files.exists(root)) {
            throw new ToolException("Path not found: " + root);
        }
        GrepQuery query = new GrepQuery(root, (String) args.get("pattern"), (String) args.get("glob"),
                ToolArgs.flag(args, "ignore_case"), OutputMode.of(ToolArgs.string(args, "output", "files")));
        int maxResults = ToolArgs.integer(args, "max_results", DEFAULT_GREP_RESULTS);
        List<String> lines = ripgrep(query);
        if (lines == null) {
            lines = javaGrep(query);
        }
        if (lines.isEmpty()) {
            return ToolOutput.text("No matches.");
        }
        // 工作目录下的路径显示成相对路径，省 token
        String prefix = ctx.cwd() + File.separator;
        List<String> shown = lines.stream().limit(maxResults)
                .map(line -> line.startsWith(prefix) ? line.substring(prefix.length()) : line)
                .toList();
        String more = lines.size() > maxResults ? "\n... (" + (lines.size() - maxResults) + " more lines)" : "";
        return ToolOutput.text(String.join("\n", shown) + more);
    }

    /** 用 ripgrep 搜索；没装 rg 时返回 null，由调用方退回到 Java 实现。 */
    private static List<String> ripgrep(GrepQuery query) throws Exception {
        List<String> command = new ArrayList<>(List.of("rg", "--color=never", "--no-heading"));
        if (query.ignoreCase()) {
            command.add("-i");
        }
        if (query.glob() != null) {
            command.addAll(List.of("--glob", query.glob()));
        }
        SKIP_DIRS.forEach(dir -> command.addAll(List.of("--glob", "!" + dir)));
        command.add(switch (query.mode()) {
            case COUNT -> "-c";
            case CONTENT -> "-n";
            case FILES -> "-l";
        });
        command.addAll(List.of("-e", query.pattern(), query.root().toString()));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException notInstalled) {
            return null;
        }
        try {
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(RIPGREP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ToolException("grep timed out");
            }
            if (process.exitValue() > 1) { // 0 = 有匹配，1 = 没匹配，其他 = 出错
                throw new ToolException("rg failed: " + err.strip());
            }
            return out.lines().toList();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    /** 纯 Java 实现，输出格式与 ripgrep 一致（path、path:line:text、path:count）。读不了的文件跳过。 */
    private static List<String> javaGrep(GrepQuery query) throws IOException {
        Pattern regex;
        try {
            regex = Pattern.compile(query.pattern(), query.ignoreCase() ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("invalid regex: " + e.getMessage());
        }
        PathMatcher nameFilter = query.glob() == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + query.glob());
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(query.root())) {
            files.add(query.root());
        } else {
            walk(query.root(), files::add);
        }
        List<String> results = new ArrayList<>();
        for (Path file : files) {
            if (nameFilter != null && !nameFilter.matches(file.getFileName())) {
                continue;
            }
            List<String> content;
            try {
                content = Files.readAllLines(file);
            } catch (IOException | UncheckedIOException unreadable) {
                continue;
            }
            int matches = 0;
            for (int i = 0; i < content.size(); i++) {
                if (regex.matcher(content.get(i)).find()) {
                    matches++;
                    if (query.mode() == OutputMode.CONTENT) {
                        results.add(file + ":" + (i + 1) + ":" + content.get(i));
                    }
                }
            }
            if (matches > 0 && query.mode() == OutputMode.FILES) {
                results.add(file.toString());
            } else if (matches > 0 && query.mode() == OutputMode.COUNT) {
                results.add(file + ":" + matches);
            }
        }
        return results;
    }

    /** 递归遍历普通文件，跳过 SKIP_DIRS 里的目录（root 本身除外）和读不了的文件。 */
    static void walk(Path root, Consumer<Path> onFile) throws IOException {
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
}
