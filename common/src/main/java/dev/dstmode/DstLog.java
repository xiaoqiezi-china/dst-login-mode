package dev.dstmode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 日志门面：统一前缀，输出单行程序化记录。两个加载器共用同一个 logger 名。 */
public final class DstLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("DST-Mode");
    private static final String P = "[DST] ";

    private DstLog() {
    }

    public static void info(String side, String message, Object... args) {
        LOGGER.info(P + side + " | " + message, args);
    }

    public static void warn(String side, String message, Object... args) {
        LOGGER.warn(P + side + " | " + message, args);
    }

    public static void error(String side, String message, Object... args) {
        LOGGER.error(P + side + " | " + message, args);
    }

    /** 启动期无 side 前缀的记录。 */
    public static void boot(String message, Object... args) {
        LOGGER.info(P + message, args);
    }

    public static void bootWarn(String message, Object... args) {
        LOGGER.warn(P + message, args);
    }

    /** 凭据脱敏：仅保留长度与首尾 4 位。 */
    public static String mask(String token) {
        if (token == null) {
            return "null";
        }
        if (token.length() <= 12) {
            return "len=" + token.length();
        }
        return token.substring(0, 4) + ".." + token.substring(token.length() - 4) + "/len=" + token.length();
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "-";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
