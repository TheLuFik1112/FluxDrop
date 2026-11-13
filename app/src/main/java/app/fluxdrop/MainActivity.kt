package app.fluxdrop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.fluxdrop.core.Diag
import app.fluxdrop.ui.AppRoot
import app.fluxdrop.ui.AppViewModel
import app.fluxdrop.ui.FluxTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.log("ui", "MainActivity.onCreate")
        enableEdgeToEdge()
        setContent {
            FluxTheme {
                AppRoot(viewModel)
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SEND_CLIPBOARD, false) != true) return
        intent.removeExtra(EXTRA_SEND_CLIPBOARD)
        // Окно ещё не получило фокус — читать буфер обмена сразу нельзя.
        window.decorView.postDelayed({ viewModel.sendClipboardNow() }, 350)
    }

    companion object {
        const val EXTRA_SEND_CLIPBOARD = "send_clipboard"

        fun clipboardIntent(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SEND_CLIPBOARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
