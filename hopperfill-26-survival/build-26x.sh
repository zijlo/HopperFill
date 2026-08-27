#!/usr/bin/env bash
# HopperFill 26.x 多版本构建脚本 (26.1 / 26.1.1 / 26.1.2 / 26.2)
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE=/opt/gradle-9.5.1/bin/gradle
PROJECT=/workspace/hopperfill-26-survival
DIST=/workspace/dist

mkdir -p "$DIST"

# 版本矩阵：mc | fabric-api | minecraft_dependency
MATRIX=$(cat <<'EOF'
26.1|0.145.1+26.1|>=26.1 <26.1.1
26.1.1|0.145.4+26.1.1|>=26.1.1 <26.1.2
26.1.2|0.155.2+26.1.2|>=26.1.2 <26.2
26.2|0.158.0+26.2|>=26.2 <26.3
EOF
)

write_props() {
    local mc="$1" api="$2" dep="$3"
    cat > "$PROJECT/gradle.properties" <<EOF
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
minecraft_version=$mc
loader_version=0.19.3
loom_version=1.17-SNAPSHOT
fabric_api_version=$api

# Mod
mod_version=4.0.0
maven_group=com.zijlo
archives_base_name=hopperfill
minecraft_dependency=$dep
EOF
}

while IFS='|' read -r mc api dep; do
    [ -z "$mc" ] && continue
    echo ""
    echo "=================================================="
    echo ">> 构建 Minecraft $mc (api=$api)"
    echo "=================================================="

    write_props "$mc" "$api" "$dep"

    rm -rf "$PROJECT/build"

    "$GRADLE" -p "$PROJECT" build --console=plain --no-daemon

    jar=$(find "$PROJECT/build/libs" -maxdepth 1 -name "*.jar" ! -name "*-dev.jar" ! -name "*-sources.jar" | head -n1)
    if [ -z "$jar" ]; then
        echo "!! 未找到产物 jar for $mc" >&2
        exit 1
    fi
    cp "$jar" "$DIST/hopperfill-$mc.jar"
    echo ">> 产物: $DIST/hopperfill-$mc.jar"
done <<< "$MATRIX"

echo ""
echo "=================================================="
echo "26.x 全部构建完成，输出目录: $DIST"
ls -la "$DIST"