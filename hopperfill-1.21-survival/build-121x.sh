#!/usr/bin/env bash
# HopperFill 1.21.x 全版本构建脚本（12 个小版本）
# 版本敏感文件（客户端 GUI）按版本组自动替换：TemplateScreen、SettingsScreen。
set -euo pipefail

export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE=/opt/gradle-9.5.1/bin/gradle
PROJECT=/workspace/hopperfill-1.21-survival
DIST=/workspace/dist

mkdir -p "$DIST"

MAIN_TEMPLATE="$PROJECT/src/main/java/com/zijlo/hopperfill/client/gui/TemplateScreen.java"
MAIN_SETTINGS="$PROJECT/src/main/java/com/zijlo/hopperfill/client/gui/SettingsScreen.java"
VAR_TEMPLATE="$PROJECT/versions/template"
VAR_SETTINGS="$PROJECT/versions/settings"

# 备份主文件（用于每次构建后恢复，保证源码树始终为最新版本）
TEMPLATE_BAK="$(cat "$MAIN_TEMPLATE")"
SETTINGS_BAK="$(cat "$MAIN_SETTINGS")"

# 版本矩阵：mc | yarn_mappings | fabric_api | 模板变体 | 设置变体
# 模板变体: v1_21_0(1.21.0-1.21.1) / v1_21_2(1.21.2-1.21.5) / current(1.21.6+)
# 设置变体: v1_21_0(1.21.0-1.21.8) / current(1.21.9+)
MATRIX=$(cat <<'EOF'
1.21|1.21+build.9|0.102.0+1.21|v1_21_0|v1_21_0
1.21.1|1.21.1+build.3|0.116.15+1.21.1|v1_21_0|v1_21_0
1.21.2|1.21.2+build.1|0.106.1+1.21.2|v1_21_2|v1_21_0
1.21.3|1.21.3+build.2|0.114.1+1.21.3|v1_21_2|v1_21_0
1.21.4|1.21.4+build.8|0.119.4+1.21.4|v1_21_2|v1_21_0
1.21.5|1.21.5+build.1|0.128.2+1.21.5|v1_21_2|v1_21_0
1.21.6|1.21.6+build.1|0.128.2+1.21.6|current|v1_21_0
1.21.7|1.21.7+build.8|0.129.0+1.21.7|current|v1_21_0
1.21.8|1.21.8+build.1|0.136.1+1.21.8|current|v1_21_0
1.21.9|1.21.9+build.1|0.134.1+1.21.9|current|current
1.21.10|1.21.10+build.3|0.138.4+1.21.10|current|current
1.21.11|1.21.11+build.6|0.141.6+1.21.11|current|current
EOF
)

write_props() {
    local mc="$1" yarn="$2" api="$3"
    cat > "$PROJECT/gradle.properties" <<EOF
# Gradle
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
org.gradle.configuration-cache=false

# 依赖下载直连（如需代理自行补充 systemProp.*.proxyHost/Port）

# Fabric（Yarn 映射）
minecraft_version=$mc
yarn_mappings=$yarn
loader_version=0.19.3
fabric_version=$api

# Mod
mod_version=4.0.1
maven_group=com.zijlo
archives_base_name=hopperfill
artifact_suffix=mc$mc
minecraft_dependency=>=$mc <1.22
EOF
}

restore_main() {
    printf '%s' "$TEMPLATE_BAK" > "$MAIN_TEMPLATE"
    printf '%s' "$SETTINGS_BAK" > "$MAIN_SETTINGS"
}

# 无论成功或失败，退出时都恢复主文件，避免把版本变体残留在源码树中
trap restore_main EXIT

while IFS='|' read -r mc yarn api tv sv; do
    [ -z "$mc" ] && continue
    echo ""
    echo "=================================================="
    echo ">> 构建 Minecraft $mc (yarn=$yarn, api=$api)"
    echo "=================================================="

    write_props "$mc" "$yarn" "$api"

    # 替换模板变体
    if [ "$tv" = "current" ]; then
        printf '%s' "$TEMPLATE_BAK" > "$MAIN_TEMPLATE"
    else
        cp "$VAR_TEMPLATE/$tv.java" "$MAIN_TEMPLATE"
    fi
    # 替换设置变体
    if [ "$sv" = "current" ]; then
        printf '%s' "$SETTINGS_BAK" > "$MAIN_SETTINGS"
    else
        cp "$VAR_SETTINGS/$sv.java" "$MAIN_SETTINGS"
    fi

    rm -rf "$PROJECT/build"
    "$GRADLE" -p "$PROJECT" build --console=plain --no-daemon

    jar=$(find "$PROJECT/build/libs" -maxdepth 1 -name "*.jar" ! -name "*-dev.jar" ! -name "*-sources.jar" | head -n1)
    if [ -z "$jar" ]; then
        echo "!! 未找到产物 jar for $mc" >&2
        restore_main
        exit 1
    fi
    cp "$jar" "$DIST/hopperfill-4.0.1-fabric-mc$mc.jar"
    echo ">> 产物: $DIST/hopperfill-4.0.1-fabric-mc$mc.jar"
done <<< "$MATRIX"

restore_main
echo ""
echo "=================================================="
echo "1.21.x 全部构建完成，输出目录: $DIST"
ls -la "$DIST"
