# 听幕 P0：端侧选型实测与真机测试清单（2026-10-29）

> 状态：Mac 侧全部完成；**真机数据待用户回填**。回填后更新《tingmu-dev-plan-20261029.md》P0 结论并定 P1 默认模型档。

## 一、Mac 侧基准矩阵（x64 Mac，100ms 分帧，greedy，threads=2）

| 模型 | int8 体积 | RTF_cpu | 真人语音表现 | 18s 中文测试句表现 | 结论 |
|---|---|---|---|---|---|
| **zipformer-small-ctc-zh-2025-04-01** | **26MB** | **0.040** | ✓ 干净（5.6s 真人片段零错） | 同音字少量（听幕→听目/听账），句子完整无重复 | **P1 默认档** |
| ctc-zh-2025-06-30 | 162MB | 0.120 | — | 最准（"听障"都对），但慢 3 倍 | 旗舰可选档，真机验速后定 |
| zipformer-zh-14M-2023-02-23 | 26MB | 0.029 | — | 同音字多+局部重复（听木/识时/字字字幕/厅长） | 低端机兜底档 |
| zipformer-bilingual-zh-en-2023-02-20 | 200MB | 0.087 | — | **重复字伪影严重**（今天天/测测测试/字幕幕幕），换 modified_beam_search 无效 | **弃用** |
| zipformer-multi-zh-hans-2023-12-13 | 76MB | 0.092 | ✓（真人会议 1.8s 正确） | 错得多（伊夏天目/音乐挂号/听霜） | 弃用 |

要点：
- 全部模型**纯 CPU** 跑通真·流式，出字轨迹逐帧推进；端点检测（停顿 1.4s 定稿）工作正常。
- Mac x64 的 RTF 换算到普通手机（2-4× 慢）估计：small-ctc ≈ 0.1-0.2 → **大概率达标（红线 0.3）**；ctc-zh-2025 ≈ 0.3-0.5 → 踩线，须真机裁决。
- **同音字错是所有端侧小模型共性**（幕→目、障→账）→ AAR 里已有 `HomophoneReplacer` 接口可治，列入 P2。
- VAD（silero_vad.onnx，643KB）已验证：静音 False、人声段 True，可直接驱动左上角说话点。

## 二、APK（P0 真机验证壳）

- 路径：`/Users/yangyan/ZCodeProject/tingmu/听幕-p0-debug.apk`（92MB）
- 内容：sherpa-onnx 官方 AAR v2.0.1（arm64-v8a + armeabi-v7a）+ **内置双模型**（small-ctc-zh-2025 默认 / zipformer-zh-14M 兜底）+ Silero VAD
- 已预演 v1 三要素：全屏滚动大字字幕（横竖屏）／左上角蓝点（VAD 概率驱动）／触屏浮现右下控制条（3.5s 自动隐去）；配色默认白底黑字可切换；字号 5 档默认特大
- P0 专属（v1 移除）：右上角性能浮层（decode avg/max、RTF、native 内存）、开始/停止按钮、模型切换对话框
- 外挂模型加载：`adb push` 到 `/sdcard/tingmu/models/<目录名>/` 后在"模型…"里出现（本机·xxx）

复现构建：
```bash
cd /Users/yangyan/ZCodeProject/tingmu/android/Tingmu
./gradlew :app:assembleDebug --no-daemon
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

## 三、真机测试清单（普通手机，建议先装到目标"普通手机"上）

准备：
```bash
adb install -r /Users/yangyan/ZCodeProject/tingmu/听幕-p0-debug.apk
# 可选：外挂大模型对比（ctc-zh-2025 162MB）
adb push /Users/yangyan/ZCodeProject/tingmu/models/ctc-zh-2025/model.int8.onnx  /sdcard/tingmu/models/ctc-zh-2025/
adb push /Users/yangyan/ZCodeProject/tingmu/models/ctc-zh-2025/tokens.txt       /sdcard/tingmu/models/ctc-zh-2025/
```
（无 adb 就用微信文件传输助手传 APK 安装；外挂模型部分跳过。）

| # | 测试项 | 操作 | 记录 |
|---|---|---|---|
| 1 | 安装启动 | 装 APK→允许麦克风→点"开始/停止" | 能否出字 |
| 2 | **延迟** | 安静房间，正常语速说一句话 | 停口后多久定格（红线 ≤1s） |
| 3 | **准确率** | 连说 1 分钟日常对话 | 明显错字率主观打分（对比微信输入法录音笔） |
| 4 | **性能浮层** | 说话 1 分钟后读数：decode avg/max、RTF、native MB | RTF 红线 ≤0.3；native 记录数值 |
| 5 | 字号/配色 | A+/A-、配色切换 | 白底黑字默认对不对味 |
| 6 | 蓝点 | 对着说 vs 闭嘴 | 蓝点闪动是否跟人 |
| 7 | 触屏控制条 | 点屏幕→出现→等 3.5s | 浮现/隐去是否符合"一碰就出" |
| 8 | 横竖屏 | 旋转两次 | 字幕是否保持、录音是否中断 |
| 9 | **发热续航** | 连续跑 10 分钟对话 | 机身后背温度手感、掉电 % |
| 10 | 模型对比 | 模型…→切 14M /（若已 push）ctc-zh-2025 | 各跑 1 分钟：速度/准确率/内存 |

回填数据直接批注到本文件，或微信里发我。

## 四、回填后要做的事

1. 按真机 RTF/内存定 P1 默认模型档（候选：small-ctc-2025 / ctc-zh-2025 / 14M）。
2. 若 RTF 超红线：降 chunk 到 200ms 重测、或 threads 调整、或降档模型。
3. 更新 `docs/tingmu-dev-plan-20261029.md` P0 结论 + 变更记录，进入 P1（正式 UI + 设置页 + 前台服务）。

## 附：本次踩坑记录

- GitHub release 资产域名（objects.githubusercontent）国内极不稳定：47MB AAR 重试后成功，2MB VAD 也重试成功；**模型走 hf-mirror.com**（仓库实名为 csukuangfj/…，先查 /api/models 列文件再拉）。
- `curl -o` 目标目录不存在会静默 FAIL——批量下载前先 mkdir。
- sherpa-onnx 2023-02-20 bilingual int8 模型在流式下有系统性重复字伪影，与解码方法无关，选型时直接绕开该代模型。


---

## 五、2026-10-29 自查与修复记录（模拟器端到端）

### 本轮发现并修复的缺陷

| # | 缺陷 | 证据 | 修复 |
|---|---|---|---|
| 1 | 渲染必崩：`String.format` 多参数类型错配（`%f` 收到 Long） | logcat FATAL `IllegalFormatConversionException: f != java.lang.Long` | 改用字符串模板 + 单参数格式化 |
| 2 | 引擎在 UI 线程加载模型，普通手机卡 2-5s | 代码审查 | 模型拷贝/加载/音频/解码全部移入引擎线程 |
| 3 | 快速连点「开始」会并发两个引擎 | 代码审查 | `starting` CAS 守卫 |
| 4 | 麦克风失败静默回退，用户不知情 | 截图：点开始无任何反应 | toast 区分「无权限」与「被占用」，并复位按钮状态 |
| 5 | `setContentView(root)` 整行缺失 → 全白屏 | uiautomator：content 无子 View | 补上调用 |
| 6 | `discover()` 未调用 `ensureBundled()` → 全新安装点开始只弹「没有可用模型」 | `run-as ls files/models` 不存在 | discover 内先 ensureBundled |
| 7 | 待机容器 idlePanel 铺满且在最上层，吞掉控制条点击 | logcat 无 SHOWBAR / 点配色无效 | 控制条 bringToFront |
| 8 | 占位符「…」在 54sp 粗体下渲染成三个黑点，误认为脏点 | 截图 | 空字幕不画占位符 |
| 9 | 深色主题下提示文字仍用深灰，几乎不可读 | 截图 | 按背景明暗切换文字色 |
| 10 | 停会后字幕残留上一次内容 | 截图 | 停止时清空 finalized |
| 11 | stats 浮层与首行大字重叠 | 截图 | 加半透明白底 + 圆角 |

### 验证通过项（截图存 notes/shots/）

- 待机页：白底 + 大蓝按钮「开始识别」+ 提示 + 自测入口（23-idle.png）
- 运行态：全屏大字字幕 + 左上蓝点 + 底部红色「■ 停止」+ 计时 + stats（25-midrun.png）
- 停止复位：字幕清空、回到待机（26-after-stop.png）
- 触屏唤出控制条：模型… / A- / A+ / 配色（30-bar.png）
- 配色切换：黑底白字，提示文字自动转浅灰（34/36 系列）
- 自测端到端：`SELFTEST_DONE rtf=0.09~0.17 text=各位好我们今天来测试一下听目的实十字幕功能…`（模拟器 x86_64，与 Mac 基准同量级）

### 当前限制（诚实口径）

- 模拟器无真实麦克风：麦克风路径只验证到「初始化失败提示 + 状态复位」，**真实拾音未验证，需真机**。
- 模拟器 x86_64 的 RTF（0.09-0.17）不能直接换算手机；真机数据仍待回填。
- 同音字错（幕→目、障→账）依旧存在，属小模型共性，P2 用 HomophoneReplacer 处理。

### 最终包

- 手机版：`tingmu-p0-v3.apk`（93MB，arm64-v8a + armeabi-v7a，0.3-p0）
- 模拟器版：`tingmu-emulator.apk`（含 x86_64）


---

## 六、2026-10-29 晚间第二轮自查（用户远程下载需求触发）

### 新发现并修复（v0.4-p0，versionCode 4）

| # | 缺陷 | 修复 |
|---|---|---|
| 12 | **蓝点在麦克风模式下永远不亮**：重构时 MicSource.level() 写死返回 0，VAD 电平没有进识别循环（wav 自测路径有电平所以模拟器看不出来） | 电平计算统一移入 processChunk：有 VAD 用 512 窗概率，无 VAD 用 RMS，麦克风/wav 两路都生效 |
| 13 | **"加载模型中"按停止被无视**：线程加载完无条件 running=true 继续跑 | 增加 cancelFlag；加载完成后与主循环双检查；stopEngine 置位并 join 3s |
| 14 | stats 浮层与首行大字叠字看不清 | 改为不透明主题色圆角底 |
| 15 | gradle 里无效的 ignoreAssetsPattern 死配置 | 移除；lite 版改为构建期移出 14M 模型目录的确定性做法 |
| 16 | 双击开始时 CAS 失败静默返回 | 加 toast 提示"上一次操作还在处理" |

### v4 回归验证（模拟器）

- 自测端到端 RTF 0.09~0.37（冷启动波动），文本全文一致，零崩溃
- **蓝点随语音亮起**（42-v4-running.png）
- 横屏自适应无崩溃（41-v4-landscape.png）
- aapt 确认 versionName=0.4-p0，full 包含 arm64-v8a+armeabi-v7a 双架构与 14M 模型

### 发布物（全部为 0.4-p0 同源构建）

| 文件 | 体积 | 内容 | 位置 |
|---|---|---|---|
| tingmu-p0-v4.apk | 93MB | arm64+v7a、双模型、VAD | Mac `~/ZCodeProject/tingmu/` + 111 `~/tingmu-dl/` |
| tingmu-p0-lite.apk | 51MB | 仅 arm64、仅 small-CTC 模型（低带宽远程下载用） | 同上 |
| 历史链接名（tingmu-p0.apk / v2 / v3） | — | 已全部替换为 v4 内容，旧链接不会装到旧包 | Mac 本地 8765 / gp.18.do |

### 公网下载（Cloudflare 隧道路径路由，未动 DNS）

- `https://gp.18.do/dl/tingmu-p0-v4.apk`（完整版）
- `https://gp.18.do/dl/tingmu-p0-lite.apk`（lite，51MB）
- 实现：111 nginx 127.0.0.1:8091 静态站 + cloudflared 对 gp.18.do 加 `^/dl/.*` 路径规则（配置备份 config.yml.bak.*；回滚=删规则重启 cloudflared + 删 sites-enabled/tingmu-dl）
- **已知瓶颈：111 与办公室同一出口，实测 19~294KB/s 波动，74MB 约 40-70 分钟**；支持 Range 断点续传；回办公室走局域网 `http://192.168.0.166:8765/tingmu-p0-v4.apk` 秒下
- 附带发现：办公室出口对国内外 CDN 都只有 1.5~50KB/s（晚间），属线路问题非配置问题

