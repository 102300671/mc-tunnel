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

多模块 Gradle 项目：

```
mc-tunnel/
├── core/          # 共享逻辑: 隧道适配、Web 服务、聊天、存储
├── standalone/    # 独立 CLI/Web 服务端
└── forge/         # Forge 1.20.1 模组(内嵌后端 + 游戏内 GUI)
```

**核心设计**：
- 零外部依赖（JDK 内置 HttpServer + 手写 WebSocket/JSON）
- 后端优先架构：存储（SQLite）、组网在后端完成
- 前后端分离：模组仅作 HTTP/WebSocket 客户端

## 构建

要求 JDK 17+。

```bash
# 构建所有模块
./gradlew build

# 仅构建独立服务端
./gradlew :standalone:jar

# 仅构建 Forge 模组
./gradlew :forge:jar
```

产物位置：
- 独立服务端：`standalone/build/libs/mctunnel-standalone.jar`
- Forge 模组：`forge/build/libs/mctunnel-forge-1.0.0-SNAPSHOT.jar`

## 使用

### 1. 独立服务端（推荐用于 Android/Termux）

```bash
java -jar mctunnel-standalone.jar serve
```

启动后：
- WebUI: <http://localhost:8787>
- 聊天 WebSocket: `ws://localhost:8788`

### 2. Forge 模组

将 `mctunnel-forge-*.jar` 放入 `.minecraft/mods/`，启动游戏后通过模组菜单管理工具。

桌面端模组会内嵌启动后端；Android（FCL）需手动在 Termux 运行独立服务端。

## 配置

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MCTUNNEL_CHAT_PORT` | `8788` | 聊天 WebSocket 端口 |
| `MCTUNNEL_NODE_ID` | 自动生成 | 本节点 ID（用于组网） |
| `MCTUNNEL_PEERS` | 空 | 对等节点地址，逗号分隔（如 `ws://a:8788,ws://b:8788`） |

### 运行时数据

所有运行时文件位于 `~/.mctunnel/`：

```
~/.mctunnel/
├── mctunnel.db              # SQLite 数据库(聊天记录/配置/节点)
└── binaries/
    ├── ngrok/               # ngrok 二进制
    ├── tailscale/           # tailscale 二进制
    └── syncthing/           # syncthing 二进制
```

## Android / FCL 部署

1. 在 Termux 中安装 JDK 17
2. 运行 `java -jar mctunnel-standalone.jar serve`
3. 在 FCL 启动器中加载模组
4. 模组自动连接 `http://localhost:8787` 后端

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/status` | 所有工具状态 |
| GET | `/api/status/{tool}` | 指定工具状态 |
| POST | `/api/install/{tool}` | 下载安装工具 |
| POST | `/api/start/{tool}` | 启动工具 |
| POST | `/api/stop/{tool}` | 停止工具 |
| POST | `/api/configure/{tool}` | 配置工具(JSON body) |
