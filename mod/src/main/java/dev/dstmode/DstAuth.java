package dev.dstmode;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与签发服务器通信的最小客户端。
 *
 * <p>NeoForge 1.21 把 authlib 放在 MC-BOOTSTRAP 层，Mixin 只处理 GAME 层，
 * 因此不修改 authlib，而是在 GAME 层的调用点上拦截。</p>
 */
public final class DstAuth {
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final long TIMEOUT_MS = 4000L;

    private static final Map<UUID, ProfileResult> PROFILES = new ConcurrentHashMap<>();
    private static final Set<UUID> REPLACE_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Object PROFILE_LOCK = new Object();
    private static final long FAIL_TTL_MS = 30_000L;
    private static volatile UUID failedId;
    private static volatile long failedAt;

    private DstAuth() {
    }

    public static boolean enabled() {
        return DstConfig.authBase() != null;
    }

    private static String base() {
        String base = DstConfig.authBase();
        if (base == null) {
            throw new IllegalStateException("authBase 未配置");
        }
        return base;
    }

    // ------------------------------------------------------------------
    // 客户端 join
    // ------------------------------------------------------------------

    public static void join(UUID profileId, String accessToken, String serverId) throws AuthenticationException {
        String url = base() + "/session/minecraft/join";
        // 注意：这里曾把 accessToken 写成占位符 "***"（为了让日志显示体时脱敏），
        // 导致后端永远返回 403 Invalid token。日志脱敏必须只作用于日志，不能作用于请求体。
        JsonObject request = new JsonObject();
        request.addProperty("accessToken", accessToken);
        request.addProperty("selectedProfile", profileId.toString().replace("-", ""));
        request.addProperty("serverId", serverId);
        String body = GSON.toJson(request);
        DstLog.info("client", "join -> {} token={}", url, DstLog.mask(accessToken));
        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = post(url, body);
        } catch (Exception e) {
            DstLog.error("client", "join 异常 {}ms {}", System.currentTimeMillis() - start, e.toString());
            throw new AuthenticationUnavailableException("DST: auth unavailable (" + e.getMessage() + ")", e);
        }
        long cost = System.currentTimeMillis() - start;
        int code = response.statusCode();
        DstLog.info("client", "join <- {} {}ms", code, cost);
        if (code == 204 || code == 200) {
            return;
        }
        if (code == 403) {
            DstLog.info("client", "join rejected {}", DstLog.truncate(response.body(), 120));
            throw new AuthenticationException("DST: rejected");
        }
        throw new AuthenticationUnavailableException("DST: join http " + code);
    }

    // ------------------------------------------------------------------
    // 服务端 hasJoined
    // ------------------------------------------------------------------

    public static ProfileResult hasJoined(String username, String serverId, String ip) throws AuthenticationUnavailableException {
        StringBuilder url = new StringBuilder(base())
                .append("/session/minecraft/hasJoined?username=").append(enc(username))
                .append("&serverId=").append(enc(serverId));
        if (ip != null && !ip.isEmpty()) {
            url.append("&ip=").append(enc(ip));
        }
        DstLog.info("server", "hasJoined -> {} serverId={}", username, serverId);
        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = get(url.toString());
        } catch (Exception e) {
            DstLog.error("server", "hasJoined 异常 {}ms {}", System.currentTimeMillis() - start, e.toString());
            throw new AuthenticationUnavailableException("DST: auth unavailable (" + e.getMessage() + ")", e);
        }
        long cost = System.currentTimeMillis() - start;
        int code = response.statusCode();
        if (code == 204) {
            DstLog.info("server", "hasJoined <- 204 {}ms (miss)", cost);
            return null;
        }
        if (code != 200) {
            DstLog.error("server", "hasJoined <- {} {}ms {}", code, cost, DstLog.truncate(response.body(), 120));
            throw new AuthenticationUnavailableException("DST-Mode: hasJoined HTTP " + code);
        }
        GameProfile profile = parseProfile(response.body());
        if (profile == null) {
            DstLog.warn("server", "hasJoined parse failed {}", DstLog.truncate(response.body(), 120));
            return null;
        }
        DstLog.info("server", "hasJoined <- 200 {}ms {} {} props={}", cost,
                profile.getName(), profile.getId(), profile.getProperties().keySet());
        return new ProfileResult(profile);
    }

    // ------------------------------------------------------------------
    // 客户端自己的 profile（皮肤）
    // ------------------------------------------------------------------

    public static GameProfile profileBlocking(UUID uuid) {
        if (!enabled() || uuid == null) {
            return null;
        }
        ProfileResult cached = PROFILES.get(uuid);
        if (cached != null) {
            return cached.profile();
        }
        synchronized (PROFILE_LOCK) {
            cached = PROFILES.get(uuid);
            if (cached != null) {
                return cached.profile();
            }
            if (uuid.equals(failedId) && System.currentTimeMillis() - failedAt < FAIL_TTL_MS) {
                return null;
            }
            String url = base() + "/session/minecraft/profile/" + uuid.toString().replace("-", "") + "?unsigned=false";
            DstLog.info("client", "profile -> {}", url);
            long start = System.currentTimeMillis();
            try {
                HttpResponse<String> response = get(url);
                long cost = System.currentTimeMillis() - start;
                if (response.statusCode() == 200) {
                    GameProfile profile = parseProfile(response.body());
                    if (profile != null) {
                        PROFILES.put(uuid, new ProfileResult(profile));
                        boolean hasTextures = profile.getProperties().containsKey("textures");
                        DstLog.info("client", "profile <- 200 {}ms {} textures={}", cost, profile.getName(), hasTextures);
                        return profile;
                    }
                }
                DstLog.warn("client", "profile <- {} {}ms", response.statusCode(), cost);
            } catch (Exception e) {
                DstLog.error("client", "profile 异常 {}ms {}", System.currentTimeMillis() - start, e.toString());
            }
            failedId = uuid;
            failedAt = System.currentTimeMillis();
            return null;
        }
    }

    /** 只打印一次"profile 已替换"，避免 getGameProfile 高频调用刷屏。 */
    public static void logReplacedOnce(GameProfile profile) {
        if (profile != null && REPLACE_LOGGED.add(profile.getId())) {
            DstLog.info("client", "profile applied {}", profile.getName());
        }
    }

    // ------------------------------------------------------------------
    // 皮肤解包（绕过 authlib 的 *.minecraft.net 白名单）
    // ------------------------------------------------------------------

    public static MinecraftProfileTextures unpackTextures(Property property) {
        if (property == null || !"textures".equals(property.name())) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonObject textures = root == null ? null : root.getAsJsonObject("textures");
            if (textures == null) {
                DstLog.warn("client", "textures empty");
                return MinecraftProfileTextures.EMPTY;
            }
            MinecraftProfileTexture skin = texture(textures, "SKIN");
            MinecraftProfileTexture cape = texture(textures, "CAPE");
            MinecraftProfileTexture elytra = texture(textures, "ELYTRA");
            DstLog.info("client", "textures skin={} cape={} elytra={}",
                    skin == null ? "-" : skin.getUrl(),
                    cape == null ? "-" : cape.getUrl(),
                    elytra == null ? "-" : elytra.getUrl());
            // secure 判定：只要材质域名可信即视为安全。
            // 原版 PlayerInfo 会丢弃其他玩家的非 SIGNED 皮肤（回落默认皮肤）。
            // 跨源场景下（QQ 客户端看正版玩家）签名经常在转发链路上丢失，
            // 因此改为以域名白名单为准：只认 *.minecraft.net 与 authBase 自身域名。
            boolean signed = trustedDomains(skin, cape, elytra);
            return new MinecraftProfileTextures(skin, cape, elytra,
                    signed ? SignatureState.SIGNED : SignatureState.UNSIGNED);
        } catch (Exception e) {
            DstLog.error("client", "textures decode failed {}", e.toString());
            return null;
        }
    }

    /** 材质 URL 是否全部来自可信域名。空材质视为可信。 */
    private static boolean trustedDomains(MinecraftProfileTexture... textures) {
        for (MinecraftProfileTexture texture : textures) {
            if (texture == null) {
                continue;
            }
            String host;
            try {
                host = URI.create(texture.getUrl()).getHost();
            } catch (Exception e) {
                return false;
            }
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            String base = DstConfig.authBase();
            String baseHost = null;
            if (base != null) {
                try {
                    baseHost = URI.create(base).getHost();
                } catch (Exception ignored) {
                    baseHost = null;
                }
            }
            boolean ok = host.endsWith(".minecraft.net")
                    || host.equals("minecraft.net")
                    || (baseHost != null && host.equals(baseHost.toLowerCase(java.util.Locale.ROOT)));
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static MinecraftProfileTexture texture(JsonObject textures, String type) {
        JsonElement element = textures.get(type);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement url = object.get("url");
        if (url == null || !url.isJsonPrimitive()) {
            return null;
        }
        Map<String, String> metadata = new HashMap<>();
        JsonElement meta = object.get("metadata");
        if (meta != null && meta.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : meta.getAsJsonObject().entrySet()) {
                metadata.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return new MinecraftProfileTexture(url.getAsString(), metadata);
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    private static GameProfile parseProfile(String body) {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        if (json == null || !json.has("id") || !json.has("name")) {
            return null;
        }
        UUID id = undash(json.get("id").getAsString());
        if (id == null) {
            return null;
        }
        GameProfile profile = new GameProfile(id, json.get("name").getAsString());
        JsonElement properties = json.get("properties");
        if (properties != null && properties.isJsonArray()) {
            JsonArray array = properties.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject property = element.getAsJsonObject();
                String name = property.get("name").getAsString();
                String value = property.get("value").getAsString();
                String signature = property.has("signature") && !property.get("signature").isJsonNull()
                        ? property.get("signature").getAsString() : null;
                profile.getProperties().put(name, signature == null
                        ? new Property(name, value) : new Property(name, value, signature));
            }
        }
        return profile;
    }

    private static UUID undash(String raw) {
        try {
            String value = raw;
            if (value.length() == 32) {
                value = value.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            }
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}