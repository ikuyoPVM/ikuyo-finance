# ikuyo 记账（ikuyo Finance）

个人生活成本记账 App。Kotlin + Jetpack Compose + Material 3，MVVM + Repository，
Room 本地存储，DataStore 偏好，Coroutines + Flow。

当前版本：`0.6.1`（versionCode 8；阶段 0 工程基础 + 阶段 1 核心记账 MVP + 阶段 2 预算/标签/历史/首页自定义
+ 阶段 3 AI 能力接入 + 阶段 4 统计与智能分析 + 阶段 5 高级财务管理 + 阶段 6 数据管理与发布
+ 维护迭代 01 AI 能力优化与账单日期调整）

> 构建状态：已在 JDK 17 + Gradle 8.9 + compileSdk 35 下构建 Debug 与 **签名 Release**（R8）；
> `:app:testDebugUnitTest` **306 项单测全绿**，并在 Android 15（API 35, 1080×2400）模拟器上
> 逐屏跑通核心流程，完成 v1 → v2 → … → v10 十次覆盖安装迁移验证与 versionCode 6 → 7 → 8 升级验证，
> 备份/恢复、CSV 与 Excel 导出、CSV 导入、多语言、提醒均真机验证（见文末「八、验证记录」）。

截图（真机运行截图，位于 `docs/screenshots/`）：

| 首页 | 首页布局 | 趋势点日期 |
| --- | --- | --- |
| ![](docs/screenshots/01-home.png) | ![](docs/screenshots/09-home-layout.png) | ![](docs/screenshots/10-day-bills.png) |

| 账单详情 | 预算管理 | 标签管理 | 回收站批量 |
| --- | --- | --- | --- |
| ![](docs/screenshots/11-bill-detail.png) | ![](docs/screenshots/12-budget.png) | ![](docs/screenshots/13-tags.png) | ![](docs/screenshots/14-trash-batch.png) |

| AI 对话 | AI 设置 | 手动记账 |
| --- | --- | --- |
| ![](docs/screenshots/15-ai-chat.png) | ![](docs/screenshots/16-ai-settings.png) | ![](docs/screenshots/17-ai-manual.png) |

| 统计分析 | AI 消费分析 | 消费预测 |
| --- | --- | --- |
| ![](docs/screenshots/18-stats.png) | ![](docs/screenshots/19-ai-analysis.png) | ![](docs/screenshots/20-forecast.png) |

| 异常提醒 | 月度总结 |
| --- | --- |
| ![](docs/screenshots/21-anomaly.png) | ![](docs/screenshots/22-report.png) |

| 固定支出 | 确认付款 | 到期提醒 |
| --- | --- | --- |
| ![](docs/screenshots/23-fixed-list.png) | ![](docs/screenshots/24-fixed-confirm.png) | ![](docs/screenshots/25-fixed-reminder.png) |

| 本期未付 | 分期计划 | 确认某一期 |
| --- | --- | --- |
| ![](docs/screenshots/26-fixed-unpaid.png) | ![](docs/screenshots/27-installments.png) | ![](docs/screenshots/28-installment-confirm.png) |

| 债务清单 | 还款（本金不计收支） | 储蓄目标 |
| --- | --- | --- |
| ![](docs/screenshots/29-debts.png) | ![](docs/screenshots/30-debt-repay.png) | ![](docs/screenshots/31-savings.png) |

| 存入储蓄 | 汇率与币种 | 外币账单（汇率快照） |
| --- | --- | --- |
| ![](docs/screenshots/32-savings-deposit.png) | ![](docs/screenshots/33-rates.png) | ![](docs/screenshots/34-foreign-bill.png) |

---

## 一、环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 17（AGP 8.7.3 要求） |
| Android Studio | Ladybug 2024.2.1 或更新 |
| Gradle | 8.9（见 `gradle/wrapper/gradle-wrapper.properties`） |
| AGP | 8.7.3 |
| Kotlin | 2.0.21（含 Compose 编译器插件） |
| KSP | 2.0.21-1.0.28 |
| compileSdk / targetSdk | 35 |
| minSdk | 26（Android 8.0） |

## 二、如何打开与构建

1. Android Studio → **Open** → 选择本目录 `ikuyo-finance`。
2. 首次同步会下载依赖，需要网络。
3. 直接运行 `app` 配置即可；debug 包名是 `xyz.ikuyo.finance.debug`。

`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar` 已随仓库提供，无需再生成。

命令行构建（Windows PowerShell，JDK 17 必须在 `JAVA_HOME` 中；AGP 8.7.3 不接受 JDK 21+）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"   # 换成自己的 JDK 17 路径
$env:ANDROID_HOME = "D:\android-sdk"               # 或写在 local.properties 的 sdk.dir

