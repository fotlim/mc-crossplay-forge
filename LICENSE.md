# 许可证

## 本仓库内容

本项目包含两部分，分别适用不同许可：

### 1. 文档与脚本（`docs/`、`systemd/`、`tools/raknet-ping.py`、`tools/build.sh`）

**MIT License**

```
Copyright (c) 2026 fotlim

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 2. 字节码补丁程序（`tools/PatchNeoForge.java`、`tools/PatchViaProxy.java`、`tools/VerifyPatch.java`）

同样 **MIT**，但需要额外声明：

这些程序**不包含**任何 NeoForge / ViaProxy / ViaVersion 的代码，它们只是读取你的 jar、修改其中的字节码、写回。分发的是「修改方法」，不是「修改后的产物」。

**修改后的 jar 的再分发**需要遵守原项目的许可证（NeoForge 是 LGPL-2.1，ViaVersion 是 GPL-3.0）。本仓库**不提供**修改后的 jar。

## 第三方组件

本项目在使用时需要你自行下载的组件：

| 组件 | 许可 | 项目 |
|---|---|---|
| NeoForge | LGPL-2.1 | https://github.com/neoforged/NeoForge |
| ViaProxy | GPL-3.0 | https://github.com/ViaVersion/ViaProxy |
| ViaVersion | GPL-3.0 | https://github.com/ViaVersion/ViaVersion |
| Geyser | MIT | https://github.com/GeyserMC/Geyser |
| Floodgate | MIT | https://github.com/GeyserMC/Floodgate |
| SakuraFrp | 专有（客户端免费） | https://www.natfrp.com |
| ASM | BSD-3-Clause | https://gitlab.ow2.org/asm/asm |
| Minecraft | 专有 | Mojang / Microsoft |

本仓库**不分发**上述任何组件的二进制文件。

## 免责

修改字节码绕过上游有意设置的安全/兼容检查，只适合自己玩的私人服务器。

- 不要用于公开服务器
- 不要指望上游支持
- 出问题自己 debug
- 如果哪天官方提供了原生支持，请优先用官方的

使用本项目造成的数据丢失、服务器崩溃、账号问题，作者不承担责任。
