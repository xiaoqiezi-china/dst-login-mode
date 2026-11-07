# DST-Mode（帝释天模式）

Minecraft 认证模组 / JVM Agent：让「自定义签发服务器」签发的凭据可以登录服务器，同时保持正版玩家正常登录。

提供两种形态：**Agent**（全版本通用，推荐）与 **模组**（单版本，适合不能改 JVM 参数的环境）。

---

## 下载

从 [Releases](https://github.com/xiaoqiezi-china/dst-login-mode/releases) 取产物：

| 形态 | 文件 | 覆盖范围 |
|---|---|---|
| **Agent**（推荐） | `dstmode-agent-1.0.0.jar` | **1.7.10 – 1.21.x 全版本、全加载器** |
| 模组 · Fabric | `dstmode-mc1.21.1-fabric-1.0.0.jar` | MC 1.21.1 / Fabric |
| 模组 · NeoForge | `dstmode-mc1.21.1-neoforge-1.0.0.jar` | MC 1.21.1 / NeoForge |

### Agent 用法（一行 JVM 参数）

```
-javaagent:/path/dstmode-agent-1.0.0.jar=https://auth.example.com
```

客户端与服务端加同一个参数即可，**不需要装模组、不挑加载器、不挑 MC 版本**。

---

## ⚠️ 使用 Agent 前必须确认后端行为

Agent 会把 authlib 的 session 服务指向你的后端。此时**正版玩家的 join 也会打到后端**，
所以后端在 `join` 端点遇到不认识的 token 时，必须转发给 Mojang：

```
POST {authBase}/session/minecraft/join
  本地票据未命中 → 转发 https://sessionserver.mojang.com/session/minecraft/join
  转发成功 → 204
```

如果后端直接返回 403，装了 Agent 的正版玩家会无法登录。

---

## Agent 做了什么

只改 **authlib**（未混淆的库），因此不依赖 Minecraft 版本：

| 修改点 | 类 | 作用 |
|---|---|---|
| session 地址 | `YggdrasilEnvironment` 里的 `https://sessionserver.mojang.com` | 换成后端地址，join / hasJoined / profile 全部走后端 |
| 材质域名白名单 | `TextureUrlChecker.isAllowedTextureDomain` | 放行自有域名（仅 authlib 4.x / 6.x 有该校验） |
| 签名状态 | `YggdrasilMinecraftSessionService.getPropertySignatureState` | 返回 SIGNED，让其他玩家能看到皮肤（仅 authlib 6.x） |

**不动** `servicesHost`（`api.minecraftservices.com`），所以微软登录不受影响——
装了 Agent 的正版玩家照样能正常登录。

authlib 世代对应关系：

| MC 版本 | authlib | Agent 行为 |
|---|---|---|
| 1.7.10 – 1.15.2 | 1.5.x | 只改地址常量 |
| 1.16 – 1.19.2 | 2.x / 3.x | 改地址；无域名校验，皮肤直接可用 |
| 1.19.3 – 1.20.4 | 4.x | 改地址 + 放行域名 |
| 1.20.5 – 1.21.x | 6.x | 改地址 + 放行域名 + 标 SIGNED |

---

## 它能做什么

| 场景 | 客户端 | 结果 |
|---|---|---|
| 正版玩家 | 不装任何东西 | ✅ 正常登录（服务端把 hasJoined 转发 Mojang） |
| 正版玩家 | 装 Agent / 模组 | ✅ 正常登录（后端代发 join） |
| 非正版玩家 | 装 Agent / 模组 | ✅ 用签发服务器的凭据登录 |
| 非正版玩家 | 不装 | ❌ 无法登录（预期行为） |

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

- **非正版玩家**：join 由后端用本地票据放行，服务端 hasJoined 也问后端 → 本地命中
- **正版玩家**：join 直连 Mojang（未装 Agent）或由后端代发（装了 Agent），
  hasJoined 问后端 → 未命中 → 转发 Mojang

核心不变量：**服务端只信 hasJoined 的返回值**，客户端无法自报身份。
`serverId` 是每条连接一次性的随机串，本地没有记录就说明这次连接没走签发服务器。

签发服务器需要实现三个 Yggdrasil 会话端点：

| 端点 | 用途 |
|---|---|
| `POST /session/minecraft/join` | 客户端校验登录凭据（未命中时转发 Mojang） |
| `GET /session/minecraft/hasJoined` | 服务端校验玩家身份（未命中时转发 Mojang） |
| `GET /session/minecraft/profile/{uuid}` | 拉取玩家档案与皮肤 |

---

## 模组形态的注入点

全部注入在 GAME 层（`net.minecraft.*`）。authlib 在 NeoForge 1.21 属于 BOOTSTRAP 层，
ModLauncher 的 Mixin 只处理 GAME 层，因此模组不在 authlib 上做注入。

| 注入点 | 目标 | 作用 |
|---|---|---|
| `ClientHandshakeMixin` | `ClientHandshakePacketListenerImpl#authenticateServer` | join 走签发服务器；JWT 凭据直接走原版；失败自动回退 |
| `ServerLoginMixin` | `ServerLoginPacketListenerImpl$1#run` | hasJoined 问签发服务器，**不向上游透传 ip** |
| `MinecraftProfileMixin` | `Minecraft#getGameProfile` | 同步拉取 profile（单机皮肤来源） |
| `SkinManagerMixin` | `SkinManager$1` 内的 `unpackTextures` 调用 | 自行解包 textures，绕过域名白名单 |
| `ChatEnforcementMixin` | `DedicatedServer#enforceSecureProfile` | 关闭安全聊天强制校验（修「档案公钥丢失」） |
| `DstMixinPlugin` | Mixin 配置插件 | 未配置 authBase 时**不加载任何注入** |

---

## 配置

**Agent**：地址来自 `-javaagent:...=<authBase>` 参数。

**模组**：优先级 **系统属性 > 环境变量 > config/dstmode.json**

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

---

## 服务端要求

```properties
online-mode=true
enforce-secure-profile=false
```

Agent 形态服务端只需在启动脚本加 `-javaagent`，不需要加载器。

---

## 命令（仅模组形态）

| 命令 | 权限 | 作用 |
|---|---|---|
| `/dst status` | 所有人 | 显示 authBase、来源、清单地址 |
| `/dst ping` | OP（等级 2） | 请求一次 `authBase/healthz` |

---

## 日志

Agent 启动时输出一行：

```
[DST] agent active authBase=https://auth.example.com
```

模组形态：

```
[DST] active side=DEDICATED_SERVER authBase=https://auth.example.com src=config-file:dstmode.json
[DST] server | hasJoined <- 200 23ms Player <uuid> props=[textures]
[DST] server | hasJoined <- 204 580ms (miss)
[DST] client | join <- 204 61ms
```

---

## 构建

需要 JDK 17+（模组为 JDK 21）。

```bash
cd agent && ./gradlew build             # build/libs/dstmode-agent-1.0.0.jar
cd fabric/1.21.1 && ./gradlew build     # build/libs/dstmode-mc1.21.1-fabric-1.0.0.jar
cd neoforge/1.21.1 && ./gradlew build   # build/libs/dstmode-mc1.21.1-neoforge-1.0.0.jar
```

---

## 目录

```
dst-login-mode/
├── agent/          JVM agent（全版本通用，推荐）
├── common/         注入与核心逻辑（模组形态，两个加载器共用）
├── fabric/1.21.1/  Fabric 入口 + 元数据
└── neoforge/1.21.1/NeoForge 入口 + 元数据
```

---

## 已知限制

1. **模组形态只构建了 1.21.1**。其他版本请用 Agent——模组需要按 authlib 版本重写
   （1.20.x 的 `hasJoinedServer` 返回 `GameProfile`、`unpackTextures` 返回 `Map`，与 1.21.x 不兼容）。
2. **Agent 的材质域名校验是整体放宽**（`isAllowedTextureDomain` 恒为 true），
   不再限定 `*.minecraft.net`。这是我们为了让自有域名可用付出的代价。
3. **1.7.10 / 1.8 的皮肤机制不同**（无 `SkinManager`，按用户名拉取）。登录不受影响，
   自定义皮肤可能显示为默认。
4. **服务端位于 Docker/反代后时不向上游透传 ip**。否则 Mojang 的 IP 校验会误判。
5. 替换认证源在部分场景可能触及 Minecraft EULA 与相关服务条款，上线前请自行确认。

---

## 许可

All Rights Reserved.