.\gradlew.bat :app:assembleDebug      # 打 debug APK → app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:testDebugUnitTest  # 跑单元测试
.\gradlew.bat installDebug            # 装到已连接设备/模拟器
.\gradlew.bat :app:assembleRelease    # 打 Release APK（需 keystore/keystore.properties，见下）
```

Release 签名：仓库里不含密钥。自行生成后放在 `keystore/ikuyo-release.jks`，
并写 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项，
这两个路径都已在 `.gitignore` 中）。**密钥丢失就无法再发布同签名升级包，请单独备份。**
没有配置文件时 `assembleRelease` 会退回未签名产物，可用于本地验证混淆是否通过。

## 三、架构

```
app/src/main/java/xyz/ikuyo/finance/
├── core/                  Money（分为单位的金额运算）、Time、Json、FlatJson、BillSnapshot、UiEvent、
│                          NoticeKind（提示语义分级）、Csv（编解码与公式注入防护）、
│                          AppLanguage（语言偏好的同步读写）
├── domain/
│   ├── model/             Bill、BillType、BillDraft、Summary、Budget、BudgetPeriod、BillCandidate、
│   │                      BillOperation、AiConfig/AiPersona、Category、Tag、MonthlyReport
│   └── usecase/           AddBillUseCase、EditBillUseCase、UndoUseCase、FinanceCalculator、BudgetPeriods、
│                          Statistics、StatisticsCache、SpendingFacts、SpendingForecast、
│                          AnomalyDetector、MonthlySummaryBuilder
├── data/
│   ├── local/             Room：Entity / DAO / IkuyoDatabase / Migrations
│   ├── mapper/            Entity ↔ Domain 映射
│   ├── repository/        BillRepository、BudgetRepository、BudgetAlertRepository、TagRepository、
│   │                      OperationRepository、BudgetTemplateRepository、SettingsRepository、ChatRepository、
│   │                      AnomalyPreferenceRepository、MonthlyReportRepository
│   ├── backup/            BackupModel（格式与清单）、BackupCodec（序列化/校验/兼容）、BackupCrypto（PBKDF2+AES-GCM）、
│   │                      BackupRepository（单事务恢复与自检）
│   ├── export/            CsvExporter（账单/预算/操作/分类/标签）、ExcelExporter（四个工作表的 SpreadsheetML）
│   ├── import/            CsvImporter（表头识别、逐行校验、组合键查重）
│   └── ai/                AiUrls、AiErrors、AiProbe（连通性测试独立判定）、AiModelCatalog（服务商模型列表）、
│                          ApiKeyStore、AiConfigCodec、AiSettingsRepository、
│                          OpenAiClient（含 tools/tool_calls）、AiService（含 chatWithTools 工具循环）、
│                          AiTools（11 个只读工具与执行器）、AiContextBuilder、AiBillParser、SpendingAnalysis
├── notify/                DailyReminder（频道、排期、通知）、DailyReminderReceiver（到点判断当天有无账单）
├── ui/                    components/Common（卡片、金额、空态等共用组件）、notice/（统一的应用内提示条）
├── di/Graph.kt            手写依赖容器（单例，无 Hilt）
└── feature/               home / bills / budget / tags / categories / trash / detail / operations /
                           mine / ai（AI 对话 + 手动记账 + AI 设置）/ stats（统计与分析）/ reports（月报）/
                           fixed（固定支出）/ installments / debts / savings / currency（多币种与汇率）/
                           backup / export / imports