### 仍未验证（等真机）

1. 真实麦克风拾音（模拟器无麦克风硬件）
2. 真机 RTF / 内存 / 发热 / 续航
3. 32 位老手机（full 包含 v7a，未实测）
4. T0.3 竞品拆解（Get笔记/Live Transcribe/音书）未做


---

## 八、真机首轮反馈与修复（2026-10-30，v0.5-p0 / versionCode 5）

用户真机安装 0.4 后反馈四点，全部处理：

| # | 用户反馈 | 根因 | 修复 |
|---|---|---|---|
| 1 | 停止按钮太大 | 132×56dp 长条 | **52dp 小圆钮**，收进触屏浮出的控制条（■红/模型/A-/A+/配色，底部居中） |
| 2 | **停止按不动、停不下** | **两个叠加根因**：①stopEngine 在主线程 join 3s 造成卡死感；②`starting` 标志运行期恒 true，toggleRun 一进来就被 busy 吞掉——所有点击静默失效 | ①停止改异步（只发信号不等待）；②加载完成即清 starting + 400ms 双击防抖；③触屏监听三重兜底（模拟器实测：tap 停止→立即回待机） |
| 3 | 设置入口找不到 | 触屏浮现的控制条太隐蔽且无停止 | 点屏幕→底部中央浮现 5 圆钮；待机页提示文案即说明 |
| 4 | 运行态不应有大按钮和计时器 | 旧版常驻运行条+计时器 | **运行态=纯字幕+左上蓝点**；计时器删除；stats 数据行也改为点屏幕才出现（P1 正式版默认移除） |

### v0.5 模拟器全链路 tap 回归（关键区别：这次全部用真 tap，不再用 intent 捷径）

待机（大按钮）→ tap 开始 → 加载 → 纯字幕运行态（无大按钮/无计时器，蓝点随音亮）→ tap 屏幕 → 5 圆钮浮现 → **tap ■ → 立即停止回待机** ✓；横屏自适应 ✓；配色切换 ✓。截图 notes/shots/50-57。

### 发布物（全部 0.5-p0 同源）

- 全量 93MB（双模型+双架构）/ lite 51MB（arm64+单模型）
- Mac 本地 `~/ZCodeProject/tingmu/tingmu-p0-v5.apk`（launchd 常驻服务 8765，v2/v3/v4 旧链接文件已全部替换为 0.5 内容）
- 111 `~/tingmu-dl/`（v5 + lite；8091 已对局域网开放，实测 105MB/s；nginx reload 冲突坑：旧 worker 占用 127.0.0.1:8091 致 bind fail，需 restart 而非 reload）
- 公网 `https://gp.18.do/dl/tingmu-p0-v5.apk`（办公室出口 19~294KB/s，仅限离开局域网时用）


---

## 九、v0.6-p0（2026-10-30，用户逻辑反馈：设置必须先行）

用户指出：字号/配色/模型应该**在开始识别之前就能设置**，而不是进识别后才发现入口。

修复（versionCode 6 / 0.6-p0）：
1. **首屏新增明面入口「设置（字号 / 配色 / 模型）」**（自测链接下方，蓝字），点击即浮现控制条；
2. 控制条从字幕层提升到根层——待机/运行两态共用，不再有"待机点了没反应"的死角；
3. 待机态控制条只显示 ？/A-/A+/配 四钮（**无停止钮**——首屏开始由大按钮负责，避免红■在待机时误启）；运行态五钮含 ■停止；
4. applyTheme 在启动时即应用（此前只在点配色时才应用，存过的主题重启不生效）。

