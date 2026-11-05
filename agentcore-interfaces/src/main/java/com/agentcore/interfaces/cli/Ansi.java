package com.agentcore.interfaces.cli;

/** 终端颜色（NO_COLOR 或非终端时关闭）。 */
public final class Ansi {

    private final boolean enabled;

    public Ansi(boolean enabled) {
        this.enabled = enabled;
    }

    public static Ansi auto() {
        return new Ansi(System.console() != null && System.getenv("NO_COLOR") == null);
    }

    private String wrap(String code, String s) {
        return enabled ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    public String dim(String s) {
        return wrap("2", s);
    }

    public String bold(String s) {
        return wrap("1", s);
    }

    public String red(String s) {
        return wrap("31", s);
    }

    public String green(String s) {
        return wrap("32", s);
    }

    public String yellow(String s) {
        return wrap("33", s);
    }

    public String cyan(String s) {
        return wrap("36", s);
    }
}