```

分层约定：UI 只读 `UiState`、只发意图；金额与统计计算集中在 `domain/usecase` 下的纯函数
（可单测、可在 `Dispatchers.Default` 上跑）；写入一律经 Repository/UseCase，
AI 不能绕过业务层直接改库。

**统计只有一个入口**：`Statistics.snapshot(...)`。统计页、AI 消费分析、消费预测、异常检测、
月度总结的数字都来自它（或它之上的 `SpendingForecast` / `AnomalyDetector` / `MonthlySummaryBuilder`），
图表与提示词不各算一套。

## 四、数据库（Room，version = 10）

| 表 | 说明 |
| --- | --- |
| `bills` | 账单主表。金额仍是「分」的整数；`deletedAt` 实现软删除；`source` 区分 MANUAL/AI；`clientKey` 唯一索引做幂等 |
| `categories` | 12 个默认分类 + 用户自定义分类（emoji、颜色、排序） |
| `budgets` | 预算规则。`scope` = TOTAL / CATEGORY / TAG / SPECIAL；含 `periodType`（MONTHLY/WEEKLY/DAILY/CUSTOM）、`carryOver`、`customStart/customEnd`、`name`、`enabled`、`note` |
| `budget_changes` | 预算金额变更历史（保留历史消费，不被删改抹掉） |
| `budget_templates` | 预算模板（金额/周期/关联分类或标签/备注） |
| `tags` / `bill_tags` | 标签与账单多对多；删标签只解关联，不动账单 |
| `bill_operations` | 账单操作历史：CREATE / UPDATE / DELETE / RESTORE / PURGE / UNDO，含前后快照 JSON 与 `undone` 标记 |
| `monthly_reports` | 月度总结。一条 = 某月的一版：`yearMonth` + `version` 唯一索引、`payloadJson`（九段内容）、`generatedAt`、`dataStart/dataEndInclusive`、`superseded` |
| `fixed_expenses` / `fixed_expense_payments` | 固定支出计划与「某一期已付」记录。计划本身不是账单，只有确认付款才经 `AddBillUseCase` 生成账单；`(fixedExpenseId, dueAt)` 唯一约束兜住重复确认 |
| `installments` / `installment_payments` | 分期计划与每期还款记录。`(installmentId, periodIndex)` 唯一，同一期只能确认一次；提前结束写 `closedEarlyAt` |
| `debts` / `debt_payments` | 债务与还款。`debt_payments` 区分 `principalMinor`（本金，只减余额不计收支）与 `extraMinor`（手续费/利息，按普通支出入账） |
| `savings_goals` / `savings_entries` | 储蓄目标与流水（存入/取出/目标间划转）。储蓄是**划转**：可用余额减、储蓄余额增，总余额不变，不计收支 |

汇率表不在 Room：存在 DataStore `ikuyo_currency_rates`（币种 → 汇率/来源/更新时间），备份时序列化为 `currencyRates` 集合，恢复时一起写回；每笔外币账单另存自己的汇率快照，所以历史账单不会因为后来改了汇率而变化。

迁移：

- `MIGRATION_1_2`：重建 `budgets`（新增周期类型、结转、自定义区间），新建 `budget_changes`。
- `MIGRATION_2_3`：`budgets` 增列 `tagId` / `name` / `enabled`（默认 1）/ `note`，新建 `budget_templates`、`bill_operations`。
- `MIGRATION_3_4`：新建 `monthly_reports` 与 `(yearMonth, version)` 唯一索引。
- `MIGRATION_4_5`：新建 `fixed_expenses`、`fixed_expense_payments`。
- `MIGRATION_5_6`：新建 `installments`、`installment_payments`（含 `(installmentId, periodIndex)` 唯一索引）。
- `MIGRATION_6_7`：新建 `debts`、`debt_payments`。
- `MIGRATION_7_8`：新建 `savings_goals`、`savings_entries`。
- `MIGRATION_8_9`：`bills` 增列 `rateSource`（默认 `MANUAL`）、`rateUpdatedAt`（默认 0），为汇率快照记录来源与时间。
- `MIGRATION_9_10`：账单日期只精确到日。把既有 `occurredAt` 截断到**当地日历日的零点**
  （`strftime(..., 'localtime', 'start of day', 'utc')`），只丢时分秒，不偏移、不清空，
  所以升级后账单仍在同一天、金额与统计口径不变。

每次迁移都在模拟器上用**覆盖安装**逐版验证（v1 的余额 ¥964.00 一路升级到 v10 后完整保留，
v9 → v10 后仍是 8 笔 / 本月支出 ¥146.70 / 总余额 ¥853.30）；
仓库里没有 instrumentation 迁移测试，迁移的正确性靠这条逐版覆盖安装的实测记录保证。
项目不使用 `fallbackToDestructiveMigration`。

月报的版本语义：重新生成只把旧版标 `superseded`，旧数字不会被后来的账单改写；
删除当前生效版本后，同月最新的一版会自动重新成为「正在查看」。

## 五、计算与统计规则（不可随意改动）

1. **金额一律用「分」的 Long 表示**，禁止 Float/Double。汇率用 `RATE_SCALE = 1_000_000` 的整数缩放，
   换算与格式化走 `Money`，避免累加误差。
2. **总余额 = 可用余额 + 储蓄余额**，两个口径都展示；储蓄不计入可用。
3. **退款抵扣支出，不计为收入**。`Bill.signedImpactMinor` / `expenseImpactMinor` 统一表达这个语义。
4. **软删除的账单不进任何统计**（余额、预算、趋势、分类 TOP、最近账单、统计页、月报），只能从回收站恢复。
5. **历史账单的汇率是快照**：入账时把 `exchangeRateScaled` 与 `cnyAmountMinor` 写死在账单上。
6. **clientKey 幂等**：AI 与手动入账都带 `clientKey`，重复提交只会落一笔。
7. **AI 不得直接改余额、预算或历史账单**，只能用候选账单表达意图，确认后经 `AddBillUseCase` 写入。
8. 预算口径：`已用 = 周期内支出 − 周期内退款`；开启结转时 `可用 = 额度 + 上期结余`；
   `每日可用 = 剩余额度 / 剩余天数`；提醒阈值 50% / 80% / 100% / 超支，每预算每周期每级只提醒一次。
9. **统计口径**（`Statistics`）：`支出` 只累加 EXPENSE，`退款` 只累加 REFUND，`净支出 = 支出 − 退款`；
   分类与标签的占比分母都是**区间净支出**；已删除账单不参与；区间严格按 `occurredAt` 半开区间计算。
10. **预算执行百分比超支不截断**（显示 120% 而不是 100%），同时给出 `超出金额`；
    `时间进度` = 已过天数 / 周期总天数，`较节奏 = 已用 − 可用额度 × 时间进度`，正数表示花得比时间快。
11. **与上期对比**：上期为 0 时返回「未知」，不把 0 当基期算成 +∞ 或 0%。
12. **预测是预测**：由本地日聚合值剔除异常日后再外推，必须标注可信度、依据与「不是承诺值」。
13. **异常检测只提醒**：判断标准全部相对个人历史（分类中位数、历史周频、历史时段分布、分类占比变化、
    预算节奏），不设全局固定金额阈值；不修改任何账单，可标记「这是正常消费」或整类忽略。
14. **月报数字来自统计服务**：九段内容的金额、占比、预测与建议都由本地统计结果生成，
    模型只被允许对已算好的数字作解释，不允许编造。
15. **计划不等于支出**：固定支出、分期计划、债务计划本身不写 `bills`、不进任何统计；
    只有用户确认「这一期已付款」才经 `AddBillUseCase` 生成一笔真实支出账单。
16. **债务本金不计收支**：还款拆成 `principalMinor`（只减少欠款与可用余额）与 `extraMinor`
    （手续费/利息，按普通支出入账），前者永不生成账单。
17. **储蓄是划转不是消费**：存入使可用余额减少、储蓄余额增加，取出相反，**总余额不变**，
    全程不进收支统计；存入金额不能超过可用余额，暂停的目标不能继续存入。
18. **汇率是快照**：外币账单落库时写入 `exchangeRateScaled`、`cnyAmountMinor`、
    `rateSource`（手动/自动/缓存）与 `rateUpdatedAt`；之后改汇率、刷新汇率都不改写历史账单。
19. **AI 不提供汇率**：模型只说币种与金额，汇率一律取本地汇率表；缺汇率时拒绝自动入账，
    只提示去「汇率与币种」设置。外币候选永远需要用户确认。
20. **自动汇率失败要明说**：刷新失败不写入任何汇率，把已有「自动」标记降级为「缓存」，
    并在汇率页与记账页提示「不是最新」，不静默沿用旧值。
21. **同一期不会重复入账**：固定支出用 `(计划, dueAt)`、分期用 `(计划, periodIndex)` 核对是否已付，
    命中即提示「这一期已经付过了」，既不重复记账也不重复推进日期。

## 六、功能

**阶段 1（记账基础）**：首页总览（总余额/可用/储蓄、今日·本周·本月支出、本月收入、净收支、分类 TOP3、
近 7 天趋势）、记支出/收入/退款、撤销上一步、账单列表（按日分组、类型筛选、四种排序）、
回收站（单条恢复/彻底删除）、预算（总额度、分类额度、每日可用）、分类管理、我的。

**阶段 2（预算与记录治理）**：预算四种维度（总预算/分类预算/标签预算/专项预算）、周期（每月/每周/每日/自定义）、
结转、启用暂停、删除二次确认、预算模板（存为模板 → 应用）、预算变更历史、超支提醒（按级别去重）、
标签系统（多标签、颜色、按标签统计与预算、删除只解关联）、账单详情与编辑、账单操作历史（逐条撤销）、
回收站批量恢复/批量彻底删除/清空、首页模块拖动排序/折叠/隐藏并可持久化、趋势图点某天看当天账单。

**阶段 3（AI 能力）**：OpenAI 兼容接口的主备模型配置与连通性测试、Keystore 加密的 API Key、
九类错误分类与重试策略、AI 对话、自然语言记账（候选账单卡片、置信度自动入账、确认/忽略/撤销）、
人设与隐私说明。

**阶段 4（统计与智能分析）**：

- **统计分析页**（我的 → 统计分析，独立入口）：日/周/月切换与自定义日期范围；
  支出、退款、收入、净支出、净收支与账单笔数；消费趋势折线（点某天进当天账单）；
  分类占比环形图（点分类进该分类账单）；分类金额排名（带较上期变化）；标签统计（点标签进该标签账单）；
  预算执行（含时间进度与较节奏）；区间明细与统计口径说明。
- **AI 消费分析**：先由本地统计算出结构化事实，再交给 AI 解释；输出固定分四段
  （事实 / 估算 / 建议 / 说明），带数据充分性与「区间已变化」提示；失败只影响这段分析，
  不影响本地统计；可重新分析。
- **消费预测**：按本月已发生账单与历史日均推算预计总支出、预计剩余、可能超支金额，
  给出可信度（高/中/低）与 5 条推算依据，明确标注「只是预测，不是已发生金额」。
- **异常提醒**：单笔金额偏高、消费频率突增、短时重复消费、分类结构变化、预算消耗快于时间、
  非常规时段消费六类；每条给触发依据与参考历史，可「这是正常消费」或「忽略这类」，重启后仍生效。
- **月度总结**：九段（收支总结 / 分类金额与占比 / 与上月对比 / 预算执行与超支 / 趋势与异常变化 /
  消费习惯观察 / 下月预测 / 下月预算建议 / 节省建议）；支持生成本月、生成上月、重新生成、删除某一版；
  历史版本保留，数字与生成时可追溯。
- **性能与隐私**：统计结果按输入缓存（账单/分类/标签/预算/区间任一变化即失效），
  聚合与预测在 `Dispatchers.Default` 上跑；AI 设置页逐项说明「会发送哪些数据」与「不会发送什么」。

**阶段 5（高级财务管理）**：

- **固定支出**（我的 → 固定支出）：房租/宽带/会员这类周期性支出登记为计划，周期支持每月、
  每周、双周、双月、每季、每半年、每年与自定义天数；列表按「已到期 / 本周 / 以后 / 已暂停」分组，
  显示到期日、剩余天数与金额。点「确认付款」才生成真实账单（备注自动写「固定支出 · <计划名> · <日期>」）
  并把计划推进到下一期；同一期重复确认会被拦下并提示「这一期已经付过了」。
  支持「本期未付」（只推进日期、不写账单）、暂停/恢复、编辑、删除。
  提醒天数可设（0/1/3/5/7/14 天），到期前按天数高亮提醒。
- **分期**（我的 → 分期）：总额、本金、手续费、利息、期数（1–60）、首期金额、每期金额自动联动，
  到期日按 `anchorDay` 逐月推算（31 号遇小月回退，不漂移）。列表显示进度条、
  已还/剩余金额与期数、「下一期 x/y · 日期 · 金额」；确认某一期才生成账单，
  提前结束可一次性结清（可选把剩余手续费记为一笔支出）。
- **债务**（我的 → 债务）：消费信贷、向亲友借入、借给亲友与自定义四类方向；展示总额、
  已还、剩余与进度。还款时本金与手续费/利息分开填，**本金不计收支**，只有手续费/利息进账本；
  还清自动进「已结清」。
- **储蓄目标**（我的 → 储蓄目标）：名称、目标金额、截止日期、分类与备注，可多目标并行；
  支持手动存入/取出、目标间划转（两条等额流水）、自动判定「已达成」、暂停/恢复、
  取消、删除（两级确认，同时清掉流水）。首页展示「储蓄余额」，总余额 = 可用 + 储蓄不变。
- **多币种与汇率**（我的 → 汇率与币种）：CNY / JPY / USD / EUR / GBP / HKD；
  记账时可切币种并看到 `折算 ≈ ¥…` 与「1 USD = ¥7.12 · 手动 · 更新于 …」；
  汇率可自动获取（Frankfurter 公开接口）、手动修改、删除；超过 24 小时或来源降级为缓存时，
  汇率页与记账页都会提示「不是最新的」。账单详情页固定展示
  「原币 $19.99 · 汇率 6.672 · 入账 ¥133.37」与「汇率快照 缓存 · 时间」，历史不会被后续改汇率改写。
- **AI × 外币**：AI 只识别币种与金额，汇率一律用本地汇率表；外币候选卡显示原币金额与折算金额，
  并且**永远需要用户确认**（即便模型给出高置信度）；本地没有该币种汇率时只提示去设置，绝不编造汇率。

**阶段 6（数据管理与发布）**：

- **备份与恢复**（我的 → 数据备份与恢复）：一键导出 JSON 备份，范围覆盖 17 个集合
  （账单含软删除、账单操作历史、分类、标签、预算与模板、预算变更、固定支出与付款、分期与付款、
  债务与还款、储蓄目标与流水、月度报告、设置）+ 版本元信息 + `checksum`；
  可选「用密码加密备份」（PBKDF2-HMAC-SHA256 120,000 次派生 256 位密钥 + AES-GCM，
  salt/iv 每次随机，密码不落盘、不进日志、不在备份里）。
  恢复流程：选文件 → 识别格式与数据版本（高于当前 App 直接拒绝）→ 需要密码则输入 →
  解密并校验完整性（密码错误与文件损坏分开提示）→ 结构、关联与枚举值检查 →
  **恢复预览**（账单 / 分类 / 标签 / 预算 / 固定支出 / 分期 / 债务 / 储蓄目标笔数与收支合计、
  备份创建时间与来源版本、是否加密、警告项）→ 用户确认 →
  在**一个事务**里清空并写入、写完自检数量与关联，任一步失败整体回滚，不留半套数据 →
  显示恢复结果。数据安全卡另提供「检查本地数据」（完整性检查）与「清空本地数据」（二次确认）。
- **CSV 导出**（我的 → 导出数据）：五类内容（账单明细 / 预算执行 / 操作历史 / 分类统计 / 标签统计），
  账单可按时间范围（全部/本月/上月/自定义）、收支类型、分类、标签、是否含已删除筛选，
  导出前实时显示「命中 N 行」与列结构样例；UTF-8、逗号引号换行正确转义、日期 `yyyy-MM-dd`、
  金额纯数字两位小数、多币种保留原币金额 + 币种 + 汇率 + 本币金额；
  以 `= + - @` 开头的用户文本加 `'` 前缀防公式注入。
