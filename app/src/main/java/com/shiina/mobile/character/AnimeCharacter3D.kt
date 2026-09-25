package com.shiina.mobile.character

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.shiina.mobile.debug.AppDebugServer
import android.graphics.PixelFormat
import com.google.android.filament.View
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import dev.romainguy.kotlin.math.Float3

/**
 * CHAR-3D: the anime character rendered as a real 3D model via Sceneview
 * (Filament GLB) instead of the flat circle face. The model lives at
 * assets/shiina_character.glb (prepared in Blender: ~18k tris, textures
 * baked, feet on origin), copied to filesDir for the loader.
 *
 * Filament requires every thread that calls its native API to be
 * "adopted" by the Engine's thread manager — crashing with "This thread
 * has not been adopted" happens when loading runs on Dispatchers.IO.
 * So the asset file is copied on IO, but createModelInstance + node
 * creation run on the main thread (SceneView's thread) via sv.post.
 */
@Composable
fun AnimeCharacter3D(
    modifier: Modifier = Modifier,
    mood: String,
    onFallback: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val sv = SceneView(
                context = ctx,
                isOpaque = false,
            ).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                scene.skybox = null
                runCatching {
                    view.blendMode = View.BlendMode.TRANSLUCENT
                    renderer.clearOptions = renderer.clearOptions.apply {
                        clear = true
                        clearColor = floatArrayOf(0f, 0f, 0f, 0f)
                    }
                }
                cameraNode.position = Float3(0f, 0f, 1.55f)
                mainLightNode?.apply {
                    lightDirection = Float3(0f, -0.4f, -1f)
                    intensity = 100_000f
                }
            }
            loadModel(ctx, sv, onFallback)
            sv
        },
        onRelease = { sv ->
            runCatching { sv.destroy() }
        },
    )
}

private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private const val ASSET = "shiina_character.glb"

private fun loadModel(ctx: Context, sv: SceneView, onFallback: (() -> Unit)?) {
    loadScope.launch {
        // Step 1 (IO thread): copy GLB out of assets without heap-allocating full byte arrays
        val file = runCatching {
            val f = File(ctx.filesDir, ASSET)
            val sp = ctx.getSharedPreferences("shiina_assets", Context.MODE_PRIVATE)
            val packageInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                }
            }.getOrNull()
            val appVersionCode = packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            } ?: 0L
            val lastCopiedVersion = sp.getLong("asset_version_$ASSET", -1L)

            if (!f.exists() || f.length() == 0L || lastCopiedVersion != appVersionCode) {
                ctx.assets.open(ASSET).use { input ->
                    f.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                sp.edit().putLong("asset_version_$ASSET", appVersionCode).apply()
                AppDebugServer.log("CHARACTER", "Asset cached: wrote ${f.length() / 1024}KB to ${f.name}")
            }
            f
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "3D asset copy failed: ${e.message}")
            onFallback?.invoke()
        }.getOrNull() ?: return@launch

        // Step 2 (main thread): Filament calls — engine has adopted this thread.
        sv.post {
            runCatching {
                val instance = sv.modelLoader.createModelInstance(file) { _ -> null }
                val node = ModelNode(instance).apply {
                    scaleToUnitCube(0.80f)
                    val c = center
                    val s = scale
                    position = Float3(-c.x * s.x, -c.y * s.y, -c.z * s.z)
                    if (animationCount > 0) {
                        playAnimation(0)
                    }
                }
                sv.addChildNode(node)
                AppDebugServer.log("CHARACTER", "3D model loaded (${file.length() / 1024}KB) with ${node.animationCount} anims, pos=${node.position}, scale=${node.scale}")
            }.onFailure { e ->
                AppDebugServer.log("ERROR", "3D model load failed: ${e.message}")
                onFallback?.invoke()
            }
        }
    }
}