package dev.dstmode.agent;

import java.lang.instrument.Instrumentation;

/**
 * DST-Mode JVM agent。
 *
 * <p>用法：{@code -javaagent:dstmode-agent.jar=<authBase>}</p>
 *
 * <p>只改 authlib（未混淆的库），因此同一份 jar 可以覆盖 1.7.10 – 1.21.x，
 * 与加载器、Minecraft 版本都无关。</p>
 */
public final class DstAgent {
    private static final String PREFIX = "[DST] ";

    private DstAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        String authBase = normalize(args);
        if (authBase == null) {
            System.out.println(PREFIX + "agent inactive: no authBase (usage: -javaagent:dstmode-agent.jar=<url>)");
            return;
        }
        System.out.println(PREFIX + "agent active authBase=" + authBase);
        inst.addTransformer(new AuthlibTransformer(authBase), false);
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    /** 去掉引号与结尾斜杠；缺 scheme 时补 http://。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isEmpty()) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