- **CSV 导入**（我的 → 导入数据）：选文件 → 识别表头（支持中英文列名）→ 逐行校验
  金额/日期/时间/类型/分类/币种 → 重复检测（日期+金额+类型+分类+备注组合键，可与已存在账单和
  文件内其他行比对）→ **预览新增 / 重复 / 冲突 / 无效各自数量与逐行原因** → 用户确认才写入 →
  结果报告（写入 N 笔、跳过重复 M 笔、无效 K 行）。未预览绝不写库；整批写入失败会回滚；
  写入用 `clientKey = import:<内容指纹>` 保证同一文件重复导入不会翻倍。
- **Excel 导出**（导出数据页 → 导出 Excel 报告）：一个 `.xlsx` 内四个工作表
  「账单明细 / 分类统计 / 预算执行 / 操作历史」，金额是数值（元，两位小数）、使用率是百分比格式、
  日期为文本、多币种保留原币与币种两列；预算与统计数字直接取自同一份统计服务，
  不写入任何 AI 结论（AI 建议与真实财务数据分开）。
- **多语言与主题**（我的 → 语言 / 外观）：简体中文（默认）、日本語、English，
  可「跟随系统」或手动指定，切换即时生效并重启保持；AI 回复语言是**独立设置**，不跟随界面语言。
  外观支持跟随系统 / 浅色 / 深色 / 动态取色（Android 12+ Material You，低版本自动禁用并说明）。
