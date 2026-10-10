package com.moneytracker.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 账单列表（首页）。 */
@Serializable data object LedgerRoute : NavKey

/** 统计页。 */
@Serializable data object StatisticsRoute : NavKey

/** 预算页。 */
@Serializable data object BudgetRoute : NavKey

/** 设置页（导出与备份）。 */
@Serializable data object SettingsRoute : NavKey

/** 分类管理页。 */
@Serializable data object CategoriesRoute : NavKey

/** 关于与隐私政策页。 */
@Serializable data object AboutRoute : NavKey

/** 记一笔 / 编辑账目。[entryId] 为 null 表示新增，否则为编辑（决策 3）。 */
@Serializable data class EditEntryRoute(val entryId: Long? = null) : NavKey
