#!/usr/bin/env bash
# HopperFill 26+ 通用单一 JAR 构建脚本（覆盖 Minecraft 26.1 及以上所有 26.x 版本）
# 编译基准版本 26.1（最老），fabric.mod.json 声明 "minecraft": ">=26.1"。
# 代码仅使用跨 26.1~26.2 稳定的 Mojang 非混淆 API（染色潜影盒通过反射 + 注册表兜底兼容 26.2 合并）。
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE=/opt/gradle-9.5.1/bin/gradle
PROJECT=/workspace/hopperfill-26-survival
DIST=/workspace/dist

mkdir -p "$DIST"

cat > "$PROJECT/gradle.properties" <<'EOF'
# Gradle
org.gradle.jvmargs=-Xmx3G
org.gradle.parallel=true
org.gradle.configuration-cache=false

# 沙箱出口代理（依赖下载）
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=18080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=18080
systemProp.http.nonProxyHosts=localhost|127.0.0.1

# Fabric (Mojang 官方映射)
minecraft_version=26.1
loader_version=0.19.3
loom_version=1.17-SNAPSHOT
fabric_api_version=0.145.1+26.1

# Mod
mod_version=4.0.0
maven_group=com.zijlo
archives_base_name=hopperfill
minecraft_dependency=>=26.1
EOF

rm -rf "$PROJECT/build"

"$GRADLE" -p "$PROJECT" build --console=plain --no-daemon

jar=$(find "$PROJECT/build/libs" -maxdepth 1 -name "*.jar" ! -name "*-dev.jar" ! -name "*-sources.jar" | head -n1)
if [ -z "$jar" ]; then
    echo "!! 未找到产物 jar" >&2
    exit 1
fi

cp "$jar" "$DIST/hopperfill-26+.jar"
echo ""
echo "=================================================="
echo "26+ 通用构建完成，产物: $DIST/hopperfill-26+.jar"
ls -la "$DIST"
