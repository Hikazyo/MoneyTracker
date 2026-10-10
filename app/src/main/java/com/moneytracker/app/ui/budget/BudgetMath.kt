package com.moneytracker.app.ui.budget

import com.moneytracker.app.data.model.Money
import java.util.Locale

/**
 * 预算进度计算。全部是纯函数，便于直接单元测试。
 *
 * 边界集中在三处，也是最容易出错的地方：
 *  - 未设限额（null）与限额为 0 要区分开：0 是非法额度，不应被当成「已用满」
 *  - 比例**不**在上层截断，可以大于 1，界面据此判断超支
 *  - 恰好用完（spent == limit）不算超支
 */
object BudgetMath {

  /** 已用比例。未设限额或限额非正时返回 null（无法计算，而不是当成 0）。 */
  fun ratio(spentMinor: Long, limitMinor: Long?): Float? {
    if (limitMinor == null || limitMinor <= 0L) return null
    return spentMinor.toDouble().div(limitMinor.toDouble()).toFloat()
  }

  /** 是否超支。恰好用完不算超支。 */
  fun isOverBudget(spentMinor: Long, limitMinor: Long?): Boolean =
    limitMinor != null && limitMinor > 0L && spentMinor > limitMinor

  /** 剩余额度，可为负（表示超支了多少）。 */
  fun remainingMinor(spentMinor: Long, limitMinor: Long): Long = limitMinor - spentMinor

  /** "剩余 680.00" 或 "超支 20.00"。 */
  fun remainingLabel(spentMinor: Long, limitMinor: Long): String {
    val remaining = remainingMinor(spentMinor, limitMinor)
    return if (remaining >= 0L) "剩余 ${Money.formatGrouped(remaining)}"
    else "超支 ${Money.formatGrouped(-remaining)}"
  }

  /** "320.00 / 1,000.00" */
  fun progressLabel(spentMinor: Long, limitMinor: Long): String =
    "${Money.formatGrouped(spentMinor)} / ${Money.formatGrouped(limitMinor)}"

  /** 进度百分比，用于无障碍描述等场景。 */
  fun percentLabel(spentMinor: Long, limitMinor: Long?): String {
    val percent = ratio(spentMinor, limitMinor) ?: return "—"
    return String.format(Locale.US, "%.0f%%", percent * 100f)
  }
}