- **每日记账提醒**（我的 → 提醒）：可开关、可选时间；当天没有有效账单才提醒
  （已删除/已撤销不算），提醒后自动续排次日；用 `setAndAllowWhileIdle` 不必申请精确闹钟权限，
  权限被拒时给出「去系统设置开启通知」的说明；重启后按 `BOOT_COMPLETED` 重新排。
- **发布构建**：`versionCode 8 / versionName 0.6.1`，Release 开启 R8 混淆与资源压缩
  （APK 2.26 MB，debug 12.56 MB），签名密钥不入 Git（`keystore/` 与 `keystore.properties` 已忽略，
  需单独备份）。

**维护迭代 01（AI 能力优化与账单日期调整）**：

- **模型从服务商列表选**（AI 设置 → 主模型）：填好 Base URL 与 API Key 后点「获取模型列表」，
  走 `GET {Base URL}/models`（已含 `/v1` 时不重复拼接），解析 `data[].id`、过滤空值后展示；
  支持搜索、刷新与记住最近一次成功列表，**没有手填模型的入口**，保存的是模型 ID。
  改 Base URL 或 API Key 后旧列表立即失效并提示重新获取；获取失败时已填的 URL / 已选模型**不会被清空**。
  API Key 仍走 Android Keystore 加密，界面掩码，错误提示只显示 `sk-l…demo` 这类前后缀。
- **连通性测试与账单解析彻底分离**：测试发一条固定的 `PROBE_PROMPT`，由独立的 `AiProbe` 判定结果，
  不再复用 `AiBillParser`（这也是过去「填好配置却提示无法解析账单内容」的根因）。
  失败按原因给不同文案：未配置完整、API Key 无效或未获授权、无权限、接口地址不存在、请求过于频繁、
  服务暂时不可用、连接超时、无法连接、返回格式异常、接口已响应但无内容、模型不可用。
- **AI 主动查数据（Tool / Function Calling）**：11 个只读工具
  `get_account_summary`、`query_transactions`、`get_category_statistics`、`get_spending_trend`、
  `get_budget_status`、`get_tag_statistics`、`get_recurring_expenses`、`get_installment_summary`、
  `get_debt_summary`、`get_savings_goals`、`search_transactions`。
  工具全部经 Repository 与 `Statistics` 查询，**不接触 Room、不生成也不执行 SQL**；
  工具名走白名单，参数有类型与枚举约束，单次最多 50 行、结果超 4000 字符截断、
  区间最多 400 天、最多 4 轮调用、整体 90 秒超时；只用只读工具，不能删改任何数据。
  相对日期由 App 按设备当天算好再传给工具，模型不自己猜日期；AI 对话页发送时会显示
  「正在汇总收支…」「正在查账单…」这类当前动作。
- **账单日期只到日**：记账/编辑只选日期（不再有小时分钟），详情页与列表也只显示日期；
  数据库升到 v10 把既有 `occurredAt` 截断到当天零点，同日多笔按 `createdAt` 稳定排序；
  CSV / Excel / 备份里的日期同样是 `yyyy-MM-dd`。
- **统一的应用内提示**：全部页面改用同一套提示条（成功/信息/警告/错误四级配色），
  成功自动消失、失败可手动关闭，删除类操作只在提示条上给「撤销」；连续相同文案 1.2 秒内不重复堆叠。

## 七、测试

