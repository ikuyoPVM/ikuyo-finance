package xyz.ikuyo.finance.data.backup

import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.data.local.entity.BillEntity
import xyz.ikuyo.finance.data.local.entity.BillOperationEntity
import xyz.ikuyo.finance.data.local.entity.BillTagCrossRef
import xyz.ikuyo.finance.data.local.entity.BudgetChangeEntity
import xyz.ikuyo.finance.data.local.entity.BudgetEntity
import xyz.ikuyo.finance.data.local.entity.BudgetTemplateEntity
import xyz.ikuyo.finance.data.local.entity.CategoryEntity
import xyz.ikuyo.finance.data.local.entity.DebtEntity
import xyz.ikuyo.finance.data.local.entity.DebtPaymentEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpenseEntity
import xyz.ikuyo.finance.data.local.entity.FixedExpensePaymentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentEntity
import xyz.ikuyo.finance.data.local.entity.InstallmentPaymentEntity
import xyz.ikuyo.finance.data.local.entity.MonthlyReportEntity
import xyz.ikuyo.finance.data.local.entity.SavingsEntryEntity
import xyz.ikuyo.finance.data.local.entity.SavingsGoalEntity
import xyz.ikuyo.finance.data.local.entity.TagEntity
import java.security.MessageDigest

class BackupFormatException(message: String) : IllegalArgumentException(message)

/**
 * 备份文件编解码（纯 Kotlin，可在 JVM 单测里跑）。
 *
 * 外层结构：
 * ```
 * {
 *   "magic": "IKUYO-BACKUP",
 *   "formatVersion": 1, "dataVersion": 9, "appVersion": "0.6.0",
 *   "createdAt": 1794780000000, "baseCurrency": "CNY",
 *   "counts": { "bills": 8, ... },
 *   "data": { ... 全部集合 ... },
 *   "checksum": "<sha256(data 子树序列化结果)>"
 * }
 * ```
 * 校验只覆盖 `data` 子树：加密只是外层包装，不改变「数据是否被改过」的判断。
 */
object BackupCodec {

    // ---------------------------------------------------------------- encode

    fun encode(data: BackupData, meta: BackupMeta): String {
        val payload = encodeData(data)
        val envelope = Json.obj(
            "magic" to Json.str(BackupFormat.MAGIC),
            "formatVersion" to Json.num(meta.formatVersion.toLong()),
            "dataVersion" to Json.num(meta.dataVersion.toLong()),
            "appVersion" to Json.str(meta.appVersion),
            "createdAt" to Json.num(meta.createdAt),
            "baseCurrency" to Json.str(meta.baseCurrency),
            "counts" to encodeCounts(meta.counts.ifEmpty { data.counts() }),
            "data" to payload,
            "checksum" to Json.str(checksumOf(payload)),
        )
        return Json.dumps(envelope)
    }