模拟器回归：首屏设置链接可见可点、控制条待机可用、配色即改即存、进程稳定。
（插曲：误将 arm-only 手机包装上 x86_64 模拟器，onnxruntime 在 ARM 转译层 dlopen 崩溃——**模拟器验证必须用含 x86_64 的 emulator 包**，真机不受影响。）

发布物：tingmu-p0-v6.apk（93MB 全量）/ tingmu-p0-lite.apk（51MB），Mac+111+gp.18.do 全部链接文件（含 v3/v4/v5 旧名）已同步为 0.6 内容。


---

## 十、v0.7-p0（2026-10-30，用户反馈：菜单要点很多下才出来）

根因：运行态触摸落在 ScrollView 上，手指的微小移动会被判成"滚动"而非"点击"，captionText 又在自动滚动中放大了位移——所以单击常被吞，要点很多下才碰中一次。

修复：**改为触摸即触发**——captionScroll/captionText/captionScreen/idlePanel 四层全部挂 OnTouchListener，`ACTION_DOWN` 一到立即 showBar()，`return false` 不消费事件（滚动等原生行为不变）。效果：手指落屏第一毫秒菜单就出来，且持续触摸时菜单保持不消失。

模拟器回归：字幕流中**单次 tap** → SHOWBAR 立即触发、五圆钮+stats 即时浮现（70-v7-instant-reveal.png）。

发布：tingmu-p0-v7.apk（93MB）/ lite（51MB），versionCode 7 / 0.7-p0；Mac+111+gp.18.do 全部链接（含历史名字）同步为 0.7。


---

## 十一、外部种子测试启动（2026-10-30）

- 用户将 `听幕-0.7-测试版.apk`（93MB 全量版）发往听障朋友实测（~/Downloads/ 有副本）。
- 这是第一批**真实目标用户**数据点；反馈按第八节同口径收集：机型/系统版本、拾音出字、定格延迟、右上角 RTF 与 native 读数、发热、错字感受、崩溃场合。
- 待回填：外部 testers 反馈 + 用户本人真机数据 → P0 收尾 → 定 P1 默认模型档 → 进 P1。

---

## 十二、「顶部一行参数还在」疑云排查（2026-10-30，外部测试期间）

用户报：自己测 v0.7 没看到顶部参数行，但朋友测试时仍看到该行。结论：**不是 bug，是两件事叠加**，已排查闭环。

**事实 1：v0.7 该行本来就是瞬态的。** 代码位置 MainActivity.kt：
- `statsText` 默认 `View.GONE`（:125），内容为 `avg/max/RTF/native/模型名`（:506-510）
- 位置 `Gravity.TOP or Gravity.END`（右上角，顶部留 42dp 让开蓝点，:126-128）
- 仅在 `showBar()` 里随小圆钮一起置 VISIBLE（:254），`hideBar` 8 秒后自动 GONE（:246-249）
- 运行态 `setPhase("running")` 强制 GONE（:266）

即：**点屏幕 → 菜单和参数行一起浮现 → 8 秒后一起消失**。用户自己没看到，是测试时没在那一刻点屏幕；朋友看到，多半是在点屏幕找菜单的瞬间被看到。**这是设计行为，不是缺陷。**

**事实 2：分发目录里确实混着旧构建，且"文件名比内容新"。** 排查 `versionCode` 后发现：
- 111 服务器 `/home/yangyan/tingmu-dl/tingmu-p0-v3.apk` 内容实为 **versionCode 4（0.4-p0）**，sha `cfe63f...`，而 v7 是 `28ccbe...`。**0.4 时代 stats 参数行是常驻显示的**（v0.5 才改为点屏幕才出现，见第八节第 4 条）——若有人从旧链接下载，看到的就是常驻参数行。
- 本地另有 `tingmu-emulator.apk`(vc6)、`tingmu-p0-v3-lite.apk`(vc5)、`听幕-p0-debug.apk`(vc1)、`听幕-p0-v2.apk`(vc2) 同样是旧内容。
- 根因：**历史迭代为省事一直是"覆盖同名文件"发布**，导致文件名（v3/v4/v5/v6）与实际内容脱钩；构建号与文件名没有绑定校验。

**处置（可回退，未删除任何文件）：**
- 本地 `tingmu/` 旧构建 → `tingmu/old-builds/`；111 `/home/yangyan/tingmu-dl/old-builds/`
- 顶层与服务器根目录现只保留 versionCode 7 的包（全量 93MB + lite 51MB）
- 复核：111 上 v4/v5/v6/v7 sha 全等 `28ccbe...`；`https://gp.18.do/dl/tingmu-p0-v7.apk` 返回 200 / content-length 97218935 / content-type application/vnd.android.package-archive

**流程改进（待执行）：** 后续每次出包必须核对 `aapt2 dump badging` 的 versionCode 与文件名一致；发布前清点分发目录，发现内容旧于名字即下线。

---

## 十三、v0.8-p0（2026-11-02，用户建议：说话中断处留空格，不加标点）

**动机**：用户反馈字幕"全部连到一块"；希望说话中间有断时留一个空（不加标点），换人说话时也更清楚。

**根因**：旧代码端点分隔符是 '\n'，且完全依赖模型自身端点（rule2=1.4s trailing-blank）。实测 sherpa-onnx 端点按 CTC trailing-blank 计静音——麦克风底噪下会漏断；且 1.4s 阈值对日常停顿太钝。

**改动**（`MainActivity.kt` processChunk + commitSegment/sepFor）：
- 用已有的 Silero VAD 驱动**软断点**：lvl>0.4 计为有声；无声持续 **>600ms** 且当前有文字 → 把当前段定格进 finalized，末尾留 **' ' 空格**，`rec.reset(st)` 开新段
- 停顿继续超过 **2.5s** → 把末尾空格升级为 '\n'（长停顿/换人说话=段落换行），只升级一次
- 模型自身端点保留作兜底（rule 不变），提交时同样走 sepFor(gapMs)
- `commitSegment` 加了 `SEG_COMMIT` 日志便于验证

**实测证据**：
- 阈值实验（A 说话 + 人为停顿 + B 说话，trim 过）：sherpa 端点在 0.7s 阈值下会把 B 从中间切开（误伤），VAD 软断点在 0.6s 正常触发、不会切进连续语音内部
- 模拟器真包验证：注入 1.5s 停顿的自测音频 → logcat `SEG_COMMIT sep=space text=对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢`，随后 B 正常滚动出字；普通连续自测 `SELFTEST_DONE rtf=0.18~0.24` 绿
- 打包：`tingmu-p0-v8.apk`（全量 97218935B，arm64+v7a，双模型，sha256 前16 `0b3e9aa71f67eb08`）/ `tingmu-p0-v8-lite.apk`（arm64 单模型，sha `428881def958c540`）；`aapt2 dump badging` 均 versionCode=8 / 0.8-p0（出包必核对 versionCode，§十二 教训）
- **分发链接本轮未切换**（仍是 v0.7 三路），待用户确认后再用 v8 新文件名发布、不覆盖旧名

**口径**：断口分隔是"空格/换行"，不是标点；多人**同时**说话仍会串（见 dialect-and-speaker-investigation-20261030.md §六），轮流说话时断口天然分开

**真机反馈（2026-11-02，用户本人手机装 v0.8 覆盖安装）**："整体上感觉比上一个版本要强很多"——v0.7→v0.8 唯一行为变化就是断口空格/长停顿换行，实测有效。待补量化：机型/系统、stats 行 RTF、发热、错字、断口疏密主观感受。

---

## 十四、v0.9-p0（2026-11-02，用户报：对话中英文/字母显示为 UNK）