`.\gradlew.bat :app:testDebugUnitTest` → **306 项，0 失败**：

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `MoneyTest` | 9 | 分与元互转、非法输入、汇率换算、格式化 |
| `FinanceCalculatorTest` | 10 | 余额、今日/本周/本月统计、分类 TOP、净收支、退款抵扣 |
| `BudgetPeriodsTest` | 14 | 四种周期窗口、上一周期、结转、阈值等级、每日可用 |
| `HomeLayoutTest` | 12 | 首页模块顺序/折叠/隐藏的编解码与容错 |
| `RecordHistoryTest` | 14 | 操作历史写入、撤销回放、快照前后差异、幂等 |
| `AiNetworkTest` | 18 | URL 规范化、JSON 编解码与抽取、配置往返、请求体不含密钥、错误映射、重试策略 |
| `AiChatTest` | 18 | 候选账单解析、需确认判定、会话持久化、幂等键 |
| `StatisticsTest` | 21 | 日/周/月/自定义区间、上期对比、分类与标签占比、退款冲减、日序列分桶与合并、预算执行、预算时间进度与节奏、快照一致性 |
| `StatisticsCacheTest` | 4 | 相同输入复用同一快照、账单/区间/预算变化即失效、5000 笔本地算完且日序列 ≤ 31 点 |
| `SpendingFactsTest` | 10 | 事实 JSON 的字段与口径、数据充分性分级、不含备注原文 |
| `SpendingForecastTest` | 8 | 典型一天剔除异常日、预计总支出与剩余、可信度分级、无数据不外推 |
| `AnomalyDetectorTest` | 10 | 六类规则各一例、历史不足不提示、只读不改账单、候选 id 稳定 |
| `MonthlySummaryBuilderTest` | 6 | 九段标题与顺序、数字与统计服务一致、空数据如实说明、少于三笔提示、超支合计、分类前五 + 其余汇总 |
| `AnalysisRangeStateTest` | 4 | 无结论不算过期、同区间不过期、换区间标记过期、失败清结论保错误 |
| `FixedExpenseScheduleTest` | 8 | 八种周期的下一期推算、月末回退不漂移、到期天数、提前几天提醒 |
| `FixedReminderTest` | 6 | 提醒天数边界 0–30、到期当天与已逾期、暂停计划不提醒、本期未付只推进日期 |
| `InstallmentScheduleTest` | 11 | 各期之和等于总额、1/31→2/28→3/31 不漂移、进度与剩余、提前结束、校验分支、余数进首期 |
| `DebtLedgerTest` | 11 | 借入/借出方向、本金不计收支、手续费计入支出、还款进度、还清判定、超额还款拦截 |
| `SavingsLedgerTest` | 18 | 存入/取出口径、总余额不变、目标间划转等额、超额存入拦截、达成与暂停、删除清流水、必要月存额 |
| `CurrencyRateTest` | 7 | 币种白名单、汇率编解码往返、来源与时间保留、删除即无汇率、无汇率时拒绝折算 |
| `RateQuoteTest` | 7 | 公开接口响应解析（倒数换算与进位）、非 CNY 基准、坏数据与不支持币种、过期判定、缓存天数 |
| `CsvTest` | 8 | 逗号/引号/换行转义、公式前缀 `'`、空字段、列数不齐、空白与 CRLF |
| `CsvExporterTest` | 11 | 四类内容表头与列数、日期与金额格式、多币种原币+币种+汇率+本币、退款与软删除标记、空数据只出表头 |
| `CsvImporterTest` | 16 | 表头识别、金额/日期/类型/分类/币种校验、组合键查重、文件内自重复、无效行原因、整批幂等 |
| `ExcelExporterTest` | 12 | 四个工作表与 readme 结构、数值与百分比单元格类型、XML 转义、列名 AA 进位、统计数字与统计服务一致 |
| `BackupCodecTest` | 17 | 17 个集合往返、checksum 覆盖范围、未知集合/设置不致命、数据版本高拒绝、引用缺失告警、枚举与金额精度保留 |
| `BackupCryptoTest` | 10 | PBKDF2 + AES-GCM 往返、错密码与文件损坏分别识别、salt/iv 随机、空密码拒绝、信封不含明文 |
| `AiToolsTest` | 6 | 11 个工具名唯一且与白名单一致、描述非空、参数都是 object 且 required 都在 properties 里、range 枚举与区间常量一致、schema 可被重新解析、`toJson` 包完整 function |

单元测试只覆盖纯逻辑；图表渲染、迁移与端到端流程走模拟器人工验证。

## 八、验证记录（模拟器 API 35，1080×2400）

- v1 → v2 → v3 **三次覆盖安装**：旧账单、余额、预算全部保留，无崩溃、无数据丢失。
  v3 → v4 的月报表迁移同样验证通过。
- 阶段 2 逐屏走查：预算四种维度与暂停/删除/模板、标签创建与打标、账单详情与编辑、
  操作历史逐条撤销、回收站批量恢复与清空、首页模块拖动/折叠/隐藏重启后保持。
- 阶段 3 逐屏走查：AI 设置页各错误分支、AI 对话、手动记账表单、候选账单卡片三态；
  成功链路用本机 OpenAI 兼容 stub 端到端跑通（解析 → 自动入账 → 确认 → 撤销 → 忽略）。
- 阶段 4 逐屏走查（8 笔真实账单）：
  - 月视图：支出 ¥146.70 / 退款 ¥0.00 / 收入 ¥1,000.00 / 净支出 ¥146.70 · 8 笔 / 净收支 ¥853.30；
    环形图 餐饮 81%、未分类 18%；排名 餐饮 ¥119.30（81%）、未分类 ¥27.40（18%）；
    标签 `#work 3 笔 · 44% ¥65.90`；预算 餐饮 24%（时间进度 50%、较节奏落后 ¥130.70）、总预算 5%。
  - 下钻一致性：点趋势峰值 → 11月15日 8 笔合计 ¥146.70；点分类 → 「餐饮 · 2026年11月」4 笔 ¥119.30；
    点标签 → 「#work · 2026年11月」3 笔 ¥65.90；三者与图表数字一致。
  - 自定义区间 11月15日–11月15日：副标题与区间行一致，趋势卡改为「只有一天的数据，没有可比趋势」。
  - 消费预测：预计本月支出 ¥293.40、预计剩余 ¥2,706.60（= ¥3,000 − ¥293.40）、可信度 中等、
    5 条推算依据与「只是预测」文案齐备。
  - 异常提醒：单笔偏高/频率突增/分类结构变化三条基于个人历史触发，
    标注「这是正常消费」「忽略这类」后计数递减且重启后保持。
  - 月度总结：生成本月 → 版本 1，九段数字与统计页一致；重新生成 → 版本 2，
    版本 1 标「已被新版取代」；删除版本 2 → 版本 1 自动回到「正在查看」。
  - 性能：插入 3000 笔临时账单（共 3008 笔）后，统计页显示「净支出 ¥16,598.70 · 3008 笔」，
    切到周视图显示「¥7,192.32 · 1292 笔」，页面可滚动、筛选可切换；测完清空临时数据回到 8 笔。
  - AI 失败隔离：关闭 Wi-Fi 与移动数据后点「生成分析」→ 显示「分析未完成：网络不可用，检查一下 Wi-Fi 或移动数据。」，
    同屏本地统计与明细仍正常显示。
