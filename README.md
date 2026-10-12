# MC-Tunnel

> 一个用于 Minecraft 多人联机的隧道/虚拟组网管理模组，同时支持作为独立服务端运行。

MC-Tunnel 帮助玩家快速搭建和管理内网穿透与虚拟组网工具（ngrok、Tailscale），并提供跨设备聊天同步和存档同步能力。

- **Minecraft**: 1.20.1
- **Forge**: 47.3.0
- **License**: MIT
- **Version**: 1.0.0-SNAPSHOT

## 功能特性

| 功能 | 说明 |
|------|------|
| 🔌 ngrok 内网穿透 | 一键下载/启动/停止 ngrok，自动映射 Minecraft 端口 |
| 🌐 Tailscale 虚拟组网 | 下载安装 tailscale，加入虚拟局域网 |
| 📁 SyncThing 存档同步 | P2P 同步 Minecraft 存档目录到多设备 |
| 💬 跨设备聊天 | WebSocket 组网，游戏内聊天与 WebUI 实时同步 |
| 🖥️ WebUI 仪表盘 | 浏览器管理工具状态、查看在线节点、发送消息 |
| 📱 Android 支持 | 通过 FCL 启动器 + Termux 后端在手机上运行 |

## 架构

多模块 Gradle 项目，**三个产物**，依赖方向严格单向：`forge → core → relay`。

```
mc-tunnel/
├── relay/         # 中继层（零外部依赖）：云中继 + 房主本地房间服务端 + 房间协议 DTO
│   ├── protocol/  #   ChatMessage / Room / RoomMember / JoinLink / LinkCodec
│   │              #   RoomNetwork / NetworkType / NetworkStatus
│   ├── server/    #   RelayServer（含内部 RoomRegistry，纯内存）
│   └── client/    #   RelayClient
├── core/          # 客户端层：隧道适配、Web 服务、聊天服务端与存储、CLI 入口、RoomManager
└── forge/         # Forge 1.20.1 模组（内嵌 core + relay 全部类 + 游戏内 GUI）
```

### 三产物

| 产物 | 路径 | 大小量级 | 用途 |
|------|------|----------|------|
| `mctunnel-relay.jar` | `relay/build/libs/` | 约 34 KB | **云中继**（常驻公网引导）与**房主本地房间服务端**，`java -jar` 直启 |
| `mctunnel-core.jar` | `core/build/libs/` | 约 181 KB | 客户端库产物，供 CLI/其他宿主引用 |
| `mctunnel.jar` | `forge/build/libs/` | 约 14.5 MB | Forge 模组，同时可 `java -jar ... serve` 独立运行 |

### 职责边界（硬契约）

| 层 | 承担 | 明确不承担 |
|---|---|---|
| 云中继 | 房间信令、成员在线状态、`room_endpoint` 地址交换、破冰阶段聊天帧转发 | **Minecraft 游戏流量**、任何持久化、任何隧道工具能力（ngrok / Tailscale / SyncThing 一概不碰） |
| 房主本地房间服务端 | 房间存续期的成员管理与状态、聊天落 SQLite（`~/.mctunnel/`） | — |
| 已建立网络（数据面） | MC 游戏包（Tailscale 直连优先）、网络建立后的 P2P 聊天 | — |
| ngrok | 打洞失败时的兜底路径：映射房主 25565 端口 | 不作为默认路径 |

**为什么数据面不走云中继**：云服务器为 `ecs.e-c1m1.large`（2 vCPU / 2 GiB / **3 Mbps 固定公网带宽 ≈ 375 KB/s**，共享调度无性能 SLA），而两个玩家的 Minecraft 流量即需 2–5 Mbit/s（进服拉区块更高）；该链路同时是 SSH(22) 的唯一入口，打满会把自己关在门外。控制面是 KB 级流量，中继常驻实测 RSS 约 36 MB、CPU 0.0%。

**依赖边界守护**：`:relay:check-boundary` 任务断言 relay 模块零 `import io.mctunnel.core.*`，构建即校验，防止中继层被客户端能力反向污染。

**核心设计**：
- 中继层零外部依赖（JDK 内置 `ServerSocket` + 手写 WebSocket 帧），不引入 SQLite/slf4j
- 模组即完整应用：桌面端游戏内直接驱动工具，WebUI 只是可选的 HTTP 门面，可在游戏内随时开关
- 游戏外可直接 `java -jar mctunnel.jar serve` 启动 WebUI（Android/Termux 场景）
- 存储（SQLite）与房间状态持久化都在**客户端/房主侧**，云中继不落任何库

