# 一、链路逐层拆解

## 全景

```
手机基岩版 1.26.0.2 (协议 26.0)
    │
    │  RakNet UDP，走公网
    ▼
SakuraFrp 节点（frp-end.com:38538）
    │
    │  隧道转发回内网
    ▼
AM01 小主机 19132/udp
    │
    ▼
Geyser-ViaProxy 2.10.1-b1184
    │  基岩协议 26.0 ──► Java 协议 26.1
    ▼
ViaProxy 3.4.14 (127.0.0.1:25568)
    │  Java 协议 26.1 ──► 1.21.1
    │  Floodgate 认证透传
    ▼
NeoForge 1.21.1 (127.0.0.1:25565)
    │  37 个模组
    ▼
游戏世界
```

## 为什么要这么绕

### 为什么不直接用 Geyser-NeoForge 插件

因为**版本对不上**。

Geyser 官方 wiki 明说：Geyser 的 Fabric / NeoForge 平台**只支持最新的 Java 版**。你的服务端停在 1.21.1（为了那 37 个模组），而最新 Geyser 早就跳到 26.x 了。

Modrinth 上能查到的最后一版支持 1.21.1 的 Geyser-NeoForge 是 **2.4.4-b705**，它最高只认基岩 **1.21.44**。

手机是 **1.26.0.2**。差了一个大版本，连不上。

### 为什么 ViaProxy 能救

ViaProxy 是个**独立代理**，不跟服务端版本绑定。它站在服务端前面，对外假装成各种版本的服务器。

官方 wiki 也给了这条路：

> "This will also work with Floodgate auth"

所以架构变成：

```
Geyser（独立版，不是插件） ──► ViaProxy ──► NeoForge
```

Geyser-ViaProxy 2.10.1 支持基岩 26.0–26.33，ViaProxy 负责把 26.1 的协议往下翻到 1.21.1。**版本问题解决。**

### 然后是 NeoForge 的墙

ViaProxy 把协议翻好了，玩家数据填好了，连接进到 CONFIGURATION 阶段 —— 然后 NeoForge 说：

```
You are trying to connect to a server that is running NeoForge, but you are not.
```

这是 NeoForge 的**设计行为**，不是 bug。它检查客户端有没有 NeoForge 的网络通道注册，没有就踢。原版客户端一样被踢。

四道关卡详见 [02-neoforge-patches.md](02-neoforge-patches.md)。

## 各组件职责

| 组件 | 版本 | 职责 | 关键配置 |
|---|---|---|---|
| Geyser-ViaProxy | 2.10.1-b1184 | 基岩 RakNet ↔ Java 协议 | `java.auth-type: floodgate` |
| ViaProxy | 3.4.14（已打补丁） | Java 协议版本翻译 | `--target-address 127.0.0.1:25565` |
| Floodgate | 2.2.4-b38 | 让基岩玩家免正版验证进服 | `key.pem` 两处共用 |
| SakuraFrp frpc | 0.51.0-sakura-15.1 | 公网 → 内网 UDP 隧道 | 隧道映射 `127.0.0.1:19132` |
| NeoForge | 21.1.250（已打补丁） | 服务端本体 | 四道关卡已绕过 |

## 端口分配

| 端口 | 协议 | 用途 | 谁在听 |
|---|---|---|---|
| 25565 | TCP | Java 版直连 | NeoForge |
| 25568 | TCP | ViaProxy 内部 | ViaProxy（只听 127.0.0.1） |
| 19132 | UDP | 基岩版 RakNet | Geyser |
| 24454 | UDP | 语音模组 | Simple Voice Chat |

**注意 25568 只听 `127.0.0.1`** —— ViaProxy 不需要对外暴露，外面的流量全走 Geyser 的 19132。

## 认证链路

Floodgate 的关键是 `key.pem`：

```
mcserver/config/floodgate/key.pem     ← Floodgate 模组生成
        │
        │  复制
        ▼
viaproxy/plugins/Geyser/key.pem       ← Geyser 用它加密玩家身份
```

两边的密钥必须一致，否则 Geyser 加密的身份 Floodgate 解不开，玩家会被当成未认证。

基岩玩家在服务端的用户名会带一个点前缀（如 `.fotlim`），这是 Floodgate 的标记 —— 表示「这个玩家不是 Java 正版账号」。

## 数据流细节

一次连接完整走一遍：

1. 手机发 RakNet Unconnected Ping → Geyser 回 MOTD
2. 手机发 Login Packet → Geyser 验证基岩身份（Xbox Live）
3. Geyser 用 Floodgate 加密身份，向 ViaProxy 发起 Java 登录
4. ViaProxy 做协议翻译（26.1 → 1.21.1），转发给 NeoForge
5. NeoForge 走四道关卡 —— **补丁在这里生效**
6. NeoForge 把玩家放进世界，模组发私有 payload —— **补丁 4 在这里兜底**
7. 玩家能动

第 6 步是最后一道坎，也是最难查的 —— 玩家已经有 entity id 了，日志里「joined」都打出来了，然后在下一行断掉。
