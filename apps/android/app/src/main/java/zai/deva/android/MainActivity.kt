package zai.deva.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import zai.deva.android.ui.DevaTheme

/**
 * 单 Activity + Compose Navigation（设计 §4）。P0 只渲染空壳占位；
 * P2 起接 HomeScreen（服务器 + 会话列表）与 ChatScreen 的导航图。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DevaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text(text = "Deva")
                }
            }
        }
    }
}