### 打包说明（slf4j 与 Forge 模块冲突）

mctunnel.jar 同时是 Forge 模组和可执行 jar，但 slf4j 的打包方式必须区分两种运行环境（中继拆成独立模块后，`mctunnel-relay.jar` 零依赖、完全不涉及此问题）：

- **Forge 内运行**：游戏启动层自带 `org.slf4j` 模块。slf4j-api 的 class 绝不能展开打进模组 jar，否则模块层报 `ResolutionException: Module mctunnel contains package org.slf4j.spi ...`（split package），游戏在模组加载前直接崩溃（2026-10-03 实测）。
- **独立运行（java -jar）**：sqlite-jdbc 需要 slf4j-api + 一个绑定。打包时把 `slf4j-api`/`slf4j-nop` 两个完整 jar 作为资源放在 `standalone-lib/`（不展开）；Manifest 的 Main-Class 是 `io.mctunnel.core.bootstrap.StandaloneLauncher`，它运行时把嵌套 jar 解到临时目录，用 platform loader 为父的 URLClassLoader 加载后再反射进入 `io.mctunnel.core.cli.Main`。Forge 加载模组时这些资源被完全忽略。

## 构建

要求 JDK 17+。

```bash
# 构建(三个产物)
./gradlew build
```

产物位置：

- `forge/build/libs/mctunnel.jar` — Forge 模组 / 可独立执行（`serve`）
- `relay/build/libs/mctunnel-relay.jar` — 云中继，`java -jar mctunnel-relay.jar [port] [public-host]`
- `core/build/libs/mctunnel-core.jar` — 客户端库

架构校验：`./gradlew :relay:check-boundary`（relay 不得 import core）。

## 使用

### 1. Forge 模组（游戏内）

将 `mctunnel.jar` 放入 `.minecraft/mods/`，启动游戏后通过模组菜单管理工具。

- 桌面端：模组内嵌全部功能，游戏内 GUI 直接驱动工具
- WebUI 可在游戏内面板中随时开关（默认开启，配置持久化）
- Android（FCL）：需在 Termux 用同一 jar 运行 `serve`（见下）

### 2. 游戏外独立运行（推荐用于 Android/Termux）

```bash
java -jar mctunnel.jar serve
```

启动后：
- WebUI: <http://localhost:8787>
- 聊天 WebSocket: `ws://localhost:8788`

也支持 CLI 子命令：`install <tool>` / `start <tool> [args]` / `stop <tool>` / `status`。

### 工具安装与更新

所有工具（ngrok / Tailscale / SyncThing）的安装与更新统一走官方渠道，CLI、WebUI、游戏内 GUI 三个入口流程一致：

1. **先检查本地是否已安装**（依次查找：① 系统 PATH — 模组首装时数据库为空，用户自行安装并配过 PATH 的工具只能从这里发现，扫到才记录；② 记录的安装路径 — 模组安装或用户指定位置后才有；③ 默认安装位置/缓存目录），再用官方源查询最新版本：
   - Tailscale：`pkgs.tailscale.com/stable/?mode=json`
   - SyncThing：GitHub Releases API
   - ngrok：无版本查询 API，直接下载 v3 stable 包探测版本（下载结果复用给安装，不二次下载）
2. **未安装** → 先让用户选择：**① 指定已有工具位置**（已自行下载但没配 PATH 的场景，指定后尝试加入 PATH 并直接使用）或 **② 从官方渠道下载安装**；
3. **已安装且是最新版** → 直接使用（但 ngrok 未配置 authtoken / Tailscale 未登录时仍会进入账户引导）；
4. **已安装但非最新** → 提供"更新"选项（ngrok 优先用自带的 `ngrok update`、Tailscale 优先用自带的 `tailscale update` 就地自更新，保留安装位置与 PATH；失败时回退官方包下载覆盖。其他工具从官方源重新下载覆盖）。

CLI 用法：

```bash
# 交互式:先检查本地与官方最新版本,再询问是否安装/更新
java -jar mctunnel.jar install <tool>

# 非交互
java -jar mctunnel.jar install <tool> --yes
java -jar mctunnel.jar install ngrok --extract-dir "C:\ngrok" --authtoken <token>
```

**Windows 上的 ngrok 特殊流程**：下载官方 zip 后询问用户解压位置 → 解压 → 尝试将目录加入用户 PATH（已存在则跳过）并记录完整路径；后续执行命令先尝试直接用 `ngrok`（PATH 解析），失败自动回退到记录的完整路径。

