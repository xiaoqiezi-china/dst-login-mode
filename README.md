# DST-Mode（帝释天模式）

Minecraft **1.21.1 / NeoForge** 认证模组：让「自定义签发服务器」签发的凭据可以登录服务器，
同时保持正版玩家正常登录。

同一个 jar 同时用于客户端与服务端，行为完全由是否配置 `authBase` 决定。

> 本仓库只包含模组源码。签发服务器（后端）与启动器对接文档由服务方自行维护。

---

## 它能做什么

| 场景 | 客户端 | 结果 |
|---|---|---|
| 正版玩家 | 不装模组 | ✅ 正常登录（服务端把 hasJoined 转发 Mojang） |
| 正版玩家 | 装模组、不配参数 | ✅ 正常登录（模组完全惰性） |
| 正版玩家 | 装模组、配了参数 | ✅ 正常登录（识别 JWT 走原版 / 失败自动回退） |
| 非正版玩家 | 装模组、配了参数 | ✅ 用签发服务器的凭据登录 |
| 非正版玩家 | 不装模组 | ❌ 无法登录（预期行为） |

---

## 工作原理

```
                    ┌──────────────────────────────┐
                    │      签发服务器（后端）        │
                    │  join / hasJoined / profile   │
                    └──────────────┬───────────────┘
                       ▲           │            ▲
        ① join（非正版）│           │ ② 未命中    │ ③ hasJoined
                       │           ▼            │
  启动器 ──token──▶ 客户端游戏   转发 Mojang   服务端游戏
                                   │
                                   ▼
                              sessionserver.mojang.com
```

- **非正版玩家**：客户端 join 打到签发服务器，服务端 hasJoined 也问签发服务器 → 本地命中 → 放行
- **正版玩家**：客户端 join 直接打 Mojang（原版行为），服务端 hasJoined 问签发服务器 → 本地未命中 → 转发 Mojang → 放行

核心不变量：**服务端只信 hasJoined 的返回值**，客户端无法自报身份。
`serverId` 是每条连接一次性的随机串，本地没有记录就说明这次连接没走签发服务器。

签发服务器需要实现三个 Yggdrasil 会话端点：

| 端点 | 用途 |
|---|---|
| `POST /session/minecraft/join` | 客户端校验登录凭据 |
| `GET /session/minecraft/hasJoined` | 服务端校验玩家身份 |
| `GET /session/minecraft/profile/{uuid}` | 拉取玩家档案与皮肤 |

---

## 注入点

全部注入在 GAME 层（`net.minecraft.*`）。authlib 在 NeoForge 1.21 属于 BOOTSTRAP 层，
ModLauncher 的 Mixin 只处理 GAME 层，因此不在 authlib 上做注入。

| 注入点 | 目标 | 作用 |
|---|---|---|
| `ClientHandshakeMixin` | `ClientHandshakePacketListenerImpl#authenticateServer` | join 走签发服务器；JWT 凭据直接走原版；失败自动回退 |
| `ServerLoginMixin` | `ServerLoginPacketListenerImpl$1#run` | hasJoined 问签发服务器，**不向上游透传 ip** |
| `MinecraftProfileMixin` | `Minecraft#getGameProfile` | 同步拉取 profile（单机皮肤来源） |
| `SkinManagerMixin` | `SkinManager$1#lambda$load$0` | 自行解包 textures，绕过 authlib 的域名白名单 |
| `ChatEnforcementMixin` | `DedicatedServer#enforceSecureProfile` | 关闭安全聊天强制校验（修「档案公钥丢失」） |
| `DstMixinPlugin` | Mixin 配置插件 | 未配置 authBase 时**不加载任何注入** |

---

## 配置

优先级：**系统属性 > 环境变量 > config/dstmode.json**

| 来源 | 键 | 用途 |
|---|---|---|
| 系统属性 | `-Ddstmode.authBase` | 客户端推荐（启动器传入） |
| 环境变量 | `DSTMODE_AUTH_BASE` | 容器/服务端常用 |
| 配置文件 | `config/dstmode.json` 的 `authBase` | 固定部署 |
| 系统属性 | `-Ddstmode.bootstrap` | 稳定清单地址，均未配置时才使用 |
| 环境变量 | `DSTMODE_BOOTSTRAP` | 同上 |

全部缺失时模组**完全惰性**，不修改任何原版类字节码。

```json
{
  "authBase": "https://auth.example.com"
}
```

`authBase` 不带结尾斜杠；生产环境必须 HTTPS（本地联调可用 http）。

---

## 服务端要求

```properties
online-mode=true
enforce-secure-profile=false
```

- `online-mode=true`：本方案的前提，服务端仍走完整加密握手
- `enforce-secure-profile=false`：虽然模组已强制关闭该逻辑，仍建议显式声明

目录结构：

```
<服务器根目录>/
├── mods/dstmode-1.0.0.jar
└── config/dstmode.json
```

---

## 命令

| 命令 | 权限 | 作用 |
|---|---|---|
| `/dst status` | 所有人 | 显示 authBase、来源、清单地址 |
| `/dst ping` | OP（等级 2） | 请求一次 `authBase/healthz`，显示 HTTP 码与耗时 |

---

## 日志

统一前缀 `[DST]`，输出单行程序化记录，不含调试级别开关。

```
[DST] active side=DEDICATED_SERVER authBase=https://auth.example.com src=config-file:dstmode.json
[DST] server | hasJoined -> Player
[DST] server | hasJoined <- 200 23ms Player <uuid> props=[textures]
[DST] server | hasJoined <- 204 580ms (miss)
[DST] client | join -> https://auth.example.com/session/minecraft/join token=abcd..wxyz/len=43
[DST] client | join <- 204 61ms
[DST] client | profile <- 200 42ms Player textures=true
```

---

## 构建

需要 JDK 21。

```bash
cd mod
./gradlew build          # 产物：build/libs/dstmode-1.0.0.jar
./gradlew runClient      # 开发环境客户端
./gradlew runServer      # 开发环境服务端
```

---

## 已知限制

1. **仅支持 1.21.1 / NeoForge 21.1.x**。其他版本需要按 authlib 版本调整注入点。
2. **正版玩家不装模组时看不到非正版玩家皮肤**。其 authlib 会拒绝非 `*.minecraft.net` 的材质域名，
   这是原版防盗链限制，无法在服务端绕过。
3. **非正版玩家的材质没有 Mojang 签名**。模组采用「域名可信即视为安全」，
   白名单限定 `*.minecraft.net` 与 `authBase` 自身域名。
4. **服务端位于 Docker/反代后时不向上游透传 ip**。否则 Mojang 的 IP 校验会误判而拒绝正版玩家。
5. 替换认证源在部分场景可能触及 Minecraft EULA 与相关服务条款，上线前请自行确认。

---

## 许可

All Rights Reserved.
