package dev.dstmode;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 认证源配置解析。所有地址都来自外部配置，代码里没有任何写死的域名。
 *
 * <p>解析顺序（先命中先用）：</p>
 * <ol>
 *   <li>系统属性 {@code -Ddstmode.authBase=...}</li>
 *   <li>环境变量 {@code DSTMODE_AUTH_BASE}</li>
 *   <li>配置文件 {@code config/dstmode.json} 的 {@code authBase}</li>
 *   <li>若以上都没有，但配置了 {@code dstmode.bootstrap}（系统属性 / 环境变量 / 配置文件），
 *       则向该地址拉取清单，取其中的 {@code authBase}。
 *       用于「签发服务器换地址」时，客户端只认一个稳定的清单地址。</li>
 * </ol>
 *
 * <p>全部缺失时返回 {@code null}，模组保持惰性（不改变任何原版行为）。</p>
 */
public final class DstConfig {
    public static final String PROP_AUTH_BASE = "dstmode.authBase";
    public static final String ENV_AUTH_BASE = "DSTMODE_AUTH_BASE";
    public static final String PROP_BOOTSTRAP = "dstmode.bootstrap";
    public static final String ENV_BOOTSTRAP = "DSTMODE_BOOTSTRAP";
    public static final String CONFIG_FILE = "dstmode.json";

    private static final Gson GSON = new Gson();

    private static volatile boolean loaded;
    private static volatile String authBase;
    private static volatile String source = "none";
    private static volatile String bootstrap = "none";

    private DstConfig() {
    }

    /** 归一化后的认证源基址（不带结尾斜杠），未配置时为 null。 */
    public static String authBase() {
        ensureLoaded();
        return authBase;
    }

    public static String source() {
        ensureLoaded();
        return source;
    }

    /** 当前生效的清单地址（bootstrap），未配置时为 none。 */
    public static String bootstrap() {
        ensureLoaded();
        return bootstrap;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String resolved = normalize(System.getProperty(PROP_AUTH_BASE));
            String from = "system-property";

            if (resolved == null) {
                resolved = normalize(System.getenv(ENV_AUTH_BASE));
                from = "environment";
            }

            String bootUrl = trimToNull(System.getProperty(PROP_BOOTSTRAP));
            String bootFrom = "system-property";
            if (bootUrl == null) {
                bootUrl = trimToNull(System.getenv(ENV_BOOTSTRAP));
                bootFrom = "environment";
            }

            if (resolved == null || bootUrl == null) {
                String[] file = readFromFile();
                if (resolved == null && file[0] != null) {
                    resolved = normalize(file[0]);
                    from = "config-file:" + CONFIG_FILE;
                }
                if (bootUrl == null && file[2] != null) {
                    bootUrl = file[2];
                    bootFrom = "config-file:" + CONFIG_FILE;
                }
            }

            if (bootUrl != null) {
                bootstrap = bootUrl + " (" + bootFrom + ")";
            }

            if (resolved == null && bootUrl != null) {
                resolved = fetchManifestAuthBase(bootUrl);
                if (resolved != null) {
                    from = "bootstrap:" + bootUrl;
                }
            }

            authBase = resolved;
            source = resolved != null ? from : "none";
            if (resolved != null) {
                DstMode.LOGGER.info("[DST] authBase={} src={}", resolved, from);
            } else if (bootUrl != null) {
                DstMode.LOGGER.warn("[DST] manifest has no authBase {}", bootUrl);
            }
        } catch (Throwable t) {
            DstMode.LOGGER.warn("[DST] config load failed {}", t.toString());
            authBase = null;
            source = "error";
        }
    }

    /**
     * 读取清单，取 authBase。清单格式由服务端约定，至少包含：
     * { "authBase": "https://..." }
     */
    private static String fetchManifestAuthBase(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            DstMode.LOGGER.warn("[DST] invalid bootstrap {}", url);
            return null;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(1500))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(2500))
                    .header("accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                DstMode.LOGGER.warn("[DST] manifest http {} {}", response.statusCode(), url);
                return null;
            }
            JsonObject obj = GSON.fromJson(response.body(), JsonObject.class);
            if (obj == null || !obj.has("authBase") || !obj.get("authBase").isJsonPrimitive()) {
                return null;
            }
            return normalize(obj.get("authBase").getAsString());
        } catch (Exception e) {
            DstMode.LOGGER.warn("[DST] manifest fetch failed {}", e.toString());
            return null;
        }
    }

    /** 返回 [authBase, authBase 来源, bootstrap]。 */
    private static String[] readFromFile() {
        Path path = configPath();
        String[] result = new String[] {null, "none", null};
        if (path == null || !Files.isRegularFile(path)) {
            return result;
        }
        try {
            JsonObject obj = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (obj == null) {
                return result;
            }
            if (obj.has("authBase") && obj.get("authBase").isJsonPrimitive()) {
                result[0] = obj.get("authBase").getAsString();
                result[1] = "config-file:" + CONFIG_FILE;
            }
            if (obj.has("bootstrap") && obj.get("bootstrap").isJsonPrimitive()) {
                result[2] = trimToNull(obj.get("bootstrap").getAsString());
            }
        } catch (Exception e) {
            DstMode.LOGGER.warn("[DST] config parse failed {}", e.toString());
        }
        return result;
    }

    private static Path configPath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        } catch (Throwable t) {
            return Path.of("config", CONFIG_FILE).toAbsolutePath();
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static String normalize(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            DstMode.LOGGER.warn("[DST] invalid authBase {}", value);
            return null;
        }
        return value;
    }
}