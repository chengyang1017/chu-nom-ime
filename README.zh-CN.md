# Android 喃字输入法

[English](README.md) | **简体中文**

一个用于输入 **越南语与喃字（Chữ Nôm）** 的原生 Android 输入法，支持离线候选检索、越南语 Telex 输入、句级候选生成，以及专门的汉喃字体显示。

这个项目基于 Android 的 `InputMethodService` API，用 Kotlin 构建。它不是一个单独的文字转换网页，而是真正的系统输入法：安装后可以在 Android 设置中启用，并直接在其他 App 的输入框里使用。

---

## 截图

> 这里先保留截图占位。准备好图片后放到 `docs/screenshots/` 即可。

### 键盘界面

📸 **截图占位：** `docs/screenshots/keyboard.png`

### 越南语 → 喃字候选

📸 **截图占位：** `docs/screenshots/candidates.png`

### 句子输入

📸 **截图占位：** `docs/screenshots/sentence-input.png`

### Telex 输入

📸 **截图占位：** `docs/screenshots/telex.png`

### 设置页面

📸 **截图占位：** `docs/screenshots/settings.png`

### 平板布局

📸 **截图占位：** `docs/screenshots/tablet.png`

---

## 项目目标

这个项目希望让喃字真正进入日常输入流程，而不是只存在于词典、静态转换网页或“复制粘贴”工作流里。

当前 Android 应用已经包含：

- 真正的 Android IME 输入法服务
- 越南语键盘输入
- Telex 组合输入
- 离线喃字候选检索
- 候选排序
- 句级候选生成
- 短语切分支持
- 越南语声调恢复基础结构
- 本地数据库 / 内存索引
- 专门的汉喃字体
- 用于启用和选择输入法的设置页面

---

## 输入流程

整体输入链可以理解为：

```text
键盘输入
   ↓
越南语 / Telex 组合
   ↓
输入状态
   ↓
本地喃字引擎
   ↓
候选生成
   ↓
候选排序
   ↓
句子组合
   ↓
Android InputConnection
   ↓
提交到其他 App 的输入框
```

这样可以把键盘 UI、语言处理、候选检索、句级组合和 Android 文本提交分别处理，而不是全部挤在一个类里。

---

## Android 输入法架构

输入法作为真正的 Android IME 注册：

```text
NomInputMethodService
        ↓
KeyboardController
        ↓
NomInputState / SentenceCompositionState
        ↓
NomEngine / SentenceNomEngine
        ↓
InputConnectionController
        ↓
其他 Android App 的输入框
```

`NomInputMethodService` 在 Manifest 中使用 `BIND_INPUT_METHOD` 权限，因此安装后可以出现在系统键盘列表中。

应用还提供设置页面，方便用户：

- 启用输入法
- 选择当前输入法

---

## 越南语输入

### Telex 组合

项目没有把拉丁字母输入简单当成普通字符串，而是单独实现了 `TelexComposer`。

相关组件包括：

```text
TelexComposer.kt
VietnameseInputParser.kt
```

这样越南语拼写处理可以和后面的喃字候选引擎保持独立。

---

## 喃字候选引擎

本地引擎按照不同职责拆分：

```text
NomEngine
├── LocalNomEngine
├── NomCandidateRanker
├── 本地数据仓库
└── 本地搜索索引
```

项目包含这些数据结构：

```text
NomCandidate
NomSearchEntry
NomSourceEntry
NomSentenceCandidate
```

本地数据层包括：

```text
NomCsvLoader
NomDatabase
NomMemoryIndex
Utf8CsvReader
```

设计重点是让常规候选查询可以离线完成，而不是每敲一个键都请求网络。

---

## 句级处理

这个项目已经不只是单字或单词查询。

句子引擎目前包含：

```text
SentenceNomEngine
SentenceCandidateGenerator
SentenceCandidateRanker
NomPhraseSegmenter
VietnameseToneRestorer
SentenceQueryContext
LatestQueryCoordinator
```

