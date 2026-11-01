# RakNet Unconnected Ping —— 不用开游戏就能验证基岩服通不通
#
# 用法:
#   python3 raknet-ping.py <host> <port>
#   python3 raknet-ping.py frp-end.com 38538
#
# 原理:
#   基岩版客户端连服务器前会发一个 Unconnected Ping 探活，
#   服务端回 Unconnected Pong，里面带 MOTD / 版本 / 玩家数。
#   这个脚本手搓那个包，所以不需要装 MC 客户端。

import socket
import struct
import sys
import time

# RakNet 离线消息魔数（官方写死的常量）
MAGIC = bytes([0x00, 0xFF, 0xFF, 0x00, 0xFE, 0xFE, 0xFE, 0xFE,
               0xFD, 0xFD, 0xFD, 0xFD, 0x12, 0x34, 0x56, 0x78])

UNCONNECTED_PING = 0x01
UNCONNECTED_PONG = 0x1C


def ping(host: str, port: int, timeout: float = 5.0) -> dict:
    """发 Unconnected Ping，解析 Unconnected Pong。"""
    # 包结构: 0x01 + 时间戳(8, int64 BE) + 魔数(16) + client GUID(8)
    timestamp = int(time.time() * 1000) & 0xFFFFFFFFFFFFFFFF
    payload = (bytes([UNCONNECTED_PING])
               + struct.pack(">Q", timestamp)
               + MAGIC
               + struct.pack(">Q", 0x1234567890ABCDEF))

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(payload, (host, port))
        data, addr = sock.recvfrom(4096)
    finally:
        sock.close()

    if not data or data[0] != UNCONNECTED_PONG:
        raise RuntimeError(f"不是 Unconnected Pong（首字节 {data[0]:#x}）")

    # Pong 结构: 0x1C + 时间戳(8) + serverGUID(8) + 魔数(16) + 字符串长度(2) + 字符串
    offset = 1 + 8 + 8 + 16
    strlen = struct.unpack(">H", data[offset:offset + 2])[0]
    offset += 2
    text = data[offset:offset + strlen].decode("utf-8", "replace")

    # 字符串用分号分隔:
    #   MCPE;<MOTD>;<协议号>;<版本>;<玩家数>;<最大玩家数>;<serverGUID>;<世界名>;<游戏模式>;<端口v4>;<端口v6>
    parts = text.split(";")
    return {
        "from": f"{addr[0]}:{addr[1]}",
        "edition": parts[0] if len(parts) > 0 else "",
        "motd": parts[1] if len(parts) > 1 else "",
        "protocol": parts[2] if len(parts) > 2 else "",
        "version": parts[3] if len(parts) > 3 else "",
        "players": parts[4] if len(parts) > 4 else "",
        "max_players": parts[5] if len(parts) > 5 else "",
        "raw": text,
    }


def main():
    if len(sys.argv) < 3:
        print(__doc__ or "用法: raknet-ping.py <host> <port>")
        sys.exit(1)
    host, port = sys.argv[1], int(sys.argv[2])
    try:
        info = ping(host, port)
    except socket.timeout:
        print(f"超时 —— {host}:{port} 没有响应")
        print("可能原因: 端口没开 / 防火墙拦了 / 隧道断了 / 服务没起")
        sys.exit(2)
    except Exception as exc:
        print(f"失败: {exc}")
        sys.exit(3)

    print(f"来自 {info['from']}")
    print(f"版本       {info['version']} (protocol {info['protocol']})")
    print(f"MOTD       {info['motd']}")
    print(f"玩家       {info['players']}/{info['max_players']}")


if __name__ == "__main__":
    main()
