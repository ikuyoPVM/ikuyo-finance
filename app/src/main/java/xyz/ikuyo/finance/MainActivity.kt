package xyz.ikuyo.finance

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import xyz.ikuyo.finance.core.AppLanguage
import xyz.ikuyo.finance.ui.IkuyoApp

class MainActivity : ComponentActivity() {

    /** 语言必须在 super.onCreate 之前就位，否则恢复界面时用的是系统语言资源。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IkuyoApp()
        }
    }
}