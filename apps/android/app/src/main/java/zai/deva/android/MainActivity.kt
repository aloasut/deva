package zai.deva.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import zai.deva.android.ui.ChatScreen
import zai.deva.android.ui.DevaTheme
import zai.deva.android.ui.HomeScreen
import zai.deva.android.ui.SettingsScreen

/**
 * 单 Activity + Compose Navigation（设计 §4）：home（服务器 + 会话列表）→ chat/:sessionId / settings。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DevaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onOpenChat = { sid -> nav.navigate("chat/$sid") },
                                onOpenSettings = { nav.navigate("settings") }
                            )
                        }
                        composable("chat/{sid}") { entry ->
                            val sid = entry.arguments?.getString("sid").orEmpty()
                            ChatScreen(sessionId = sid, onBack = { nav.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