- 阶段 5 逐屏走查（数据库 v8 → v9 覆盖安装，旧数据完整保留）：
  - **固定支出**：建「房租 ¥800 / 每月 15 日」→ 列表「已到期」组显示到期日与剩余天数；
    点「确认付款」→ 账单多一笔「固定支出 · 房租 · 11-15」，计划推进到 12-15；
    立刻再点确认 → 提示「这一期已经付过了（付款时间 …）」，账单不增加、日期不再推进。
    提醒天数设 5 天 → 页面出现「5 天后到期」提示；「本期未付」→ 日期推进但账单不变。
  - **分期**：建「12 期 × ¥100 / 首期 11-15」→ 进度 0/12、下一期 11-15 ¥100；
    确认第 1 期 → 账单「分期第 1/12 期 · 11-15」、进度 1/12；连续点击不重复入账；
    「提前结束」（手续费 ¥30）→ 剩余期数清零、状态「已结清」。
  - **债务**：建「向朋友借 ¥3,000」→ 还本金 ¥10 + 手续费 ¥2 → 账本只多一笔 ¥2.00、
    欠款变 ¥2,990（本金不计收支）；再还 ¥2,990 → 进入「已结清」。
  - **储蓄目标**：建 Travel ¥1,000 → 存 ¥100（储蓄 ¥100、可用 ¥753.30、总余额 ¥853.30 不变、
    账本仍 8 笔）→ 取 ¥30 → 存满自动「已达成」→ 暂停后存入被拦下并提示；
    删除目标两级确认后流水一并清理。
  - **汇率与币种**：点「刷新汇率」→ 「已更新 5 个币种的汇率（来源：自动）」、徽标变「自动」；
    断网再刷新 → 「汇率服务响应超时，可以手动填写汇率；已保存的汇率仍按上次记录使用，并标记为缓存」，
    徽标降为「缓存」并提示「有 5 个币种的汇率不是最新的…」。
  - **外币账单**：记 19.99 USD → 面板自动带出汇率并显示 `折算 ≈ ¥142.33`、保存后首页总余额
    ¥710.97、详情页 `原币 $19.99 · 汇率 7.12 · 入账 ¥142.33 · 汇率快照 手动 · 时间`；
    把汇率改成 7.50 后旧账单仍是 7.12 / ¥142.33（快照不追溯）。
  - **AI × 外币**：让模型返回 19.99 USD → 候选卡 `$19.99` + `折算 ¥133.37 · 1 USD = ¥6.672` +
    理由「外币 美元 USD，按本地汇率 ¥6.672 折算，请确认金额」，即使置信度 0.99 也必须确认；
    确认后账单落库 `rateSource = CACHE`、详情显示原始来源与时间。
  - 测试数据清理后回到基线：8 笔账单、本月支出 ¥146.70、总余额 ¥853.30、储蓄 ¥0.00。

- 阶段 6 走查（Debug 版 + Release 签名版各一轮）：
  - **备份导出（明文）**：保存为 `ikuyo-backup-20261115-1159.ikuyobak`（12,844 字节），
    拉回核对 `magic=IKUYO-BACKUP`、`formatVersion=1`、`dataVersion=9`、`baseCurrency=CNY`、
    `counts.bills=8`、17 个集合齐全、`checksum` 存在，全文**不含 API Key**。
    修复点：`CreateDocument("application/json")` 会让系统面板追加 `.json`，
    改用 `application/octet-stream` 后文件名保持 `.ikuyobak` 干净。
  - **备份导出（加密）**：同一账本 4,443 字节（约为明文 1/3），信封
    `kdf=PBKDF2WithHmacSHA256 / iterations=120000 / keyBits=256 / cipher=AES/GCM/NoPadding / salt / iv / keyCheck / data`；
    在文件里搜 `餐饮`、`perf-` 均无命中 → 内容无明文。
  - **恢复**：选文件 → 输错密码 → 红字 `备份密码不正确`（预览不出现、数据不变）→
    输入正确密码 → 预览 `账单 1 笔 / 分类 12 个 / 支出合计 ¥12.50 / 备份创建时间 2026-11-15 13:34 /
    0.6.0 · 数据版本 v9 · 已加密` → 点「确认覆盖当前账本」→ `恢复完成`，页面数字与预览一致。
  - **CSV 导出**：8 笔全量 → `ikuyo-bills-20261115-1222.csv` 970 字节，16 列表头、
    日期 `2026-11-15`、金额 `28.00`、汇率 `1`、来源 `AI`/`手动`、`是否已删除=否`；
    勾「收入」后预览从 8 行变 1 行；切「全部时间」「预算执行」「操作历史」后列结构随之变化。
    Release 版同样导出成功（1 笔账本 258 字节）。
  - **CSV 导入**：上述导出文件原样导入 → `新增 0 / 重复 8`；改一行备注后 → `新增 1 / 重复 7`，
    确认后提示 `已导入 1 笔；跳过重复 7 笔`，数据库账单 8 → 9 笔、操作历史 7 → 8 条；
    清理后回 8 笔。
  - **Excel 导出**：`ikuyo-report-20261115-1247.xlsx` 5,673 字节（Release 版 4,602 字节），
    解压核对四个工作表 `账单明细 / 分类统计 / 预算执行 / 操作历史`，
    分类统计一行 `2026年11月 | 餐饮 | 4 | 119.3 | 0.8132`、预算执行 `餐饮 | 500 | 119.3 | 380.7 | 0.24 | 0`。
  - **多语言与主题**：切日本語/English 后首页与我的页文案即时变化（`ホーム / AI 記帳 / マイ`、`Home / AI Entry / Mine`），
    重启保持；AI 语言独立设置不影响界面语言；动态取色开关在 API 35 上生效，关闭后回到默认配色。
  - **每日提醒**：设 13:15 → 13:24 系统投递 `id=1001 channel=daily_reminder`、
    标题 `Nothing recorded today`（该日无账单），次日自动续排；当天记一笔账单后到点无通知；
    关闭开关后 `dumpsys alarm` 中不再有该 PendingIntent。
  - **性能（千笔）**：插入 1,000 笔临时账单后冷启动 2261–2423 ms、统计页首屏约 2.6 s、
    列表快速滑动无 ANR；统计合计 ¥33,818.70 与手算一致；清理后回基线。
  - **发布**：`assembleRelease` 出 2,261,096 字节 APK（debug 12,561,251 字节），
    `apksigner` 校验通过（`CN=Ikuyo Finance`，SHA-256 `e563e5a9…1ab0`）；
    装机后中文默认、记账/列表/统计/备份/导出全可用；
    `versionCode 6 → 7` 覆盖安装后 `versionName 0.6.0`、旧账单（1 笔 ¥12.50）完整保留。

