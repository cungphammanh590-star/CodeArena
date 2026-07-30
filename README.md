# LeetCode Tracker

完全本地的 **leetcode.cn** 刷题追踪助手（**v0.3.4**）。  
在力扣正常提交后，浏览器扩展把记录写到本机；可选 AI 陪练帮你复盘。**刷题数据不出本机。**

> **版本线**：轻量本地能力以 Release [`v0.3.4`](https://github.com/cungphammanh590-star/leetcode-tracker/releases/tag/v0.3.4) / 维护支 [`local-stable`](https://github.com/cungphammanh590-star/leetcode-tracker/tree/local-stable) 为准（只修缺陷）。  
> 本支 `main` 将继续演进可选的云端「智能教练」；只需本地轻量版请安装 `v0.3.4` 或跟踪 `local-stable`。

不限定操作系统：只要本机能跑 **Python 3.9+**，并用 **Chrome / Edge**（或同内核浏览器）加载扩展即可。

---

## 你需要准备什么

- Python **3.9+**（终端里能执行 `python` / `python3` 与 `pip`）
- Chrome 或 Edge（用于加载扩展 + 打开仪表盘）
- [leetcode.cn](https://leetcode.cn) 账号（照常刷题）
- 可选陪练：本机 [Ollama](https://ollama.com/) 模型，或在维护台填写 DeepSeek API Key

---

## 怎么安装（两种方式任选）

### 方式 A：下载 ZIP（适合不想用 Git 的用户）

1. 打开仓库页面，下载源码压缩包并解压，例如：
   - GitHub：`Code` → `Download ZIP`，或
   - [Releases](https://github.com/cungphammanh590-star/leetcode-tracker/releases) 里的 **Source code (zip)**
2. 在终端进入解压后的目录（目录名可能带 `-main` / 版本号，以你解压出来的为准）：

```bash
cd leetcode-tracker-main
# 若你解压的是带版本号的包，目录名会不同，改成实际路径即可
```

3. 安装本机命令：

```bash
# 核心：追踪 + 仪表盘
python -m pip install .

# 可选：陪练依赖（本地 Ollama / DeepSeek）
python -m pip install ".[coach]"
```

若系统默认命令是 `python3` / `pip3`，把上面的 `python` 换成 `python3` 即可。

4. 浏览器扩展：使用**同一份解压目录**里的 `extension/` 文件夹（见下方「日常使用」第 2 步）。  
   也可以从 [Release v0.3.4](https://github.com/cungphammanh590-star/leetcode-tracker/releases/tag/v0.3.4) 单独下载扩展 zip，解压后加载其中的 `extension/`。

### 方式 B：用 pip 从 Git 安装

```bash
python -m pip install git+https://github.com/cungphammanh590-star/leetcode-tracker.git@v0.3.4

# 可选陪练
python -m pip install "leetcode-tracker[coach]"
```

扩展仍需单独准备：克隆仓库，或从 Release 下载扩展 zip，加载其中的 `extension/`。

---

## 日常使用（装好后每次这样跑）

```bash
# 启动本机服务（唯一必需）
leetcode-tracker serve
```

然后：

1. 浏览器打开仪表盘：**http://127.0.0.1:8763/**
2. 打开扩展管理页（Chrome：`chrome://extensions`，Edge：`edge://extensions`）  
   → 打开「开发者模式」→ **加载已解压的扩展程序** → 选中 `extension/` 目录
3. 在 [leetcode.cn](https://leetcode.cn) 正常做题、提交；扩展角标显示 ok 即表示已记录
4. 需要陪练：点通知或扩展弹窗里的「打开陪练」，在页面发消息即可  
   （未装 `[coach]` / 未开 Ollama 也不影响采集）

维护台（清日志、重建统计、重建路线图、题单导入、陪练模型）：**http://127.0.0.1:8763/ops**

若改过端口：

```bash
leetcode-tracker config set port 9000
```

然后重启 `serve`，并在扩展里确认端口一致后重载扩展。

---

## 页面一览

| 地址 | 做什么 |
|------|--------|
| `/` | 按天概览、当日题目/错题、近 7 日、最近提交 |
| `/ops` | 维护台 |
| `/coach` | 陪练对话 |
| `/problems/{题号}` | 单题详情 |

---

## 自定义题单（可选）

在维护台 **http://127.0.0.1:8763/ops** 的「学习偏好与题单」里导入：

1. 选择 **已有题单**，或 **新建题单**（填名称即可，ID 自动生成）
2. 粘贴题目 JSON：根对象 `{"problems":[...]}`，或直接是题目数组
3. 写入方式默认 **追加**（同题去重跳过）；需要整表替换时选 **覆盖整表**
4. 点 **导入题目**

每题字段：`id`、`slug`、`title`（或 `title_cn`）、`difficulty`、`tags`、`order`。  
题单身份由页面选择，JSON 里不要写 `_meta`。维护台里可查看样例 JSON。

---

## 常用命令

| 命令 | 用途 |
|------|------|
| `leetcode-tracker serve` | 启动本机服务（自动就绪内嵌路线图与 Hot100 题单） |
| `leetcode-tracker kg import` | 强制重建知识图谱（维护用；日常不必） |
| `leetcode-tracker kg progress --track dp` | 查看某条路线进度 |
| `leetcode-tracker config set llm.coach_model <name>` | 更换本地陪练模型 |
| `leetcode-tracker autostart install` | 开机自动 `serve`（当前实现偏 macOS LaunchAgent；其他系统可自行用系统服务/计划任务跑 `serve`） |

---

## 数据在哪

默认写在用户主目录下（各系统路径形式可能略有不同）：

| 内容 | 路径 |
|------|------|
| 刷题记录 / 图谱 / 陪练会话 | `~/.local/share/leetcode-tracker/leetcode.db` |
| 配置 | `~/.config/leetcode-tracker/config.json` |

「今日」统计按 **中国时区（北京时间）** 切日；仪表盘可切换日期回顾历史一天。

---

## 陪练小提示（可选）

- 只追踪：不必装 Ollama，也不必填 API Key
- 本地模型：先启动 Ollama 并拉取模型，再到陪练页发消息
- 云端：打开 **http://127.0.0.1:8763/ops** →「陪练模型」选 DeepSeek，填写 Key 后保存
- 模型超时/失败会有兜底回复，**不影响提交采集**
- 若维护台提示缺少 `langchain-openai` 等，再执行一次 `pip install ".[coach]"`（ZIP 安装）或 `pip install "leetcode-tracker[coach]"` 后重启 `serve`

---

## 说明

- 仅支持 **leetcode.cn**，无云同步
- 未安装 `[coach]` 时，采集与统计照常可用
- 图谱覆盖约 890+ 题，启动时自动就绪；图谱外的题仍可陪练，只是缺少路线位置信息
- 首页可开关「题单模式 / 知识图谱模式」、管理活跃题单与已掌握名单；默认双模式开启、题单为 Hot100
- 维护台里的破坏性操作需要确认；本版不提供网页清空全部提交、也不在网页改端口

## 图谱来源

路线图来自 **algorithm-stone**（MIT），随包装在 `leetcode_tracker/data/algorithm_stone/maps/`。
