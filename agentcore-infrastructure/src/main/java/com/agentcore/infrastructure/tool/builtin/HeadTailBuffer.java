package com.agentcore.infrastructure.tool.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 有上限的输出缓冲：只保留开头 headLimit 个字节和结尾 tailLimit 个字节，中间的丢掉并计数。
 * 子进程输出多少都不会撑爆内存（例如 {@code yes}、刷屏的构建日志）；开头通常是命令回显，结尾通常是报错，两头都留着。
 * 线程安全：读取线程写、工具线程读。
 */
final class HeadTailBuffer {

    private static final int READ_CHUNK_BYTES = 8192;

    private final byte[] head;
    private final byte[] tail;
    private int headSize;
    /** 结尾部分是环形缓冲：tailStart 是最早那个字节的位置。 */
    private int tailStart;
    private int tailSize;
    private long totalBytes;

    HeadTailBuffer(int headLimit, int tailLimit) {
        this.head = new byte[headLimit];
        this.tail = new byte[tailLimit];
    }

    /** 把流读到结束（进程被杀时流会关闭，读取出错也就此结束）。 */
    void readFully(InputStream in) {
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        try {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                write(chunk, read);
            }
        } catch (IOException ignored) {
            // 进程被杀时流会关闭
        }
    }

    synchronized void write(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            if (headSize < head.length) {
                head[headSize++] = bytes[i];
            } else if (tail.length > 0) {
                appendToTail(bytes[i]);
            }
        }
        totalBytes += length;
    }

    private void appendToTail(byte value) {
        if (tailSize < tail.length) {
            tail[(tailStart + tailSize) % tail.length] = value;
            tailSize++;
        } else {
            tail[tailStart] = value;
            tailStart = (tailStart + 1) % tail.length;
        }
    }

    /** 中间丢掉了多少字节。 */
    synchronized long omittedBytes() {
        return totalBytes - headSize - tailSize;
    }

    /** 按 UTF-8 解码；中间有省略时插入一行说明。截断处的半个多字节字符会显示成 �。 */
    synchronized String text() {
        String start = new String(head, 0, headSize, StandardCharsets.UTF_8);
        byte[] end = new byte[tailSize];
        for (int i = 0; i < tailSize; i++) {
            end[i] = tail[(tailStart + i) % tail.length];
        }
        String finish = new String(end, StandardCharsets.UTF_8);
        long omitted = omittedBytes();
        return omitted > 0 ? start + "\n... [" + omitted + " bytes of output omitted] ...\n" + finish : start + finish;
    }
}
