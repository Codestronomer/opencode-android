package ai.opencode.app

import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.user.AgentRevealGlow
import ai.opencode.feature.transcript.user.UserSurfaceCorners
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Debug-only golden diff for the shader ports (docs/android-12-support.md §10.2): each
 * effect draws twice — the AGSL pass and its GLSL port — side by side at identical inputs,
 * for a pixel diff between the two renders on one device. Started over adb; literals are
 * fine (developer-only screen).
 */
class ShaderLabActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenCodeAppTheme { ShaderLab() } }
    }
}

@Composable
private fun ShaderLab() {
    val dark = OpenCodeTheme.colors.isDark
    Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(16.dp)) {
        Text("Shader Lab", color = Color.White)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            RevealRow("reveal glow: AGSL (left) vs GL (right)", AgentRevealGlow.Mode.Agsl, dark, UserSurfaceCorners(top = true))
        }
        RevealRow("reveal glow: GL (left) vs GL (right, repeat)", AgentRevealGlow.Mode.Gl, dark, UserSurfaceCorners(top = true))
        RevealRow("reveal glow: AGSL vs GL (top-tight corner = orientation probe)", null, dark, UserSurfaceCorners(top = true))
    }
}

@Composable
private fun RevealRow(label: String, mode: AgentRevealGlow.Mode?, dark: Boolean, corners: UserSurfaceCorners = UserSurfaceCorners.round) {
    val left = AgentRevealGlow(mode ?: AgentRevealGlow.Mode.Agsl.takeIf { android.os.Build.VERSION.SDK_INT >= 33 } ?: AgentRevealGlow.Mode.Gl)
    val right = AgentRevealGlow(AgentRevealGlow.Mode.Gl)
    Text(label, color = Color(0xFF9AA4B0))
    Row {
        LabCell(left, dark, corners, Modifier.padding(end = 12.dp))
        LabCell(right, dark, corners)
    }
}

@Composable
private fun LabCell(glow: AgentRevealGlow, dark: Boolean, corners: UserSurfaceCorners, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(150.dp, 70.dp)
            .background(Color(0xFF1B2129)),
    ) {
        with(glow) { draw(1.0, corners, dark) }
    }
}
