#!/usr/bin/env bash
# HopperFill 单一 JAR 构建脚本（覆盖 Minecraft 1.21 及以上所有版本）
# 编译基准版本 1.21，fabric.mod.json 声明 "minecraft": ">=1.21"。
# 代码只使用跨 1.21~1.21.11 稳定的 intermediary（如 interactionManager.isCreative()），
# 无需再按版本拆分，产物为一个 hopperfill-4.0.1-fabric-mc1.21.x.jar。
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-21-temurin
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE=/opt/gradle-9.5.1/bin/gradle
PROJECT=/workspace/hopperfill-1.21-survival
DIST=/workspace/dist

mkdir -p "$DIST"

cat > "$PROJECT/gradle.properties" <<'EOF'
# Gradle
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
org.gradle.configuration-cache=false

# 依赖下载直连（如需代理自行补充 systemProp.*.proxyHost/Port）

# Fabric（单一 1.21+ 基准版本，Yarn 映射）
minecraft_version=1.21
yarn_mappings=1.21+build.9
loader_version=0.19.3
fabric_version=0.102.0+1.21

# Mod
mod_version=4.0.1
maven_group=com.zijlo
archives_base_name=hopperfill
artifact_suffix=mc1.21.x
minecraft_dependency=>=1.21
EOF

rm -rf "$PROJECT/build"

"$GRADLE" -p "$PROJECT" build --console=plain --no-daemon

jar=$(find "$PROJECT/build/libs" -maxdepth 1 -name "*.jar" ! -name "*-dev.jar" ! -name "*-sources.jar" | head -n1)
if [ -z "$jar" ]; then
    echo "!! 未找到产物 jar" >&2
    exit 1
fi

cp "$jar" "$DIST/hopperfill-4.0.1-fabric-mc1.21.x.jar"
echo ""
echo "=================================================="
echo "构建完成，单一产物: $DIST/hopperfill-4.0.1-fabric-mc1.21.x.jar"
ls -la "$DIST"