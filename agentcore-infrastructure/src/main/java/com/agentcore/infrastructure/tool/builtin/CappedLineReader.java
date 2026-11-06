package com.agentcore.infrastructure.tool.builtin;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 逐行读文本文件，每行最多保留 maxChars 个字符（多出的部分读过去、丢掉）。
 * 文件多大、单行多长（例如压缩过的 JS）都不会把整个文件或整行读进内存。
 * 换行符与 {@link String#lines()} 相同：\n、\r、\r\n。非法的 UTF-8 字节替换成 �，不报错。
 */
final class CappedLineReader implements Closeable {

    /** 一行：保留下来的文字，以及是否被截断过。 */
    record Line(String text, boolean truncated) {
    }

    private static final int END_OF_STREAM = -1;

    private final PushbackReader reader;
    private final int maxChars;

    CappedLineReader(InputStream in, int maxChars) {
        InputStreamReader decoder = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE));
        this.reader = new PushbackReader(new BufferedReader(decoder), 1);
        this.maxChars = maxChars;
    }

    /** 下一行；读完了返回 null。文件以换行结尾时，最后不会多出一个空行（与 String.lines() 一致）。 */
    Line next() throws IOException {
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        boolean readAnything = false;
        int ch;
        while ((ch = reader.read()) != END_OF_STREAM) {
            readAnything = true;
            if (ch == '\n') {
                return new Line(text.toString(), truncated);
            }
            if (ch == '\r') {
                skipLineFeedAfterCarriageReturn();
                return new Line(text.toString(), truncated);
            }
            if (text.length() < maxChars) {
                text.append((char) ch);
            } else {
                truncated = true;
            }
        }
        return readAnything ? new Line(text.toString(), truncated) : null;
    }

    private void skipLineFeedAfterCarriageReturn() throws IOException {
        int next = reader.read();
        if (next != '\n' && next != END_OF_STREAM) {
            reader.unread(next);
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
