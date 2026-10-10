package com.moneytracker.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 收支语义色。
 *
 * Material3 没有「支出/收入」这类语义色，必须自定义。深色模式下用更亮的色阶，
 * 否则纯红/纯绿在深色背景上对比度不足。
 */
private val ExpenseLight = Color(0xFFD9534F)
private val ExpenseDark = Color(0xFFFF8A80)

private val IncomeLight = Color(0xFF2E9E5B)
private val IncomeDark = Color(0xFF81C995)

@Composable
fun expenseColor(): Color = if (isSystemInDarkTheme()) ExpenseDark else ExpenseLight

@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) IncomeDark else IncomeLight
