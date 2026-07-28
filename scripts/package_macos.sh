#!/usr/bin/env bash
# 打包 macOS 发行包（v0.3.2+）：解压后含 extension/ + 使用说明（无桌面壳）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(python3 -c "from leetcode_tracker import __version__; print(__version__)" 2>/dev/null || echo "0.3.4")"
STAGE="$ROOT/release/stage"
OUT_DIR="$ROOT/release"
DIST_NAME="LeetCode-Tracker-macOS-v${VERSION}"
BUNDLE="$STAGE/$DIST_NAME"

cd "$ROOT"

echo "==> 清理旧产物"
rm -rf "$STAGE"
mkdir -p "$BUNDLE/extension"

echo "==> 复制浏览器扩展"
rsync -a --delete \
  --exclude '.DS_Store' \
  "$ROOT/extension/" "$BUNDLE/extension/"

echo "==> 写入使用说明"
cat > "$BUNDLE/GettingStarted.txt" << EOF
LeetCode Tracker v${VERSION}（仅支持 leetcode.cn）

解压后目录：
  extension/           — Chrome / Edge 浏览器扩展（必装）
  GettingStarted.txt   — 本文件
  README.md            — 完整说明

推荐安装与使用：

1. 安装本机服务
   pip install git+https://github.com/cungphammanh590-star/leetcode-tracker.git@v${VERSION}

   可选陪练：
   pip install 'leetcode-tracker[coach]'
   （本地 Ollama，或维护台填写 DeepSeek API Key）

2. 启动服务
   leetcode-tracker serve

3. 浏览器打开仪表盘
   http://127.0.0.1:8763/
   维护台：http://127.0.0.1:8763/ops

4. 加载扩展
   chrome://extensions → 开发者模式 → 加载已解压的扩展程序
   → 选择本目录下的 extension 文件夹

5. 在 leetcode.cn 正常做题提交
   扩展角标显示 ok 表示已记录
   需要复盘时点通知或弹窗「打开陪练」

可选：首页可开关题单 / 知识图谱模式；维护台可导入自定义题单。
图谱随包装载，启动时自动就绪（日常不必再 kg import）。

数据保存在本机：
  刷题记录：~/.local/share/leetcode-tracker/leetcode.db
  配置：~/.config/leetcode-tracker/config.json

说明：本版已取消桌面 App，请用终端 serve + 浏览器。
EOF

cp "$ROOT/README.md" "$BUNDLE/README.md"

echo "==> 打 zip"
mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/${DIST_NAME}.zip"
rm -f "$ZIP"
xattr -cr "$BUNDLE" 2>/dev/null || true
find "$BUNDLE" -name '._*' -delete 2>/dev/null || true
(
  cd "$STAGE"
  zip -qry -X "$ZIP" "$DIST_NAME" -x '*.DS_Store' -x'*/._*'
)

echo "==> 完成: $ZIP"
ls -lh "$ZIP"
