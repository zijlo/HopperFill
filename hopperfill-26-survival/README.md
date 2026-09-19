# HopperFill — Minecraft 26.x

**简体中文** | [English](../README_EN.md)

本目录是 HopperFill 的 **26.x 分支**（Mojang 官方映射 / 非混淆，编译基准 26.1，需 JDK 25）。
完整的模组说明、命令表、木锄 4 步流程与源码结构见仓库根目录文档：

- 中文文档：[../README.md](../README.md)
- English docs: [../README_EN.md](../README_EN.md)

## 构建

```bash
./gradlew build          # 需 JDK 25
```

产物：`build/libs/hopperfill-4.0.1-fabric-mc26.x.jar`

需要 26.1 / 26.1.1 / 26.1.2 / 26.2 的单版本包时，用 `build-26x.sh` 构建。

源码位于 `src/main/java/com/zijlo/hopperfill/`。