    /** 序列化数据子树；两处调用（编码与整体校验）必须走同一实现，否则校验永远对不上。 */
    fun encodeData(data: BackupData): JsonValue.Obj = Json.obj(
        "bills" to list(data.bills) { bill ->
            Json.obj(
                "id" to num(bill.id),
                "type" to Json.str(bill.type),
                "amountMinor" to num(bill.amountMinor),
                "currency" to Json.str(bill.currency),
                "exchangeRateScaled" to num(bill.exchangeRateScaled),
                "cnyAmountMinor" to num(bill.cnyAmountMinor),
                "categoryId" to num(bill.categoryId),
                "paymentMethod" to Json.str(bill.paymentMethod),
                "note" to Json.str(bill.note),
                "occurredAt" to num(bill.occurredAt),
                "createdAt" to num(bill.createdAt),
                "source" to Json.str(bill.source),
                "refundOfBillId" to num(bill.refundOfBillId),
                "rateSource" to Json.str(bill.rateSource),
                "rateUpdatedAt" to num(bill.rateUpdatedAt),
                "clientKey" to Json.str(bill.clientKey),
                "deleted" to Json.bool(bill.deleted),
                "deletedAt" to num(bill.deletedAt),
            )
        },
        "billTags" to list(data.billTags) { ref ->
            Json.obj("billId" to num(ref.billId), "tagId" to num(ref.tagId))
        },
        "operations" to list(data.operations) { op ->
            Json.obj(
                "id" to num(op.id),
                "billId" to num(op.billId),
                "type" to Json.str(op.type),
                "summary" to Json.str(op.summary),
                "beforeJson" to str(op.beforeJson),
                "afterJson" to str(op.afterJson),
                "amountMinor" to num(op.amountMinor),
                "undone" to Json.bool(op.undone),
                "operatedAt" to num(op.operatedAt),
            )
        },
        "categories" to list(data.categories) { c ->
            Json.obj(
                "id" to num(c.id),
                "name" to Json.str(c.name),
                "emoji" to Json.str(c.emoji),
                "colorArgb" to num(c.colorArgb.toLong()),
                "isDefault" to Json.bool(c.isDefault),
                "sortOrder" to num(c.sortOrder.toLong()),
            )
        },
        "tags" to list(data.tags) { t ->
            Json.obj(
                "id" to num(t.id),
                "name" to Json.str(t.name),
                "colorArgb" to num(t.colorArgb.toLong()),
            )
        },
        "budgets" to list(data.budgets) { b ->
            Json.obj(
                "id" to num(b.id),
                "scope" to Json.str(b.scope),
                "categoryId" to num(b.categoryId),
                "tagId" to num(b.tagId),
                "name" to str(b.name),
                "amountMinor" to num(b.amountMinor),
                "periodType" to Json.str(b.periodType),
                "carryOver" to Json.bool(b.carryOver),
                "customStart" to num(b.customStart),
                "customEnd" to num(b.customEnd),
                "enabled" to Json.bool(b.enabled),
                "note" to str(b.note),
                "createdAt" to num(b.createdAt),
                "updatedAt" to num(b.updatedAt),
            )
        },
        "budgetTemplates" to list(data.budgetTemplates) { t ->
            Json.obj(
                "id" to num(t.id),
                "name" to Json.str(t.name),
                "scope" to Json.str(t.scope),
                "categoryId" to num(t.categoryId),
                "tagId" to num(t.tagId),
                "amountMinor" to num(t.amountMinor),
                "periodType" to Json.str(t.periodType),
                "carryOver" to Json.bool(t.carryOver),
                "note" to str(t.note),
                "createdAt" to num(t.createdAt),
            )
        },
        "budgetChanges" to list(data.budgetChanges) { c ->
            Json.obj(
                "id" to num(c.id),
                "budgetId" to num(c.budgetId),
                "scope" to Json.str(c.scope),
                "categoryId" to num(c.categoryId),
                "categoryName" to str(c.categoryName),
                "oldAmountMinor" to num(c.oldAmountMinor),
                "newAmountMinor" to num(c.newAmountMinor),
                "periodType" to Json.str(c.periodType),
                "changedAt" to num(c.changedAt),
            )
        },
        "fixedExpenses" to list(data.fixedExpenses) { f ->
            Json.obj(
                "id" to num(f.id),
                "name" to Json.str(f.name),
                "amountMinor" to num(f.amountMinor),
                "categoryId" to num(f.categoryId),
                "paymentMethod" to Json.str(f.paymentMethod),
                "periodType" to Json.str(f.periodType),
                "dueAt" to num(f.dueAt),
                "anchorDay" to num(f.anchorDay.toLong()),
                "customDays" to num(f.customDays.toLong()),
                "isVariable" to Json.bool(f.isVariable),
                "enabled" to Json.bool(f.enabled),
                "note" to Json.str(f.note),
                "createdAt" to num(f.createdAt),
                "updatedAt" to num(f.updatedAt),
            )
        },
        "fixedExpensePayments" to list(data.fixedExpensePayments) { p ->
            Json.obj(
                "id" to num(p.id),
                "fixedExpenseId" to num(p.fixedExpenseId),
                "billId" to num(p.billId),
                "amountMinor" to num(p.amountMinor),
                "dueAt" to num(p.dueAt),
                "paidAt" to num(p.paidAt),
                "note" to Json.str(p.note),
            )
        },
        "installments" to list(data.installments) { it0 ->
            Json.obj(
                "id" to num(it0.id),
                "name" to Json.str(it0.name),
                "totalMinor" to num(it0.totalMinor),
                "principalMinor" to num(it0.principalMinor),
                "feeMinor" to num(it0.feeMinor),
                "interestMinor" to num(it0.interestMinor),
                "periodCount" to num(it0.periodCount.toLong()),
                "firstPeriodMinor" to num(it0.firstPeriodMinor),
                "perPeriodMinor" to num(it0.perPeriodMinor),
                "dueAt" to num(it0.dueAt),
                "anchorDay" to num(it0.anchorDay.toLong()),
                "categoryId" to num(it0.categoryId),
                "paymentMethod" to Json.str(it0.paymentMethod),
                "enabled" to Json.bool(it0.enabled),
                "closedEarlyAt" to num(it0.closedEarlyAt),
                "note" to Json.str(it0.note),
                "createdAt" to num(it0.createdAt),
                "updatedAt" to num(it0.updatedAt),
            )
        },
        "installmentPayments" to list(data.installmentPayments) { p ->
            Json.obj(
                "id" to num(p.id),
                "installmentId" to num(p.installmentId),
                "periodIndex" to num(p.periodIndex.toLong()),
                "billId" to num(p.billId),
                "amountMinor" to num(p.amountMinor),
                "dueAt" to num(p.dueAt),
                "paidAt" to num(p.paidAt),
                "note" to Json.str(p.note),
            )
        },
        "debts" to list(data.debts) { d ->
            Json.obj(
                "id" to num(d.id),
                "name" to Json.str(d.name),
                "type" to Json.str(d.type),
                "principalMinor" to num(d.principalMinor),
                "dueAt" to num(d.dueAt),
                "anchorDay" to num(d.anchorDay.toLong()),
                "categoryId" to num(d.categoryId),
                "paymentMethod" to Json.str(d.paymentMethod),
                "enabled" to Json.bool(d.enabled),
                "settledAt" to num(d.settledAt),
                "note" to Json.str(d.note),
                "createdAt" to num(d.createdAt),
                "updatedAt" to num(d.updatedAt),
            )
        },
        "debtPayments" to list(data.debtPayments) { p ->
            Json.obj(
                "id" to num(p.id),
                "debtId" to num(p.debtId),
                "principalMinor" to num(p.principalMinor),
                "extraMinor" to num(p.extraMinor),
                "billId" to num(p.billId),
                "paidAt" to num(p.paidAt),
                "note" to Json.str(p.note),
            )
        },
        "savingsGoals" to list(data.savingsGoals) { g ->
            Json.obj(
                "id" to num(g.id),
                "name" to Json.str(g.name),
                "targetMinor" to num(g.targetMinor),
                "dueAt" to num(g.dueAt),
                "note" to Json.str(g.note),
                "categoryId" to num(g.categoryId),
                "status" to Json.str(g.status),
                "createdAt" to num(g.createdAt),
                "updatedAt" to num(g.updatedAt),
            )
        },
        "savingsEntries" to list(data.savingsEntries) { e ->
            Json.obj(
                "id" to num(e.id),
                "goalId" to num(e.goalId),
                "type" to Json.str(e.type),
                "amountMinor" to num(e.amountMinor),
                "occurredAt" to num(e.occurredAt),
                "note" to Json.str(e.note),
            )
        },
        "monthlyReports" to list(data.monthlyReports) { r ->
            Json.obj(
                "id" to num(r.id),
                "yearMonth" to Json.str(r.yearMonth),
                "version" to num(r.version.toLong()),
                "generatedAt" to num(r.generatedAt),
                "dataStart" to num(r.dataStart),
                "dataEndInclusive" to num(r.dataEndInclusive),
                "billCount" to num(r.billCount.toLong()),
                "netExpenseMinor" to num(r.netExpenseMinor),
                "incomeMinor" to num(r.incomeMinor),
                "overspendMinor" to num(r.overspendMinor),
                "payloadJson" to Json.str(r.payloadJson),
                "narrative" to str(r.narrative),
                "superseded" to Json.bool(r.superseded),
            )
        },
        "settings" to Json.obj(
            *data.settings.values.entries
                .sortedBy { it.key }
                .map { it.key to Json.str(it.value) as JsonValue? }
                .toTypedArray()
        ),
    )