**根因实证**： 现用 `small-ctc-zh-2025` 词表 1000 token， **0 个拉丁字母 token**，`<unk>`=id 2。`say` 合成英文音频实测输出全 `<unk>`（"天乐<unk> <unk>…"），中英混合音频里英文段全 UNK、中文正常。

**v0.9 改动**：
1. **UNK 不再显示**： `cleanAsrText()` 在取结果处剥 `<unk>` 并压缩多余空格（MainActivity）。模拟器注入中英混合 wav 验证： `SEG_COMMIT sep=space text=天乐 我们明天下午三点在医院门口见面`——无 UNK（英文仍被丢，待双语包）
2. **外挂双语模型支持**： `buildRecognizer` 检测到目录带 `bpe.model` → `modelType="zipformer2"` + `bpeVocab`（AAR 2.0.1 的 OnlineModelConfig 有这两个字段，javap 实证）。sideload 后模型列表自动出现（id=目录名+`-td`）；自测加 `--es model <id>` 指定模型
3. versionCode 9 / 0.9-p0；出包 `tingmu-p0-v9.apk`（全量 97218935B sha `7d5f6d31ea6cdae5`）/ `tingmu-p0-v9-lite.apk`（51MB），aapt2 核对 vc=9；`~/Downloads/听幕-0.9-测试版.apk` 已同步

**双语包选型实测**（同一批合成音频 + 官方 test_wavs）：
- 老 `zipformer-bilingual-int8`（189MB，2023-02-20）： 英文能认（"HELLO…ABC COMPANY"）但流式重复字伪影确认（"明明天""见面面"）、中英混合串字——**不用**，与旧记录一致
- **`sherpa-onnx-x-asr-480ms-...-zh-en-punct-int8-2026-06-05`（162MB）**： en1 "Hello, this is a test of the A B C company meeting at three"、en2 "Please send the PDF file to my iPhone"、中文正常、中英混说一句话 "昨天是 Monday，today is 礼拜二" 全对，**无重复伪影，自带标点**。模拟器 selftest 全链路 MODEL_LOADED 成功、输出带标点、RTF 0.57（模拟器 x86；真机待测）
- 结论： 双语包 = x-asr-480ms， 走"可选下载/外挂"（162MB 不塞 APK），和方言包同一机制；下载按钮待拍板。**注意**： 它自带标点，与 v0.8"留空格不加标点"偏好冲突——如坚持，可在显示层剥标点
- en-kroko-2025-08-06（57MB 英文专用）备选， 不如双语包一站式

**换模型权衡实测（2026-11-02，Mac 同条件）**：

| 指标 | small-ctc 25MB（现用默认） | x-asr-480ms 162MB（双语） |
|---|---|---|
| RTF_cpu（Mac） | **0.037** | 0.100（约慢 2.7 倍） |
| RTF（模拟器 x86） | 0.17 | 0.57（约慢 3.3 倍） |
| 中文 CER 去标点（18.3s 自然语音） | **4.2%** | 5.6%（持平略差， 且自带标点） |
| 中文 CER（TTS 合成 6.8s） | 6.7% | 26.7%（尾部 "门口见面" 被端点截掉——合成音假象？需真人语音复核） |
| 英文 | 全 `<unk>` | **基本全对**（ABC/PDF/iPhone/句子） |
| 体积打进 APK | — | 全量版双模型都留 ≈ 250MB； 只留双语： 全量 ≈ 206MB / lite(arm64) ≈ **170MB** |
| 其它 | 省电省内存， 32 位老机友好 | 出字延迟略高（480ms chunk)， 发热/续航待真机； 公网 gp.18.do 出口 19-294KB/s， 200MB 要 12min~3h， 分发优先局域网/微信 |

**结论**： 速度慢 2.5-3 倍但仍远低于实时（RTF≪1)， 换行； 准确率中文持平、英文从不可用到可用； 体积用户拍板可接受。

---

## 十五、v1.0-p0 双版本打包（2026-11-02，用户拍板：按模型分两个版本， 不再按 ABI 分全量/lite）

**用户决策**： 双语版（x-asr 双语模型， 英文+标点， 慢一点/大一点， arm64 专属 ~165MB）+ 快速版（small-ctc 25MB， 快/省电/凉快， 双架构含 32 位老机， 无英文无标点）。每个版本只内置一个模型。有标点用户拍板没问题。

**实现**： `scripts/build-apk.sh bilingual|fast`——构建期把选定模型目录拷入 assets、构建后 trap 恢复； 双语版只拷贝模型本体 5 个文件（encoder.int8/decoder/joiner/tokens/bpe.model)。versionCode 10 / 1.0-p0（两版同号， 包名相同， 互斥安装）。

**踩坑+修复**：
1. x-asr 原目录带 `test_wavs/` 子目录 → assets 打包后 `ensureBundled` 用 `assets.open()` 打开目录炸 `FileNotFoundException` 崩溃。修复两处： build 脚本只拷贝模型文件； `ensureBundled` 过滤子目录（本机 AssetManager 对文件 list 返回空/null、对有内容目录返回非空——**注意不是"文件返回 null"， 实测文件返回空数组**， 第一版过滤写反导致 all= 空）
2. `adb install -r` 不清 filesDir → 旧模型残留混入 discover； 模拟器验证前必须 `pm clear`

**验证**： 模拟器装双语版 pm clear 后 selftest： `all=x-asr-...-td` 唯一模型、MODEL_LOADED 成功、输出带标点、RTF 0.55(x86 模拟器）； 快速版： `all=small-ctc-zh-2025`、RTF 0.14、无标点无 unk。出包 aapt2 核对 vc=10：
- [听幕-双语版.apk](../../tingmu/听幕-双语版.apk) 165251729B arm64-only 只含 x-asr， sha `f73f2dc3a5a5923d`
- [听幕-快速版.apk](../../tingmu/听幕-快速版.apk) 76075107B arm64+v7a 只含 small-ctc， sha `c17975e78d971c97`
- 均已 cp 到 ~/Downloads/； 三路下载链接仍待用户拍板后切换

**独立共存版（用户拍板：两个版本要能在同一手机同时安装、第一屏要显示版本名）**：
- 原理： 不同 `applicationId` 即可共存（同一签名即可， 不需要多个签名）——双语版 `com.zcode.tingmu.bilingual`、快速版 `com.zcode.tingmu.fast`， 桌面图标名 `听幕双语版`/`听幕快速版`（manifest label 改 `@string/app_name`， gradle 按 `-Pvariant` 注入 resValue）
- 第一屏 idle 顶部新增粗体标题 `听幕双语版  v1.0-p0`（titleText， 跟随主题色）； 模拟器实证： 两包同装 `pm list packages` 双现、权限弹窗显示"听幕双语版"、首屏标题正确
- 重出包： 双语版 165252253B sha `4dac4fd32ce489a2`（arm64, vc10, label 听幕双语版） / 快速版 76075623B sha `c63d134a5aa3e34f`（arm64+v7a, vc10, label 听幕快速版）， 已覆盖 ~/Downloads 同名文件
- 注意： 包名变了 → 外挂模型目录路径变为 `Android/data/com.zcode.tingmu.{bilingual,fast}/files/models/`； 旧 com.zcode.tingmu 的数据不会自动迁移（两版各自独立数据， 正是共存的语义）

---

## 十六、v1.0 正式命名 + logo（2026-11-02，用户拍板）

- **版本号**： 两版 versionName 统一 **"1.0"**（versionCode 仍 10， 避免已装 vc10 的测试机降级装不上）
- **logo**： `scripts/gen_icon.py <variant> <resDir>` 用 PIL 程序化绘制——蓝底(#0A84FF 与 App 主按钮同色）圆角方块 + 白色对话气泡 + 三条蓝色字幕行 + 右下角徽章字（**快速版="快"、双语版="双"**）， 生成 legacy 五密度 PNG + anydpi-v26 自适应 icon（前景透明、徽章置于安全区内）。manifest 加 `android:icon/roundIcon=@mipmap/ic_launcher(_round)`。
- build-apk.sh 构建时按 variant 重生成图标、 trap 恢复默认（res 常驻快速版图标， 保证裸 gradle 构建也能过）
- 像素级验证： 气泡中心白、徽章区两版 icon 字节不同（双语 `656525855cbc` / 快速 `b451041b87a0`)
- 重出包（aapt2 核对 vc10 / v1.0 / label / icon 均在内）： 双语版 165313342B sha `997109173b69f0ef`（arm64)； 快速版 76135616B sha `ec7fd21dade85d76`（arm64+v7a)； 均已 cp ~/Downloads/
- **下一步（用户拍板）**： 上传 GitHub

