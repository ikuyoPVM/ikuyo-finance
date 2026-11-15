package xyz.ikuyo.finance.domain.usecase

import xyz.ikuyo.finance.core.BillSnapshot
import xyz.ikuyo.finance.data.repository.BillRepository
import xyz.ikuyo.finance.data.repository.OperationRepository
import xyz.ikuyo.finance.domain.model.BillOperation
import xyz.ikuyo.finance.domain.model.BillOperationType

/**
 * 统一撤销：所有「撤销」入口都走这里，保证状态回退一致。
 *
 * - CREATE  → 彻底删除这笔刚录入的账单（不留在回收站）。
 * - DELETE  → 从回收站恢复。
 * - UPDATE  → 用操作历史中的修改前快照写回。
 * 撤销本身也会记一条 UNDO 历史，且该条不再可撤销，避免反复横跳。
 */
class UndoUseCase(
    private val billRepository: BillRepository,
    private val operationRepository: OperationRepository,
) {

    suspend operator fun invoke(operationId: Long? = null): Result<String> {
        val operation = if (operationId == null) {
            operationRepository.lastUndoable()
        } else {
            operationRepository.find(operationId)
        } ?: return Result.failure(IllegalStateException("没有可撤销的操作"))
        if (!operation.isUndoable) {
            return Result.failure(IllegalStateException("该操作已撤销或不可撤销"))
        }
        return when (operation.type) {
            BillOperationType.CREATE -> undoCreate(operation)
            BillOperationType.DELETE -> undoDelete(operation)
            BillOperationType.UPDATE -> undoUpdate(operation)
            else -> Result.failure(IllegalStateException("该操作不支持撤销"))
        }
    }

    private suspend fun undoCreate(operation: BillOperation): Result<String> {
        billRepository.purge(operation.billId)
        finish(operation)
        return Result.success("已撤销新增，账单已移除")
    }

    private suspend fun undoDelete(operation: BillOperation): Result<String> {
        billRepository.restore(operation.billId)
        finish(operation)
        return Result.success("已撤销删除，账单已恢复")
    }

    private suspend fun undoUpdate(operation: BillOperation): Result<String> {
        val snapshot = BillSnapshot.decode(operation.beforeJson)
            ?: return Result.failure(IllegalStateException("修改前快照缺失，无法撤销"))
        billRepository.applySnapshot(snapshot)
        finish(operation)
        return Result.success("已撤销修改，账单已还原")
    }

    private suspend fun finish(operation: BillOperation) {
        operationRepository.markUndone(operation.id)
        operationRepository.record(
            billId = operation.billId,
            type = BillOperationType.UNDO,
            summary = "撤销：${operation.summary}",
            amountMinor = operation.amountMinor,
        )
    }
}