这套结构为“句级输入”打基础：输入法可以根据一整段越南语去生成和比较多组喃字候选，而不是逼用户一个音节一个音节地转换。

简化后可以理解为：

```text
越南语句子
    ↓
短语切分
    ↓
候选生成
    ↓
候选组合
    ↓
排序
    ↓
推荐喃字句子
```

---

## 离线数据

输入法把喃字数据直接打包在本地。

当前资源包括：

```text
app/src/main/assets/
├── hannom_rcv_standard_nom.csv
├── hannom_rcv_metadata.json
├── fonts/
└── licenses/
```

应用设置页面中明确说明，该键盘可离线运行，并使用来自 **Hội Bảo tồn Di sản chữ Nôm** 的数据。

仓库里还包含 Python 数据预处理工具：

```text
tools/extract_hannom_rcv.py
```

以及对应的测试脚本。

---

## 字体支持

很多喃字并不能稳定依赖普通 Android 系统字体显示。

因此仓库中包含专门的汉喃字体：

```text
han_nom_primary.ttf
plangothic_p1.ttf
```

相关字体许可证也一起保存在项目资源中。

对于输入法来说，生成正确 Unicode 字符还不够；如果候选栏无法正确显示这些字，实际使用体验仍然会失败，所以字体支持是这个项目的重要一部分。

---

## 项目结构

```text
chu-nom-ime/
├── app/
│   └── src/main/
│       ├── assets/
│       │   ├── fonts/
│       │   ├── licenses/
│       │   ├── hannom_rcv_metadata.json
│       │   └── hannom_rcv_standard_nom.csv
│       │
│       ├── java/com/example/chineseime/
│       │   ├── data/
│       │   ├── engine/
│       │   │   └── sentence/
│       │   ├── ime/
│       │   └── ui/
│       │
│       ├── res/
│       └── AndroidManifest.xml
│
├── tools/
│   ├── extract_hannom_rcv.py
│   └── test_extract_hannom_rcv.py
│
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 技术栈

### Android

- Kotlin
- Android SDK
- `InputMethodService`
- AndroidX
- Material Components
- ConstraintLayout

### 语言处理

- 越南语 Telex 组合
- 越南语输入解析
- 候选排序
- 短语切分
- 句级候选生成
- 声调恢复基础结构

### 本地数据

- CSV 源数据
- 本地数据库层
- 内存搜索索引
- 离线元数据

### 工具

- Gradle Kotlin DSL
- JUnit
- Android Instrumentation Test
- Python 数据预处理脚本

---

## 构建要求

当前 Android 模块配置：

```text
minSdk 24
targetSdk 36
Java 17
```

可以直接用 Android Studio 构建，也可以使用 Gradle。

### Windows

```powershell
.\gradlew.bat assembleDebug
```

### macOS / Linux

```bash
./gradlew assembleDebug
```

安装 APK 后，再到 Android 的输入法设置中启用该键盘。

---

## 测试

仓库同时包含单元测试和 Android 仪器测试基础设施。

句子引擎还包含设备性能测试。

常用检查：

```bash
./gradlew test
./gradlew connectedAndroidTest
```

---

## 设计方向

这个项目并不希望用户一直在“普通越南语键盘”和“喃字转换器”之间来回切换。

长期方向是让：

```text
越南语输入
   +
喃字候选
   +
上下文
   +
句级排序
   =
真正可用的喃字输入体验
```

也就是说，拉丁字母输入、喃字候选、短语判断和句子建议应该尽量共存在同一个键盘工作流里。

---

## 状态

**持续开发中。**

仓库目前已经包含原生 Android IME 服务、键盘控制器、本地喃字数据层、Telex / 越南语处理、句级候选引擎、离线资源、字体支持和设置流程。

接下来的重点是继续改善候选质量、句级行为、输入手感、设备兼容性，并让它逐步接近真正可日常使用的 Android 喃字输入法。