    // ---------------------------------------------------------------- decode

    fun decode(text: String): Result<ParsedBackup> = runCatching {
        val root = Json.parseOrNull(text) as? JsonValue.Obj
            ?: throw BackupFormatException("文件内容不是有效的备份 JSON")
        val magic = root.str("magic")
        if (magic != BackupFormat.MAGIC) {
            throw BackupFormatException("这不是 Ikuyo Finance 的备份文件")
        }
        val formatVersion = root.int("formatVersion") ?: 0
        if (formatVersion > BackupFormat.FORMAT_VERSION) {
            throw BackupFormatException(
                "备份的文件格式版本（v$formatVersion）高于当前应用（v${BackupFormat.FORMAT_VERSION}），请先升级应用再恢复"
            )
        }
        val dataVersion = root.int("dataVersion") ?: 0
        if (dataVersion > BackupFormat.DATA_VERSION) {
            throw BackupFormatException(
                "备份的数据版本（v$dataVersion）高于当前应用（v${BackupFormat.DATA_VERSION}），请先升级应用再恢复"
            )
        }
        val payload = root.obj("data")
            ?: throw BackupFormatException("备份文件里没有数据内容")

        val expected = root.str("checksum")
        val actual = checksumOf(payload)
        if (expected.isNullOrBlank()) {
            throw BackupFormatException("备份文件缺少完整性校验值，可能不是本应用生成的")
        }
        if (!expected.equals(actual, ignoreCase = true)) {
            throw BackupFormatException("完整性校验失败：备份内容与校验值不一致，文件可能已损坏或被修改")
        }

        val data = decodeData(payload)
        val meta = BackupMeta(
            formatVersion = formatVersion,
            dataVersion = dataVersion,
            appVersion = root.str("appVersion").orEmpty(),
            createdAt = root.long("createdAt") ?: 0L,
            baseCurrency = root.str("baseCurrency") ?: "CNY",
            counts = decodeCounts(root.obj("counts")).ifEmpty { data.counts() },
            checksum = actual,
        )
        ParsedBackup(meta = meta, data = data, encrypted = false)
    }

