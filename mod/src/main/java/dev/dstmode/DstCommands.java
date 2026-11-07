package dev.dstmode;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@code /dst status} 与 {@code /dst ping}：只读诊断，不提供切换。 */
public final class DstCommands {
    private DstCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("dst")
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(DstCommands::statusText, false);
                    return 1;
                }))
                .then(Commands.literal("ping")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            String result = pingText();
                            context.getSource().sendSuccess(() -> Component.literal(result), false);
                            return 1;
                        })));
    }

    private static Component statusText() {
        String base = DstConfig.authBase();
        String bootstrap = DstConfig.bootstrap();
        String bootstrapHint = "none".equals(bootstrap) ? "无" : bootstrap;
        if (base == null) {
            return Component.literal("[DST-Mode] 未启用：未找到 authBase（系统属性 "
                    + DstConfig.PROP_AUTH_BASE + " / 环境变量 " + DstConfig.ENV_AUTH_BASE
                    + " / config/" + DstConfig.CONFIG_FILE + "），清单=" + bootstrapHint);
        }
        return Component.literal("[DST-Mode] 已启用：authBase=" + base
                + "，来源=" + DstConfig.source() + "，清单=" + bootstrapHint);
    }

    private static String pingText() {
        String base = DstConfig.authBase();
        if (base == null) {
            return "[DST-Mode] 未配置 authBase，无法 ping";
        }
        String url = base + "/healthz";
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            long start = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            String body = response.body();
            if (body != null && body.length() > 200) {
                body = body.substring(0, 200) + "...";
            }
            DstLog.info("cmd", "ping {} -> {} {}ms", url, response.statusCode(), cost);
            return "[DST-Mode] ping " + url + " -> HTTP " + response.statusCode() + " (" + cost + "ms) " + body;
        } catch (Exception e) {
            DstLog.error("cmd", "ping failed {}", e.toString());
            return "[DST-Mode] ping " + url + " 失败：" + e;
        }
    }
}