**ngrok 账户引导**：安装或指定位置完成后，会先探测 authtoken 是否已配置（`ngrok config check`）——已配置则直接跳过；未配置才询问是否注册/登录账户（未登录无法启动隧道，登录后可获取免费的 Authtoken）：确认后自动打开 [dashboard.ngrok.org/signup](https://dashboard.ngrok.org/signup)（若浏览器未弹出会提示手动打开并支持复制网址），登录后在 Dashboard 复制 Authtoken 粘贴到输入框，确认后自动执行 `ngrok config add-authtoken <token>`。

**Windows 上的 Tailscale 特殊流程**：下载官方安装包 [tailscale-setup-latest.exe](https://pkgs.tailscale.com/stable/tailscale-setup-latest.exe) 并运行，由用户按安装向导完成安装（默认装到 `C:\Program Files\Tailscale`，安装包自注册 PATH 与系统服务）。后续命令统一先试 PATH 中的 `tailscale`，失败回退完整路径。

**Tailscale 守护进程（tailscaled）管理**：状态中带 `daemonRunning` 字段（CLI `status`/WebUI 卡片/游戏内面板均显示"守护:运行中/未运行"，Linux 通过 `systemctl is-active tailscaled` 判定，普通用户查询无需密码；Windows 通过 `tailscale status` 判定）。启停逻辑：
- **Linux/macOS**：root 用户直接执行 `systemctl start|stop tailscaled`；非 root 用户先检测免密 sudo（`sudo -n`），不可用时弹出密码输入——CLI 用 `Console.readPassword` 不回显，WebUI 弹密码框（`type=password` 遮罩），游戏内 GUI 打开掩码输入屏。**安全保证**：密码仅在本次操作中经 `sudo -S` 的标准输入传递（不进命令行参数、不进 shell、`ps` 不可见），不出现在任何日志中，不记录、不持久化，用毕立即从内存清空；密码错误允许重试 3 次。无交互式终端时提示用户自行执行 `sudo systemctl start tailscaled`。
- **Windows**：启动优先运行 `tailscale-ipn.exe` 托盘程序（由它拉起系统服务），回退 `net start Tailscale`；停止走 `net stop Tailscale`（需管理员权限）。

入口：CLI `java -jar mctunnel.jar daemon start|stop|status tailscale`；API `POST /api/daemon-start|stop/tailscale`（首次返回 `{"needsSudoPassword":true}`，随后请求体可带 `{"sudoPassword":"..."}`）；WebUI"启动服务/停止服务"按钮；游戏内 tailscale 行"启服务/停服务"。

**Tailscale 登录引导**：安装完成后先探测登录态（`tailscale status --json` 的 BackendState）——已登录则跳过；未登录才询问是否登录：确认后触发 `tailscale login`，在弹出的浏览器中完成授权即可（CLI 会同时打印认证 URL，浏览器未弹出时可手动打开）。

**已有工具直接使用（三个入口一致）**：如果工具已自行安装，可指定其位置让模组直接使用，不再下载安装——CLI 用 `java -jar mctunnel.jar locate <tool> <可执行文件或所在目录>`；WebUI 每个工具卡片有"指定位置"按钮；游戏内面板每个工具行有"位置"按钮。路径验证后会记录到本地数据库，重启后仍然生效。

**Tailscale 虚拟组网三种方式（房主/成员向导，三入口一致）**：CLI 用 `java -jar mctunnel.jar mesh`（无参数进入交互向导，也支持 `mesh token <tskey>` / `mesh share <邮箱...>` / `mesh invite <邮箱...>` / `mesh join` / `mesh clear-token`）；WebUI 在 tailscale 卡片点"🌐 虚拟组网向导"；游戏内 tailscale 行点"组网"。

1. **共用一个账号**：房主创建 Tailscale 账号并 `tailscale login`，所有成员也登录同一账号。无需 API，最简单；成员能看到该账号下全部设备。
2. **房主分享本机设备**（推荐用于临时联机）：成员各自注册自己的 Tailscale 账号；房主在 [admin → Settings → API access tokens](https://login.tailscale.com/admin/settings/keys) 生成 API 访问令牌（`tskey-api-` 开头，最长 90 天，需要 Owner/Admin/IT admin 角色），在向导里粘贴（令牌先调 API 验证、通过后存入本地数据库复用），再填写成员邮箱执行"分享设备"（API `POST /api/v2/device/{deviceId}/shares`）。成员收到分享邮件点接受后，即可用房主的 `100.x.x.x` 地址联机。被分享设备默认隔离（quarantine）：成员只能连入本机，看不到房主的其他设备。
3. **邀请成员加入同一 tailnet**：成员各自注册账号，房主用同一令牌按邮箱发送 tailnet 邀请（API `POST /api/v2/tailnet/-/invitations`，角色固定 `member`：可使用网络设备、不能进管理控制台）。成员接受邀请并 `tailscale login` 登录自己的账号后，tailnet 内互通。

> 说明：CLI 没有 share/invite 子命令，邀请链接也无法通过 API 生成（只能在网页控制台手动生成），因此方式 2/3 统一走官方控制面 API 按邮箱发送；本机设备通过 `tailscale status --json` 的 Self.ID（nodeId）与设备列表的 nodeId/主机名匹配得到数字设备 ID。成员侧指引在向导弹窗/`mesh join` 中查看。

## 配置

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MCTUNNEL_CHAT_PORT` | `8788` | 聊天 WebSocket 端口 |
| `MCTUNNEL_NODE_ID` | 自动生成 | 本节点 ID（用于组网） |
| `MCTUNNEL_PEERS` | 空 | 对等节点地址，逗号分隔（如 `ws://a:8788,ws://b:8788`） |
| `MCTUNNEL_WEB_PORT` | `8787` | WebUI HTTP 端口 |
| `MCTUNNEL_RELAY_PORT` | `8721` | 云中继地址的默认端口 |
| `MCTUNNEL_ROOM_SERVER_PORT` | `8721` | 房主本地房间中继端口 |

### 运行时数据

数据目录统一为 `{gameDir}/mctunnel/`，`DataDir` 自身**不再提供任何兜底默认值**——宿主未调用 `DataDir.set()` 就读取会直接抛 `IllegalStateException`（历史上它曾默认 `~/.mctunnel/`，该路径现已废弃，那里的旧库与二进制缓存不再被读写）。

- **Forge 模组**：`FMLPaths.GAMEDIR.get().resolve("mctunnel")`
- **独立 CLI**：按优先级解析 gameDir —— `--game-dir` 参数 > 环境变量 `MCTUNNEL_GAME_DIR` > jar 所在 `mods/` 的上一级 > 当前目录下有 `.minecraft` > `~/.minecraft` > 当前工作目录
- CLI 每次启动都会打印实际数据目录及其**命中来源**；落到最后一项（当前工作目录）时额外告警，提示换个目录运行会另建一份库，建议用 `--game-dir` 固定

配置项与数据分离：模组配置走 Forge 标准 `ForgeConfigSpec`（`ModConfig.Type.COMMON`），落在 `<gameDir>/config/` 下的 toml 文件，不在 `mctunnel/` 数据目录里。
```
{gameDir}/mctunnel/
├── mctunnel.db              # SQLite 数据库(聊天记录/配置/节点)
└── binaries/
    ├── ngrok/               # ngrok 二进制
    ├── tailscale/           # tailscale 二进制
    └── syncthing/           # syncthing 二进制
```

> 云中继**不落任何盘**：房间注册表是 `RelayServer` 内部纯内存结构，进程重启即清空；成员凭 `mctunnel://` 链接重连时按需重建房间。

## Android / FCL 部署

1. 在 Termux 中安装 JDK 17
2. 运行 `java -jar mctunnel.jar serve`
3. 在 FCL 启动器中加载模组
4. 模组自动连接 `http://localhost:8787` 后端

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/status` | 所有工具状态（含本地/最新版本、更新标记） |
| GET | `/api/signup-url` | ngrok 注册/登录页地址 |
| POST | `/api/check/{tool}` | 检查更新（官方渠道） |
| POST | `/api/install/{tool}` | 下载安装工具；body 可选 `{"extractDir":"...","force":true}`；Windows ngrok 缺 extractDir 时返回 `{"needsExtractDir":true}` |
| POST | `/api/update/{tool}` | 更新到官方最新版 |
| POST | `/api/start/{tool}` | 启动工具 |
| POST | `/api/stop/{tool}` | 停止工具 |
| POST | `/api/daemon-start/{tool}` | 启动后台守护进程（tailscaled） |
| POST | `/api/mesh/tailscale/token` | 验证并保存 Tailscale API 令牌（body `{"apiToken":"tskey-..."}`） |
| POST | `/api/mesh/tailscale/share` | 把本机分享给邮箱（body `{"emails":"a@b.com, c@d.com"}`） |
| POST | `/api/mesh/tailscale/invite` | 邀请邮箱加入同一 tailnet（body 同上） |
| POST | `/api/mesh/tailscale/clear-token` | 清除已保存的 API 令牌 |
| POST | `/api/daemon-stop/{tool}` | 停止后台守护进程 |
| POST | `/api/configure/{tool}` | 配置工具(JSON body) |