    private fun decodeData(root: JsonValue.Obj): BackupData = BackupData(
        bills = decodeList(root, "bills") { o ->
            BillEntity(
                id = o.long("id") ?: 0L,
                type = o.str("type").orEmpty(),
                amountMinor = o.long("amountMinor") ?: 0L,
                currency = o.str("currency") ?: "CNY",
                exchangeRateScaled = o.long("exchangeRateScaled") ?: 1_000_000L,
                cnyAmountMinor = o.long("cnyAmountMinor") ?: 0L,
                categoryId = o.long("categoryId"),
                paymentMethod = o.str("paymentMethod").orEmpty(),
                note = o.str("note").orEmpty(),
                occurredAt = o.long("occurredAt") ?: 0L,
                createdAt = o.long("createdAt") ?: 0L,
                source = o.str("source") ?: "MANUAL",
                refundOfBillId = o.long("refundOfBillId"),
                rateSource = o.str("rateSource") ?: "MANUAL",
                rateUpdatedAt = o.long("rateUpdatedAt") ?: 0L,
                clientKey = o.str("clientKey").orEmpty(),
                deleted = o.bool("deleted") ?: false,
                deletedAt = o.long("deletedAt"),
            )
        },
        billTags = decodeList(root, "billTags") { o ->
            BillTagCrossRef(
                billId = o.long("billId") ?: 0L,
                tagId = o.long("tagId") ?: 0L,
            )
        },
        operations = decodeList(root, "operations") { o ->
            BillOperationEntity(
                id = o.long("id") ?: 0L,
                billId = o.long("billId") ?: 0L,
                type = o.str("type").orEmpty(),
                summary = o.str("summary").orEmpty(),
                beforeJson = o.str("beforeJson"),
                afterJson = o.str("afterJson"),
                amountMinor = o.long("amountMinor") ?: 0L,
                undone = o.bool("undone") ?: false,
                operatedAt = o.long("operatedAt") ?: 0L,
            )
        },
        categories = decodeList(root, "categories") { o ->
            CategoryEntity(
                id = o.long("id") ?: 0L,
                name = o.str("name").orEmpty(),
                emoji = o.str("emoji") ?: "🏷️",
                colorArgb = (o.long("colorArgb") ?: 0xFF6750A4L).toInt(),
                isDefault = o.bool("isDefault") ?: true,
                sortOrder = o.int("sortOrder") ?: 0,
            )
        },
        tags = decodeList(root, "tags") { o ->
            TagEntity(
                id = o.long("id") ?: 0L,
                name = o.str("name").orEmpty(),
                colorArgb = (o.long("colorArgb") ?: 0xFF6750A4L).toInt(),
            )
        },
        budgets = decodeList(root, "budgets") { o ->
            BudgetEntity(
                id = o.long("id") ?: 0L,
                scope = o.str("scope").orEmpty(),
                categoryId = o.long("categoryId"),
                tagId = o.long("tagId"),
                name = o.str("name"),
                amountMinor = o.long("amountMinor") ?: 0L,
                periodType = o.str("periodType") ?: "MONTHLY",
                carryOver = o.bool("carryOver") ?: false,
                customStart = o.long("customStart"),
                customEnd = o.long("customEnd"),
                enabled = o.bool("enabled") ?: true,
                note = o.str("note"),
                createdAt = o.long("createdAt") ?: 0L,
                updatedAt = o.long("updatedAt") ?: 0L,
            )
        },
        budgetTemplates = decodeList(root, "budgetTemplates") { o ->
            BudgetTemplateEntity(
                id = o.long("id") ?: 0L,
                name = o.str("name").orEmpty(),
                scope = o.str("scope") ?: "SPECIAL",
                categoryId = o.long("categoryId"),
                tagId = o.long("tagId"),
                amountMinor = o.long("amountMinor") ?: 0L,
                periodType = o.str("periodType") ?: "MONTHLY",
                carryOver = o.bool("carryOver") ?: false,
                note = o.str("note"),
                createdAt = o.long("createdAt") ?: 0L,
            )
        },
        budgetChanges = decodeList(root, "budgetChanges") { o ->
            BudgetChangeEntity(
                id = o.long("id") ?: 0L,
                budgetId = o.long("budgetId") ?: 0L,
                scope = o.str("scope").orEmpty(),
                categoryId = o.long("categoryId"),
                categoryName = o.str("categoryName"),
                oldAmountMinor = o.long("oldAmountMinor"),
                newAmountMinor = o.long("newAmountMinor"),
                periodType = o.str("periodType").orEmpty(),
                changedAt = o.long("changedAt") ?: 0L,
            )
        },
        fixedExpenses = decodeList(root, "fixedExpenses") { o ->
            FixedExpenseEntity(
                id = o.long("id") ?: 0L,
                name = o.str("name").orEmpty(),
                amountMinor = o.long("amountMinor") ?: 0L,
                categoryId = o.long("categoryId"),
                paymentMethod = o.str("paymentMethod").orEmpty(),
                periodType = o.str("periodType") ?: "MONTHLY",
                dueAt = o.long("dueAt") ?: 0L,
                anchorDay = o.int("anchorDay") ?: 1,
                customDays = o.int("customDays") ?: 30,
                isVariable = o.bool("isVariable") ?: false,
                enabled = o.bool("enabled") ?: true,
                note = o.str("note").orEmpty(),
                createdAt = o.long("createdAt") ?: 0L,
                updatedAt = o.long("updatedAt") ?: 0L,
            )
        },
        fixedExpensePayments = decodeList(root, "fixedExpensePayments") { o ->
            FixedExpensePaymentEntity(
                id = o.long("id") ?: 0L,
                fixedExpenseId = o.long("fixedExpenseId") ?: 0L,
                billId = o.long("billId"),
                amountMinor = o.long("amountMinor") ?: 0L,
                dueAt = o.long("dueAt") ?: 0L,
                paidAt = o.long("paidAt") ?: 0L,
                note = o.str("note").orEmpty(),
            )
        },
        installments = decodeList(root, "installments") { o ->
            InstallmentEntity(
                id = o.long("id") ?: 0L,
                name = o.str("name").orEmpty(),
                totalMinor = o.long("totalMinor") ?: 0L,
                principalMinor = o.long("principalMinor") ?: 0L,
                feeMinor = o.long("feeMinor") ?: 0L,
                interestMinor = o.long("interestMinor") ?: 0L,
                periodCount = o.int("periodCount") ?: 1,
                firstPeriodMinor = o.long("firstPeriodMinor") ?: 0L,
                perPeriodMinor = o.long("perPeriodMinor") ?: 0L,
                dueAt = o.long("dueAt") ?: 0L,
                anchorDay = o.int("anchorDay") ?: 1,
                categoryId = o.long("categoryId"),
                paymentMethod = o.str("paymentMethod").orEmpty(),
                enabled = o.bool("enabled") ?: true,
                closedEarlyAt = o.long("closedEarlyAt"),
                note = o.str("note").orEmpty(),
                createdAt = o.long("createdAt") ?: 0L,
                updatedAt = o.long("updatedAt") ?: 0L,
            )
        },
        installmentPayments = decodeList(root, "installmentPayments") { o ->
            InstallmentPaymentEntity(
                id = o.long("id") ?: 0L,
                installmentId = o.long("installmentId") ?: 0L,
                periodIndex = o.int("periodIndex") ?: 1,
                billId = o.long("billId"),
                amountMinor = o.long("amountMinor") ?: 0L,
                dueAt = o.long("dueAt") ?: 0L,
                paidAt = o.long("paidAt") ?: 0L,
                note = o.str("note").orEmpty(),
            )
        },
        debts = decodeList(root, "debts") { o ->
            DebtEntity(
                id = o.long("id") ?: 0L,
                name = o.str("name").orEmpty(),
                type = o.str("type").orEmpty(),
                principalMinor = o.long("principalMinor") ?: 0L,
                dueAt = o.long("dueAt") ?: 0L,
                anchorDay = o.int("anchorDay") ?: 1,
                categoryId = o.long("categoryId"),
                paymentMethod = o.str("paymentMethod") ?: "OTHER",
                enabled = o.bool("enabled") ?: true,
                settledAt = o.long("settledAt"),
                note = o.str("note").orEmpty(),
                createdAt = o.long("createdAt") ?: 0L,
                updatedAt = o.long("updatedAt") ?: 0L,
            )
        },
        debtPayments = decodeList(root, "debtPayments") { o ->
            DebtPaymentEntity(
                id = o.long("id") ?: 0L,
                debtId = o.long("debtId") ?: 0L,
                principalMinor = o.long("principalMinor") ?: 0L,
                extraMinor = o.long("extraMinor") ?: 0L,
                billId = o.long("billId"),
                paidAt = o.long("paidAt") ?: 0L,
                note = o.str("note").orEmpty(),
            )
        },
        savingsGoals = decodeList(root, "savingsGoals") { o ->
            SavingsGoalEntity(
                id = o.long("id") ?: 0L,
                name = o.str("name").orEmpty(),
                targetMinor = o.long("targetMinor") ?: 0L,
                dueAt = o.long("dueAt") ?: 0L,
                note = o.str("note").orEmpty(),
                categoryId = o.long("categoryId"),
                status = o.str("status") ?: "ACTIVE",
                createdAt = o.long("createdAt") ?: 0L,
                updatedAt = o.long("updatedAt") ?: 0L,
            )
        },
        savingsEntries = decodeList(root, "savingsEntries") { o ->
            SavingsEntryEntity(
                id = o.long("id") ?: 0L,
                goalId = o.long("goalId"),
                type = o.str("type").orEmpty(),
                amountMinor = o.long("amountMinor") ?: 0L,
                occurredAt = o.long("occurredAt") ?: 0L,
                note = o.str("note").orEmpty(),
            )
        },
        monthlyReports = decodeList(root, "monthlyReports") { o ->
            MonthlyReportEntity(
                id = o.long("id") ?: 0L,
                yearMonth = o.str("yearMonth").orEmpty(),
                version = o.int("version") ?: 1,
                generatedAt = o.long("generatedAt") ?: 0L,
                dataStart = o.long("dataStart") ?: 0L,
                dataEndInclusive = o.long("dataEndInclusive") ?: 0L,
                billCount = o.int("billCount") ?: 0,
                netExpenseMinor = o.long("netExpenseMinor") ?: 0L,
                incomeMinor = o.long("incomeMinor") ?: 0L,
                overspendMinor = o.long("overspendMinor") ?: 0L,
                payloadJson = o.str("payloadJson").orEmpty(),
                narrative = o.str("narrative"),
                superseded = o.bool("superseded") ?: false,
            )
        },
        settings = BackupSettings(
            (root.obj("settings")?.fields?.entries ?: emptySet<Map.Entry<String, JsonValue>>())
                .mapNotNull { (key, value) -> (value as? JsonValue.Str)?.let { key to it.value } }
                .toMap()
        ),
    )

