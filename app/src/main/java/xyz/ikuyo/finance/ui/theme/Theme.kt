package xyz.ikuyo.finance.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = androidx.compose.ui.graphics.Color(0xFFFDF8FD),
    surface = androidx.compose.ui.graphics.Color(0xFFFDF8FD),
)

private val DarkColors = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

@Composable
fun IkuyoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IkuyoTypography,
        content = content,
    )
}

/** 支出/收入/退款对应的语义色。 */
@Composable
fun amountColorFor(type: xyz.ikuyo.finance.domain.model.BillType): androidx.compose.ui.graphics.Color {
    val dark = isSystemInDarkTheme()
    return when (type) {
        xyz.ikuyo.finance.domain.model.BillType.EXPENSE ->
            if (dark) ExpenseRedDark else ExpenseRed

        xyz.ikuyo.finance.domain.model.BillType.INCOME ->
            if (dark) IncomeGreenDark else IncomeGreen

        xyz.ikuyo.finance.domain.model.BillType.REFUND ->
            if (dark) RefundBlueDark else RefundBlue
    }
}