- 维护迭代 01 走查（数据库 v9 → v10 覆盖安装，旧数据完整保留）：
  - **升级不丢不偏**：v9 → v10 覆盖安装后仍是 8 笔账单、本月支出 ¥146.70、本月收入 ¥1,000.00、
    总余额 ¥853.30、储蓄 ¥0.00；餐饮 ¥119.30（4 笔）、未分类 ¥27.40（3 笔）与升级前一致，
    账单日期只丢时分秒、没有跨天偏移。
  - **模型列表**：Base URL `http://10.0.2.2:8901/v1` 点「获取模型列表」→ `共 2 个模型`
    （`stub-gpt-4o-mini` / `stub-tool-pro`），搜索框可按名称过滤，列表里没有手填入口；
    改 Base URL 后 `已选择：…` 立刻消失，必须重新获取再选。停掉本地服务再点「刷新」→
    `连接超时，请检查网络或服务商状态。`，而 Base URL 与已选模型**仍留在界面上**。
  - **连通性测试的错误分流**（造错服务 8902，逐条看提示）：
    401 → `API Key 无效或未获授权。`；404 → `接口地址或接口路径不存在，请检查 Base URL。`；
    40 秒延迟 + 30 秒超时 → `连接超时，请检查网络或服务商状态。`；
    200 但返回 HTML → `服务商返回格式异常，可能不是 OpenAI 兼容接口。`；
    配置正确 → `成功：stub-gpt-4o-mini · 12 ms`。整个过程没有生成账单，也没有新增聊天消息。
  - **AI 主动查数据**（本地假模型按关键词选工具，两轮往返 `tools=11`）：
    `get_account_summary` → 支出 146.70 / 收入 1000.00 / 净 853.30 / 8 笔；
    `get_budget_status` → 餐饮 额度 500.00 / 已用 119.30 / 剩余 380.70、总预算 3000.00 / 已用 146.70 / 剩余 2853.30；
    `get_category_statistics` → 餐饮 119.30（4 笔，81.32%）、未分类 27.40（3 笔，18.67%）；
    `get_spending_trend` → 11-10…11-14 均为 0.00、11-15 为 146.70；
    `query_transactions` → 5 行含日期/类型/金额/分类/标签/支付方式/备注/来源；
    `get_tag_statistics` → `work` 65.90（3 笔，44.92%）；
    `search_transactions`（咖啡）、`get_recurring_expenses`、`get_installment_summary`、
    `get_debt_summary`、`get_savings_goals` → 均返回 `count: 0` 与空列表（没有数据就说没有）。
    期间修掉两处真实缺陷：预算工具误把「额度+结转」当剩余金额（改为 `可用 − 已用`）；
    上期为 0 时对比百分比输出空对象 `{}`（改为 `n/a`，不再让模型去猜）。
  - **账单日期只到日**：记账页只有日期选择器，账单详情显示 `2026-11-15`，同日多笔顺序稳定；
    CSV 与 Excel 导出的日期列都是 `yyyy-MM-dd`。

## 九、已知限制

- 备份恢复走的是「一个事务里整体覆盖」：没有合并（merge）模式，也不做跨设备同步或云盘上传，
  备份文件需要用户自己保管；加密备份忘记密码无法恢复（设计如此，不做后门）。
- 恢复逻辑包含 Room 事务与自检，只能在真机/模拟器人工验证，没有 instrumentation 测试覆盖；
  单测覆盖的是备份编解码、加密与校验（27 项）。
- CSV 导入只按表头自动识别字段，没有手动列映射界面；遇到非本项目导出的表头会判为无效列。
- Excel 只有四个工作表（账单明细 / 分类统计 / 预算执行 / 操作历史），不做图表与透视表。
- 每日提醒使用 `setAndAllowWhileIdle`（不必申请精确闹钟权限），系统打盹时最多可能延迟数分钟；
  国产 ROM 的省电策略可能需要用户手动允许自启动/后台运行。
- Release 版本开启了 R8 混淆，崩溃堆栈需要 `mapping.txt` 才能还原；签名密钥保存在本地
  `keystore/`（已 gitignore），换机或重装系统前必须自行备份，否则无法再发布同签名升级包。
- 固定支出、分期、债务都只做「手动确认这一期」，不做自动扣款、不做银行/账单导入。
- 单账户模型：储蓄是目标余额而非真实账户，同一笔钱不能在两个目标里重复占用。
- 「已付/未付」判定依赖账单落库成功；若入账失败会回滚并提示，不会出现「计划推进但没账单」。
- 汇率自动获取依赖公开接口（Frankfurter），只覆盖 JPY/USD/EUR/GBP/HKD 五种外币；
  接口不可达时按缓存使用并强制提示，不做离线汇率推算。
- 已支付的固定支出/分期如果后来被删除，计划日期不会回退（避免来回抖动），需要时手动调整。
- AI 对话不是流式输出；「已忽略」标记只在内存，重启后候选卡会回到可确认状态（不影响金额）。
- AI 工具调用是「最多 4 轮、整体 90 秒」的同步循环：复杂问题会多等几秒，超时后只回一句失败原因，
  不会把已查到的数据拼成半截答案。
- 工具返回单次最多 50 行、结果超 4000 字符截断；超大账本想「一次看完所有账单」不现实，
  需要按区间或分类缩小问题范围。
- 模型列表只缓存最近一次成功结果：换网络或服务商改了目录时需要手动「刷新」。
- 预测是线性外推：数据少于约一周或某天占比过高时可信度会自动降级，但不做季节性建模。
- 异常检测需要一定历史量（单笔偏高要求该分类 ≥ 20 笔历史），新用户短期内不会提示。
- 统计缓存是单条记忆（只保留最近一次输入的结果），连续来回切换区间会重算。
- 统计页日序列最多画 31 个点，超过的区间会自动按等宽合并成桶（金额合计不变）。

## 十、后续计划（阶段 7）

多账户与真实账户余额、云同步与跨设备账本（当前只做本地文件备份）、短信/邮件/账单文件自动导入、
订阅到期与自动续费识别、预算自动建议的回测与沉淀，以及更完整的报表与图表导出。