    // ------------------------------------------------------------ integrity

    /** data 子树的 SHA-256（十六进制小写）；编码与校验共用同一序列化路径。 */
    fun checksumOf(payload: JsonValue.Obj): String =
        sha256Hex(Json.dumps(payload))

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /**
     * 结构检查：能不能恢复、恢复后会不会出现「账单挂在不存在的分类上」这类悬空数据。
     * errors 阻断恢复，warnings 只提示。
     */
    fun issuesOf(data: BackupData): BackupIssues {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val billIds = data.bills.map { it.id }.toSet()
        val categoryIds = data.categories.map { it.id }.toSet()
        val tagIds = data.tags.map { it.id }.toSet()
        val installmentIds = data.installments.map { it.id }.toSet()
        val fixedIds = data.fixedExpenses.map { it.id }.toSet()
        val debtIds = data.debts.map { it.id }.toSet()
        val goalIds = data.savingsGoals.map { it.id }.toSet()
        val budgetIds = data.budgets.map { it.id }.toSet()

        if (data.isEmpty()) errors += "备份里没有任何账本数据"

        val duplicateClientKeys = data.bills.groupBy { it.clientKey }.filterValues { it.size > 1 }
        if (duplicateClientKeys.isNotEmpty()) {
            errors += "有 ${duplicateClientKeys.size} 个重复的账单标识（clientKey），导入会互相覆盖"
        }

        val orphanCategory = data.bills.count { it.categoryId != null && it.categoryId !in categoryIds }
        if (orphanCategory > 0) errors += "$orphanCategory 笔账单指向不存在的分类"

        val orphanRefund = data.bills.count { it.refundOfBillId != null && it.refundOfBillId !in billIds }
        if (orphanRefund > 0) warnings += "$orphanRefund 笔退款的原始账单不在备份里"

        val orphanBillTags = data.billTags.count { it.billId !in billIds || it.tagId !in tagIds }
        if (orphanBillTags > 0) errors += "$orphanBillTags 条账单标签关联的账单或标签不存在"

        val orphanBudgetCategory = data.budgets.count { it.categoryId != null && it.categoryId !in categoryIds }
        if (orphanBudgetCategory > 0) warnings += "$orphanBudgetCategory 个分类预算指向不存在的分类"

        val orphanBudgetTag = data.budgets.count { it.tagId != null && it.tagId !in tagIds }
        if (orphanBudgetTag > 0) warnings += "$orphanBudgetTag 个标签预算指向不存在的标签"

        val duplicatePeriods = data.installmentPayments
            .groupBy { it.installmentId to it.periodIndex }
            .filterValues { it.size > 1 }
        if (duplicatePeriods.isNotEmpty()) {
            errors += "有 ${duplicatePeriods.size} 个分期的同一期存在多条付款记录"
        }

        val orphanFixedPayments = data.fixedExpensePayments.count { it.fixedExpenseId !in fixedIds }
        if (orphanFixedPayments > 0) errors += "$orphanFixedPayments 条固定支出付款记录找不到对应计划"

        val orphanInstallmentPayments = data.installmentPayments.count { it.installmentId !in installmentIds }
        if (orphanInstallmentPayments > 0) errors += "$orphanInstallmentPayments 条分期付款记录找不到对应分期"

        val orphanDebtPayments = data.debtPayments.count { it.debtId !in debtIds }
        if (orphanDebtPayments > 0) errors += "$orphanDebtPayments 条债务还款记录找不到对应债务"

        val orphanGoalEntries = data.savingsEntries.count { it.goalId != null && it.goalId !in goalIds }
        if (orphanGoalEntries > 0) warnings += "$orphanGoalEntries 条储蓄流水找不到对应目标"

        val orphanBudgetChanges = data.budgetChanges.count { it.budgetId != 0L && it.budgetId !in budgetIds }
        if (orphanBudgetChanges > 0) warnings += "$orphanBudgetChanges 条预算变更记录找不到对应预算"

        val negativeAmounts = data.bills.count { it.amountMinor < 0 || it.cnyAmountMinor < 0 }
        if (negativeAmounts > 0) errors += "$negativeAmounts 笔账单金额为负数"

        return BackupIssues(errors = errors, warnings = warnings)
    }

    // --------------------------------------------------------------- helpers

    private fun encodeCounts(counts: Map<String, Int>): JsonValue.Obj =
        Json.obj(
            *counts.entries
                .sortedBy { it.key }
                .map { it.key to Json.num(it.value.toLong()) as JsonValue? }
                .toTypedArray()
        )

    private fun decodeCounts(obj: JsonValue.Obj?): Map<String, Int> =
        obj?.fields?.entries
            ?.mapNotNull { (key, value) -> (value as? JsonValue.Num)?.let { key to it.value.toInt() } }
            ?.toMap()
            ?: emptyMap()

    private fun <T> list(items: List<T>, f: (T) -> JsonValue): JsonValue = Json.arr(items.map(f))

    private fun <T> decodeList(
        parent: JsonValue.Obj,
        key: String,
        f: (JsonValue.Obj) -> T,
    ): List<T> = parent.arr(key)?.items
        ?.mapNotNull { it as? JsonValue.Obj }
        ?.map(f)
        ?: emptyList()

    private fun num(value: Long?): JsonValue? = value?.let { Json.num(it) }

    private fun str(value: String?): JsonValue? = value?.let { Json.str(it) }
}