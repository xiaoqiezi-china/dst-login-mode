# DST-Mode（帝释天模式）

Minecraft 认证模组：让「自定义签发服务器」签发的凭据可以登录服务器，同时保持正版玩家正常登录。

同一份代码构建出 **Fabric** 与 **NeoForge** 两个加载器的 jar，客户端与服务端通用。

---

## 下载

从 [Releases](https://github.com/xiaoqiezi-china/dst-login-mode/releases) 取对应组合的 jar：

| Minecraft | 加载器 | 文件 |
|---|---|---|
| 1.21.1 | Fabric | `dstmode-mc1.21.1-fabric-1.0.0.jar` |
| 1.21.1 | NeoForge | `dstmode-mc1.21.1-neoforge-1.0.0.jar` |

文件名规则固定为 `dstmode-mc{版本}-{加载器}-{模组版本}.jar`，启动器可直接拼 URL 抓取。

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
| `SkinManagerMixin` | `SkinManager$1` 内的 `unpackTextures` 调用 | 自行解包 textures，绕过 authlib 的域名白名单 |
| `ChatEnforcementMixin` | `DedicatedServer#enforceSecureProfile` | 关闭安全聊天强制校验（修「档案公钥丢失」） |
| `DstMixinPlugin` | Mixin 配置插件 | 未配置 authBase 时**不加载任何注入** |

注入代码放在 `common/`，两个加载器共用；各自只提供入口类与元数据。

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

`authBase` 不带结尾斜杠；生产环境必须 HTTPS。

---

## 服务端要求

```properties
online-mode=true
enforce-secure-profile=false
```

目录结构：

```
<服务器根目录>/
├── mods/dstmode-mc1.21.1-neoforge-1.0.0.jar
└── config/dstmode.json
```

---

## 命令

| 命令 | 权限 | 作用 |
|---|---|---|
| `/dst status` | 所有人 | 显示 authBase、来源、清单地址 |
| `/dst ping` | OP（等级 2） | 请求一次 `authBase/healthz` |

Fabric 端的命令依赖 Fabric API 的命令注册回调；未装 Fabric API 时自动跳过，
登录与皮肤功能不受影响。

---

## 日志

统一前缀 `[DST]`，输出单行程序化记录。

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

需要 JDK 21（1.21.1 目标）。每个版本组合都是独立的 Gradle 工程：

```bash
cd fabric/1.21.1 && ./gradlew build     # build/libs/dstmode-mc1.21.1-fabric-1.0.0.jar
cd neoforge/1.21.1 && ./gradlew build   # build/libs/dstmode-mc1.21.1-neoforge-1.0.0.jar
```

---

## 目录

```
dst-login-mode/
├── common/         注入与核心逻辑（两个加载器共用）
├── fabric/1.21.1/  Fabric 入口 + 元数据
└── neoforge/1.21.1/NeoForge 入口 + 元数据
```

---

## 已知限制

1. **当前只构建 1.21.1**。1.20.x 使用 authlib 4，`hasJoinedServer` 返回 `GameProfile`、
   `unpackTextures` 返回 `Map`，与 1.21.x 的 authlib 6 不兼容，需要额外的兼容层。
2. **正版玩家不装模组时看不到非正版玩家皮肤**。其 authlib 会拒绝非 `*.minecraft.net` 的材质域名。
3. **非正版玩家的材质没有 Mojang 签名**。模组采用「域名可信即视为安全」，
   白名单限定 `*.minecraft.net` 与 `authBase` 自身域名。
4. **服务端位于 Docker/反代后时不向上游透传 ip**。否则 Mojang 的 IP 校验会误判。
5. 替换认证源在部分场景可能触及 Minecraft EULA 与相关服务条款，上线前请自行确认。

---

## 许可

All Rights Reserved.