---

## 十七、GitHub 发布（2026-11-02，用户拍板：公开仓库 + Releases 带 APK）

- 仓库 **https://github.com/huiguyy/tingmu**（public，MIT LICENSE 代码；模型/AAR 许可各见上游）
- 推送内容（44 文件， 97MB)： 源码 + res 图标 + AAR + vad + 构建脚本 + notes/docs + README； **排除 models/(629MB)、upstream/、old-builds/、*.apk、日志截图**（.gitignore 已写）
- 新增 `scripts/fetch-models.sh`（从 sherpa-onnx 官方 release 拉 small-ctc-2025 / x-asr-480ms / silero_vad）； build-apk.sh 改为模型从 `models/` 来并自动 fetch
- Release **v1.0** 挂两个 APK： `听幕-双语版.apk`(165313342B) / `听幕-快速版.apk`(76135616B)，下载品链接 `https://github.com/huiguyy/tingmu/releases/download/v1.0/听幕-双语版.apk` 等
- 安全： 绝未推整个 ZCodeProject 工作区（内含交易网关/cookie 等敏感内容）， 只建了独立干净仓库

**icon v5 + v1.1（2026-11-03，用户仍报"老logo"，指出"删掉重装不存在缓存"——技术上正确）**： 承认两点可能：①手机上打开的仍是旧文件 ②v4 白底蓝泡与 v1 蓝底白泡外形相似易误判。v5 彻底换配色：**深黑蓝底(#111827) + 蓝字幕泡 + 白杠 + 黄徽章黑字（快/双）**——48px 实测黑蓝68%/蓝泡15%/黄章4%，与 v1-v4 任何版本一眼可辨。版本升 **v1.1/vc11**，文件名自动带版本：`听幕-双语版-v1.1.apk` 165311039B sha `5eb943217ad98935` / `听幕-快速版-v1.1.apk` 76313928B sha `4b456fda83b2e7d5`；APK 内 icon 与设计稿 sha 一致(2d67ff2c…）；Downloads 只留 v1.1 两件+v5预览；GitHub 新建 Release v1.1；判据：**安装界面显示黑底黄徽章 = 新文件；蓝白泡 = 旧文件**。

**icon v4.1 端到端复验（2026-11-02 晚）**： 模拟器装同源 x86_64 包 → 打开系统"应用信息"页抓系统渲染的大图标：**白底 48% + 品牌蓝 #0A6CFF 14% + 右下徽章区检出蓝圆(10,108,255)**，与预览一致（差异仅因截图裁切含页面底色）。确认：APK 纯 PNG 图标 + Android 系统渲染正确。手机侧若仍显旧图标 = 启动器缓存/旧包残留，需先卸载旧应用再装、必要时重启。

**icon v4.1 纯 PNG（同一天，用户反馈"换了但和预览不一样"）**： 根因 = 自适应 icon 被启动器裁剪/缩放，预览（整块方图）≠ 手机渲染。修复： **删除 anydpi-v26 自适应 icon，只保留各密度整块 PNG**，manifest 只留 android:icon=@mipmap/ic_launcher。此后预览图直接取自 APK 内 icon，二者必然一致。重建 18:32： 双语 165297479B sha `091d98ddb58ce240` / 快速 76300404B sha `92d53085b58473b4`； APK 内无任何 anydpi 条目、icon sha 与 res 一致； Downloads 只留新三件； GitHub release 已 clobber、源码已 push。

**icon v4 清场重建（同一天，用户要求"删掉旧的、只留新的、重新做"）**： 全删旧 apk/旧 res icon → gen_icon.py v4 **反色设计**（白底圆角方块 + 蓝色字幕气泡 + 泡内两条白粗杠 + 右下蓝徽章白字 快/双，与 v1/v3 蓝底白标一眼可辨）；48px 验证白底72%+蓝泡20%。重建： 双语 `听幕-双语版-v1.0.apk` 165310428B sha `220c33ba428723e0` / 快速 76312581B sha `5c6376594b9dcb9e`； APK 内 icon 与设计稿 sha 一致(7cc49b46…）； Downloads 只留新文件+预览； GitHub release 已 clobber、源码已 push。

**icon v3 重设计（同一天，用户反馈 v2 两个字看不清）**： v2 文字型 logo 在 48px 下必糊（本模型无法看图，只做了像素统计，未把住"可辨"关）⇒ v3 改为**纯几何**：白色字幕框（圆角矩形+尾巴）+ 框内两条 10% 粗蓝杠 + 右下快/双徽章。48px 逐行验证：`WWW?BBBB…BBB WWW` 杠在框内清晰可辨。重建：双语 sha `46e3be98112b399b`(165312848B) / 快速 `d7200816f1363136`(76314677B)；预览 `~/Downloads/听幕-logo预览-v3.png`（v2 预览已删）；源码 push + release v1.0 附件已更新。

**icon v2 重设计（同一天，用户拍板"logo 不行重新设计"）**： v1 气泡细线小尺寸糊 → v2 改为 **蓝底(#0A6CFF) + 中央白色粗体「听幕」+ 右下角徽章圆圈(快/双）**。理由：粗字小尺寸辨识度高；白字对蓝底 WCAG 对比度 4.56:1（v1 的 #0A84FF 只有 3.6:1，听障友好性不够，故加深）。monochrome 剪影层保留（主题图标）。像素验证：legacy 77%蓝+19%白字，mono 20%白色剪影；模拟器抽屉实测新蓝块内白字像素 1000+/格。重建：双语 sha `76af4cad460cd612`(165327328B) / 快速 `3d537f779fe5bd60`(76329093B)，已 cp ~/Downloads/、push 源码(06d22d7)、release v1.0 附件已 clobber 更新。

**icon 补丁（同一天，用户真机报"logo 是空白图片"）**： APK 内 icon 资源像素验证其实非空（前景 432px 100% 不透明、78% 蓝），模拟器抽屉渲染也有蓝+白气泡； 真机空白最可能原因 = Android 13+ 主题图标（themed icons) 下 adaptive icon 缺 `<monochrome>` 层，前景整片不透明被压成纯色块，气泡看不见。修复： gen_icon.py 新增 monochrome 白色剪影层（气泡+徽章， 25% 覆盖率实测）并写入 adaptive XML； 重建两包（双语 sha `e17b6df99b2ac64f` / 快速 `d541444cef23497f`)、已 cp ~/Downloads/ 并 `gh release upload --clobber` 更新 v1.0 附件、源码已 push。另一种可能是用户看的是文件管理器里 APK 文件的空白缩略图（正常现象），安装后桌面图标才是真 logo。

---

## 十八、真机长期测试反馈（2026-11-03，用户本人）

- **双语版（v1.5 前身 v1.4/vc14 同模型）长时间使用：无明显发热**——此前的核心担忧（162MB 大模型在手机上持续推理是否烫）解除；RTF 未逐点记录但体感流畅。
- **识别质量**：双语版准确率明显高于快速版；**语速快时双语版基本不丢词，快速版丢词明显**。
- 结论：**P1 默认模型档 = 双语版**（质量优先），快速版保留为老机/省电档。此结论已写进 README 与 Release v1.4/v1.5 说明。
- 用户口径："先这么用着吧"——v1.5 图标定稿，进入稳定使用阶段。

---

## 十九、v1.5 方言版（SenseVoice 离线整句引擎，2026-11-03）

用户拍板："那就选 SenseVoice，再做个 1.5 版本的方言版吧。"（第三个版本，独立包名可同机共存）

**实现（为什么是"整句出字"）**：sherpa-onnx 里所有方言模型都是**离线模型**（SenseVoice / paraformer 系），没有流式接口可用。因此方言版 = **Silero VAD 切句 + OfflineRecognizer 整句解码**：
- VAD 512 采样窗逐窗 `vad.compute()` 取电平；连续静音 ≥600ms 判一句结束并 flush（与流式版 0.6s 软断点同阈值），单句超 12s 强制 flush 防字幕无限不出
- 每段在单线程 executor 上整句 `acceptWaveform + decode`，识别在后台跑、不阻塞录音
- `AsrEngine.classify`：目录名含 `sense` → `ModelEntry(isOffline=true)`；`buildOfflineRecognizer` 用 `OfflineSenseVoiceModelConfig(language="zh", useInverseTextNormalization=true)`，tokenizer 用 `tokens.txt`（不需要 bpe.model）

**模拟器端到端自测（x86_64 包，2026-11-03）**：
```
MODEL_LOADED sense-voice-int8-off vad=true
SEG_COMMIT sep=space text=好我们今天来测试一下听目的实时字幕功能把语音变成大字显示在屏幕上现在的医院挂号银行办事政务服务
SEG_COMMIT sep=space text=窗口对厅长朋友来说都是难关希望这个工具能帮上忙
SELFTEST_DONE rtf=0.2356 text=...（同上，含"听目/厅长"两处错字，句首"各位"被 VAD 起点切掉）
```
- 18.3s 连续语音被切成 2 段（12s 上限触发 + 尾部），**证明长句强制 flush 生效**
- RTF 0.2356（模拟器 x86_64，真机 arm64 预计更快）
- 已知错字与 Mac 端 SenseVoice 一致（"听幕"→"听目"、"听障"→"厅长"）：**专有名词会听错，是模型特性不是接线问题**

**构建/分发**：
- `build.gradle.kts` 三variant：`fast` / `bilingual` / `dialect`（applicationId `com.zcode.tingmu.dialect`，app_name 听幕方言版）
- `gen_icon.py` 徽章字新增「方」；`build-apk.sh dialect` → `听幕-方言版-v1.5.apk`（arm64 单架构）
- **成品：197,785,969 字节（189MB），sha256 前缀 `2b74a39af25d5431`**；aapt2 校验 package/versionCode=15/versionName=1.5/label=听幕方言版/native-code=arm64-v8a
- APK 内 icon 无 anydpi 条目（纯 PNG，8864B/xxxhdpi），徽章「方」像素与 v9 设计一致
- 分发：`~/Downloads/听幕-方言版-v1.5.apk` + GitHub Release v1.5 第三资产 `tingmu-dialect-v1.5.apk`

---

## 二十、v1.6 方言版双通道 + 标点（2026-11-03）

用户真机反馈"方言版没有标点符号"，并要求按双 pass 方案改造（"没有流式的那种感受好，准确度比前两个强太多了"）。

**双通道实现（MainActivity.runOfflineEngine）**：
- 方言包同时内置 `small-ctc`（25MB 流式）与 `SenseVoice`（237MB 离线）+ VAD + 标点模型
- 每个音频块同时喂 VAD（切句）与流式识别器（实时出字，`partial` 半透明显示）
- 静音 ≥600ms / 单句 >12s 判句结束：立刻把第一遍流式文本 `commitSegment` 上屏（段 id 记录），同时把该段音频丢给单线程 executor 跑 SenseVoice 整句解码
- 第二遍结果回来后 `replaceSegment(id, text)` **就地替换同一段**（字幕"跳一下"变准），不新增行
- 字幕存储从 `StringBuilder` 改为 `ArrayList<Seg(id, text, sep)>`：支持按 id 回填、按段丢弃老内容（>4000 字）、长停顿把末段分隔符升级为换行

**标点**：SenseVoice 实测**不出标点**（use_itn True/False、language zh/auto 四种组合都没有），这是模型特性（FunASR 侧标点本来就是外挂模型）。因此方言版内置 `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8`（75MB），第二遍识别完 `OfflinePunctuation.addPunctuation(text)` 补 "，。"。实测每句 12~340ms（首句含预热），中英混排会插中文标点（方言场景以中文为主，可接受）。

**模拟器端到端自测（x86_64 包）**：
```
MODEL_LOADED sense-voice-int8-off vad=true dual=true punct=true
SEG_COMMIT text=各位好我们今天来测试一下听目的实十字幕功能把语音变成大字显示在屏幕上...   ← 第一遍流式（实时）
SEG_CORRECT text=好，我们今天来测试一下听目的实时字幕功能，把语音变成大字，显示在屏幕上。...  ← 第二遍 SenseVoice+标点
SEG_COMMIT text=窗口对厅朋友来说都是难关希望这个工具能帮上
SEG_CORRECT text=窗口对厅长朋友来说都是难关，希望这个工具能帮上忙。
SELFTEST_DONE rtf=0.2287
```
- 抓屏确认：第一遍文本先显示，约 1 秒后被带标点版本替换（同一行）
- 快速版回归（流式路径共用新的 segs 渲染）：`SELFTEST_DONE rtf=0.1107` 正常
- 已知：第一遍小模型会丢字/错字（"实十""厅朋友"），第二遍修正；句首"各位"在第二遍被 VAD 起点切掉（与单通道同现象）

**体积**：方言版 280,492,283B（267MB，三模型：SenseVoice 237MB + 标点 75MB + 流式 26MB）；快速版 76,327,464B；双语版 165,324,427B。三包均 vc16/v1.6、纯 PNG 图标、无 anydpi。
**分发**：~/Downloads 三件 v1.6（v1.5 已删）；GitHub Release v1.6 三资产（ASCII 名）。源码待推。

---

## 二十一、v1.6 双语版"没有可用模型"事故（2026-11-03，用户真机发现）

**现象**：用户安装 v1.6 双语版，打开提示"没有可用模型"，无法识别。

**根因**：v1.6 给方言版加标点模型后，`AsrEngine.classify` 里为了不让标点模型出现在模型列表，写了
`if (dir.name.contains("punct", ignoreCase = true)) return entries`。
但双语模型目录名是 **`x-asr-480ms-zh-en-punct-int8`**——名字里也含 `punct`，整目录被当标点模型跳过 ⇒ `discover()` 返回空列表 ⇒ 提示没有可用模型。
（APK 内 5 个 x-asr 文件确实都在，排除打包缺失；是纯代码过滤逻辑误伤。）

**修复**：改为按前缀精确匹配 `if (dir.name.startsWith("punct-ct-transformer")) return entries`。方言版标点目录名是 `punct-ct-transformer-zh-en`，仍正确排除；x-asr 不再误伤。

**验证**：
- 模拟器装修复后的双语包（x86_64）→ `SELFTEST_MODEL all=x-asr-480ms-zh-en-punct-int8-td`、`MODEL_LOADED`、`SELFTEST_DONE rtf=0.8197`（x86 慢，手机 arm64 正常），输出带标点："各位好， 我们今天来测试一下听目的实时字幕功能， 把语音变成大字显示在屏幕上。…"
- 快速版/方言版不受影响（快速版目录 small-ctc；方言版标点目录前缀匹配）
- **重建双语包：165,324,427B，sha256 前缀 `dccafae4b959524c`**，~/Downloads 与 Release v1.6 资产已替换

**教训**：按"目录名包含某词"过滤模型时必须用精确前缀；模型名本身常含功能词（punct），contains 会误伤。

---

## 二十二、P1 听幕·屏 MVP 上线（2026-11-05，家族矩阵拍板同日）

**背景**：三产品矩阵拍板（听幕不动 + 听幕·屏 + 听幕·厅，见 `docs/tingmu-family-product-plan-20261105.md`）。本节 = 听幕·屏（手机悬浮字幕 + 乘车模式）P1 开发回执。

**产品与实现**：
- 独立包 `com.zcode.tingmu.screen`（vc1 / v0.1，独立版本线），launcher=`ScreenActivity`（gradle manifestPlaceholder 按 variant 注入，听幕三版不受影响）
- 核心机制 = **双引擎一音频流**：`MicSource` 16k CHUNK=1600 同时喂 ①KWS 哨兵常驻（`KeywordSpotter`，wenetspeech mobile int8 三件套共 **4.7MB**，`kws-wenetspeech-mobile/`）②10s 环形缓冲（ArrayDeque 160k samples）。KWS 命中触发词（默认：下一站/终点站/请下车/换乘/到站/列车进站）→ 继续收集 5s → 环形缓冲整体灌待命的流式 ASR（small-ctc 26MB，按需解码）→ 站名表 contains 匹配（归一化去空格标点）→ 悬浮字幕（`TYPE_APPLICATION_OVERLAY`，NOT_TOUCHABLE 不挡视频，黑底白字圆角条，命中浮现→停留可设 10s→淡出）+ 双震；未匹配站名降级只显示触发词（4s）；同站 30s 去抖。**非站名语音一律不上屏 = 隐私白名单**。
- **触发词→音素转换**：KWS 模型 tokens 是带调拼音音素（非汉字）。`scripts/gen_kws_keywords.py` 构建期生成 keywords.txt（pypinyin→声母/带调韵母拆分，调号规则：a>o>e>i>u>ü，ui 调在 i/iu 调在 u），**官方 keywords.txt + test_keywords.txt 全部 16 词回归 16/16 一致**；同时生成 App 端 `HanziToPinyin.kt`（20425 字）+`PinyinTable.kt`（1302 音节）供运行时自定义触发词（`createStream(keywords=)`，OOV 字自动回落内置词表）。
- 新文件：`KwsEngine.kt` / `FloatingWindow.kt` / `ScreenService.kt`（前台服务 type=microphone、通知含停止 action、复用听幕 requestStop 不 join 模式）/ `ScreenActivity.kt`（首屏大开关铁律、授权引导链悬浮窗→麦克风→通知、触发词/站名表/震动/停留/字号/配色设置、selftest 按钮）；`AudioSources.wavSource()` 加 assetName 缺省参数（兼容听幕调用）；`build-apk.sh screen` 分支 + `gen_icon.py` 徽章「屏」；`fetch-models.sh` 加 KWS 模型（GitHub release tag kws-models，失败提示手动下载）。
- selftest：`am start -n com.zcode.tingmu.screen/com.zcode.tingmu.ScreenActivity --ez selftest true [--es wav audio/test_zh_16k.wav]`；asset 音频 = say(Tingting) 合成报站 "下一站，人民广场…"（7.9s，`assets/audio/station_test_16k.wav`）。

**验证（模拟器 x86_64 包）**：
- 正例：`MODEL_LOADED asr=small-ctc-zh-2025 words=6 stations=4` → `KWS_HIT keyword=下一站` → `ASR_WINDOW text=下一战人民广场请下车的乘客请提前做好准备从左侧车门下`（ASR 层"下一战"同音错不影响匹配，正是设计目标）→ `STATION_MATCH station=人民广场` → **悬浮字幕截图确认**（黑底白字圆角条叠加 UI 之上，"下一站：人民广场"大字两行，清晰可读）→ `SELFTEST_DONE rtf=0.059 hits=1 matches=1 audio=8.0s`
- 负例（18.3s 对话音频）：`hits=0 matches=0`，无 KWS_HIT、无字幕上屏 ✓
- 双引擎 RTF 0.06（模拟器 x86），比听幕单 ASR 0.11 还低——KWS 哨兵功耗假设成立

**踩坑记录**：
1. **`HanziToPinyin.<clinit> Method too large`**：20425 条字符串数组字面量全在类初始化超 JVM 64KB 方法上限 → 改分块字符串常量（5000 条/块，块尾逗号，运行时 split 解析；常量池引用不占方法字节码）
2. am start 相对类名（`.ScreenActivity`）按 applicationId 解析——实际类名基于 namespace `com.zcode.tingmu`，要用全名
3. selftest 服务 `stopSelf()`→`onDestroy`→`fw.remove()` 会在字幕显示前移除窗口 → selftest 模式 remove 延迟 15s（postDelayed 存活于主线程 looper）
4. `FONT_SIZES` 是 Theme.kt 顶层属性不是 Theme 类成员（`Theme.FONT_SIZES` 编译错）
5. 运行态收起设置区留下孤儿标签 → MVP 撤掉收起逻辑（设置区常显）
6. 空 pendingKeyword 时降级 show("") 会闪空字幕条 → 加 isNotEmpty 守卫
7. 悬浮字幕字号 54/44sp 在 1080p 两行换行 → screen 默认 fontIdx=1（36sp）+ TextView maxWidth + autoSize（26+）；两行大字幕形态接受，真机再调

**交付物（全部核对 versionCode/versionName/label/native-code）**：
- 真机包：`听幕·屏-v0.1.apk` **58,278,799B（arm64-only，KWS 4.7MB + small-ctc 26MB + 双测试音频）**，sha256 前 16 `94adba3207437fd4`；仓库根 + ~/Downloads
- 模拟器包：`/tmp/tingmu-screen-emulator.apk`（arm64+x86_64，94MB）
- 图标：蓝泡 + 黄徽章「屏」（gen_icon.py v9 同造型）

**待真机（用户侧）**：
1. 国行 ROM 悬浮窗授权路径（模拟器 appops 全通，真机入口深浅未知）
2. 真实地铁噪声下 KWS 命中率/ASR 站名率——**顺手录一段车厢报站音频跑 bench CER（零成本预验证，与医院广播同批）**
3. 功耗：40 分钟乘车实测耗电/发热
4. 字幕字号/位置/停留时长手感
5. 真实线路站名表粘贴后整链路（含换乘词触发）

**已知边界（诚实口径）**：站名表手贴（无在线线路库）；触发词转换限常用汉字（字表 20425 字，生僻字回落内置词表）；公交/地铁报站句式差异靠触发词可编辑覆盖；纯端侧无线路数据在线更新。

---

## 二十三、v1.7 全线去标点 + 2 秒换行（2026-11-05，用户拍板；与 §二十二 听幕·屏 MVP 同日，本节为主机三版）

用户要求：①双语版/方言版不要标点 ②稍微停顿留空格 ③停顿超过 2 秒换行（原 2.5s）；并问快速版现状。

**快速版原状（回答用户）**：本来就无标点（small-ctc 词表里没有标点 token）；停顿 ≥0.6s 提交一段留空格；>2.5s 升级为换行。

**改动（三版统一）**：
- `cleanAsrText` 新增标点剥离：中文标点 `[，。！？；：、（）【】《》〈〉「」『』""''…—～·]` 与英文句读 `[,.!?;:]`（数字保护，"3.14" 不拆）全部**替换成空格**再压多余空格——标点位置天然对应停顿，正好落在"停顿留空格"的产品规则上
- `sepFor`/长停顿升级阈值 2500ms → **2000ms**
- 方言版移除标点模型：`buildPunctuation`/`addPunct` 全删，APK 不再打包 punct-ct-transformer（**267MB → 209MB**），fetch-models.sh 不再下载
- 流式自测日志改为打 `cleanAsrText` 后的文本（此前 SELFTEST_DONE 打原始文本，标点剥离了日志也看不出来，造成过误判）

**模拟器自测（x86_64，三版全过）**：
```
双语: SELFTEST_DONE rtf=0.4162 text=各位好 我们今天来测试一下听目的实时字幕功能 把语音变成大字显示在屏幕上 现在的医院挂号 银行办事 政务服务窗口 对听账朋友来说都是难关 希望这个工具能   ← 标点全部变空格
方言: dual=true（punct 标志消失）SEG_CORRECT text=好我们今天来测试一下听目的实时字幕功能把语音变成大字显示在屏幕上现在的医院挂号银行办事政务服务  rtf=0.2334
快速: SELFTEST_DONE rtf=0.1638 text=各位好我们今天来测试一下听目的实十字幕功能…   ← 回归正常
```

**成品（vc17/v1.7）**：快速 76,716,535B sha `1f3fe77f5683b7a9` / 双语 165,713,502B sha `53656635fae75872` / 方言 218,794,388B sha `a5dac61c08ffb61c`（方言 APK 已无 punct 模型条目）。~/Downloads 换 v1.7（v1.6 已删）；Release v1.7 三资产。

**注**：听幕·屏（CaptionEngine）是另一条产品线，自己的 sepFor/cleanAsrText 未动（仍 2.5s、不去标点），按屏线需求另行处理。

---

## 二十三、P1.5 听幕·屏三模式补齐（2026-11-05，用户拍板"把所有模式做到位"）

**定位修正**：P1 把"报站"做成了产品全部，用户指出太窄。听幕·屏 = **手机屏幕上的透明字幕层**，三模式：
- **env 环境字幕**：麦克风全量字幕悬浮在任意 App 上（听幕悬浮窗版）——身边人说话/电视声/广播都变字幕
- **media 视频字幕**：MediaProjection + AudioPlaybackCapture 捕获手机内播放的媒体音频 → 字幕（Android 10+，抖音/B站无字幕视频实时配）
- **ride 乘车省电**：KWS 哨兵+站名表（P1 原样保留，非站名语音不上屏）

**实现**：
- `CaptionEngine.kt`（新）：自 MainActivity 抽离的识别核心（processChunk/段落机/VAD 软断点 600ms/2500ms 语义逐行保留），纯逻辑回调输出 `(tail, full, lvl)`；**MainActivity 一行未动**（听幕三版零风险）。顺带修了听幕旧版 `src.close()` 缺失（AudioRecord 泄漏）。
- `ProjectionSource.kt`（新）：`AudioRecord.Builder.setAudioPlaybackCaptureConfig`（注意方法名是 Config 不是 Configuration，且 AudioRecord 无 capture 构造）+ 48k→16k 三点平均下采样。
- `ScreenService`：三模式分支；media 模式按 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 进前台（Android 14+ 必须先前台才能 getMediaProjection）；selftest 保留字幕 15s 供截图；字幕静默 5s 淡出（selftest 15s）。
- `ScreenActivity`：三模式单选+大开关；media 走 `createScreenCaptureIntent`→onActivityResult；`ProjHolder` 静态持有 resultData。
- Manifest：`FOREGROUND_SERVICE_MEDIA_PROJECTION` + `foregroundServiceType="microphone|mediaProjection"`；v0.2/vc2。

**验证（模拟器 x86_64，Android 15）**：
- **env**：`ENGINE_LOADED vad=true`（VAD 正常！）→ 全文识别 rtf 0.13-0.20 → 悬浮字幕截图（黑底白字圆角条，28sp/30 字 2-3 行）
- **media**：`PROJECTION_IN code=-1 data=true` → 自播(AudioTrack USAGE_MEDIA 16k)+全屏捕获 → `SEG_COMMIT 的乘客请提前做好准备从左侧车门下车` → `SELFTEST_DONE rtf=0.091` → 字幕截图 ✓（自播首句"下一站人民广场"丢失属 AudioTrack/capture 启动窗口，链路本身成立）
- **ride**：正例 `KWS_HIT→STATION_MATCH 人民广场` ✓；负例 18.3s 对话 `hits=0 matches=0` 不上屏 ✓

**踩坑记录（全部实锤）**：
1. **`finally` 秒杀刚启动的异步引擎**：`runCaptionMode` 里 `eng.start()` 异步返回后函数立即结束→`runEngine` 的 finally 对还在加载的引擎调 `stop()` interrupt。首测表现只是"卡在 VAD"的假象。修：`CaptionEngine.awaitDone()`（join，服务线程专用）在 runCaptionMode 末尾等待。
2. **WavSource 非实时**：18.3s 音频瞬间灌完，VAD 600ms 静默软断点永不触发→全程 partial 整段显示、字幕条 6 行。修：selftest 按 100ms/chunk 节流（`pace`）。
3. **Intent-as-extra 丢 resultData**：`putExtra(EXTRA_PROJ_DATA, Intent)` 传到 Service 变 null（startForegroundService 路径实测）。改 `ProjHolder` 静态持有。另注意 `RESULT_OK == -1`，别把 `projCode < 0` 当无效。
4. **悬浮字幕字号/长度**：36sp/40 字→4-6 行占屏。定：默认 28sp（fontIdx=0）+ `takeLast(30)` 字。
5. 保留 VAD 看门狗（3s 超时降级 RMS）：真机 arm64 无问题，模拟器防御性保留。

**交付**：`听幕·屏-v0.2.apk` **58,295,219B（arm64-only）** sha256 前缀 `4eada2a3999cb72e`；已核对 vc2/v0.2/label/launcher；仓库根+~/Downloads。

**真机待验（用户侧）**：①MediaProjection 弹窗在国产 ROM 的形态（Android 15 有"单应用/整个屏幕"选择，**看视频场景选"整个屏幕"或选目标 App**）②抖音/快手捕获可行性（个别 App 防捕获时无音频）③环境字幕麦克风常驻的耗电/发热 ④字幕位置手感（底部 72dp）⑤后台保活（通知栏常驻+停止按钮）。

**v0.3 追加（同日，用户指定"悬浮字幕最多两行或一行滚动"）**：FloatingWindow 定 `maxLines=2` + 按屏宽/字号动态算每行容量（`(屏宽px - 104dp) / (字号px) - 1`），超长 `takeLast(cols*2)` 尾部对齐——最新说的永远可见，绝不超两行。版本 vc3/v0.3，模拟器截图确认双行形态。真机手感（字号/停留）留待用户实测调。

---

## 二十四、v1.8 断句改版：停顿换行、2 秒空行（2026-11-05，用户拍板）

用户要求：**停顿空格→换行，原换行→空一行**。三版统一：
- 停顿 ≥0.6s → `\n`（换行）
- 停顿 >2s → `\n\n`（空一行）
- 12s 长句强制切段只 `\n`（不算真实停顿，避免连续说话每 12s 吐空行）

**改版中发现并修掉两个真 bug（v1.7 的"2 秒换行"其实从未生效）**：
1. **流式路径**：端点（endpoint）触发时把 `lastVoiceMs=now` 重置，静音计时永远到不了 2s，upgrade 分支是死代码。修复=新增 `vadSilenceMs` 字段，按音频块真实累计静音、只在有声音时清零，端点不再重置。
2. **方言路径**：`sepFor(gap)` 的 gap 是"距上次 flush 的时长"（含整段说话时间），几乎必 >2s ⇒ 每段都被判成长停顿。修复=flush 用真实 `silenceMs`；断句后静音继续累计，>2s 再 `upgradeLastSep()`。

**验证（3 秒静音测试音频，fast/dialect x86_64 包）**：
```
fast:    SEG_COMMIT sep=nl text=各位好…显示在屏幕上
         SEG_BLANK 静音>2s，上一段后留空行
         抓屏：第一段 → 空一行 → 第二段（可视确认）
dialect: SEG_COMMIT sep=nl → SEG_BLANK → SEG_COMMIT sep=nl → 两条 SEG_CORRECT 正常回填，rtf=0.2184
```
- `upgradeLastSep` 改为幂等（已是空行不重复写/不重复打日志）
- 自测音频验证完已还原（assets/audio/test_zh_16k.wav 与原文件 cmp 一致）

**成品 vc18/v1.8**：快速 76,716,535B sha `5c04837f5708073c` / 双语 165,713,506B sha `0f9f7791afbd61ad` / 方言 218,794,392B sha `6029dbbb344a1779`；~/Downloads 换 v1.8；Release v1.8 三资产。
