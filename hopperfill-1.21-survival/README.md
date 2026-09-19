# HopperFill — Minecraft 1.21.x

**简体中文** | [English](../README_EN.md)

本目录是 HopperFill 的 **1.21.x 分支**（Yarn 映射，编译基准 1.21.11，需 JDK 21）。
完整的模组说明、命令表、木锄 4 步流程与源码结构见仓库根目录文档：

- 中文文档：[../README.md](../README.md)
- English docs: [../README_EN.md](../README_EN.md)

## 构建

```bash
./gradlew build          # 需 JDK 21
```

产物：`build/libs/hopperfill-4.0.1-fabric-mc1.21.x.jar`

需要 1.21.0 ～ 1.21.10 时，用 `build-121x.sh` 按小版本构建（会按版本组自动替换界面变体文件）。

源码位于 `src/main/java/com/zijlo/hopperfill/`。
