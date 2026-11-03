package com.zcode.tingmu

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.max

/** 引擎侧：模型发现 / 拷贝 / 构建（只做纯逻辑，不碰视图）。 */
object AsrEngine {
    private const val TAG = "TingmuSelfTest"
    private const val ASSET_MODELS = "models"
    private const val DEST = "models"

    /** Unpack bundled models (and VAD) into filesDir. Idempotent. */
    fun ensureBundled(ctx: Context): List<String> {
        val names = try { ctx.assets.list(ASSET_MODELS)?.toList() ?: emptyList() } catch (e: Exception) {
            Log.e(TAG, "list assets failed", e); return emptyList()
        }
        val done = mutableListOf<String>()
        for (name in names) {
            val entries = try { ctx.assets.list("$ASSET_MODELS/$name")?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
            // 跳过模型目录里的子目录（如 x-asr 自带的 test_wavs/）——本机 AssetManager 对文件 list 返回空/null，对有内容的目录返回非空
            val files = entries.filter { f ->
                try { ctx.assets.list("$ASSET_MODELS/$name/$f").isNullOrEmpty() } catch (_: Exception) { true }
            }
            if (files.isEmpty()) continue // silero_vad.onnx 是文件不是目录
            val dest = File(ctx.filesDir, "$DEST/$name")
            val complete = dest.resolve("tokens.txt").isFile && files.all { dest.resolve(it).isFile }
            if (!complete) {
                dest.mkdirs()
                for (f in files) {
                    ctx.assets.open("$ASSET_MODELS/$name/$f").use { input ->
                        dest.resolve(f).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            done += name
        }
        return done
    }
    fun discover(ctx: Context): List<ModelEntry> {
        ensureBundled(ctx)
        val out = mutableListOf<ModelEntry>()
        // bundled (copied out of assets)
        File(ctx.filesDir, DEST).listFiles()?.filter { it.isDirectory }?.forEach {
            out += classify(it, "\u5185\u7f6e\u00b7")
        }
        // side-loaded: app external dir (no permission needed) and legacy sdcard path
        getExternalModelsDir(ctx)?.listFiles()?.filter { it.isDirectory }?.forEach {
            out += classify(it, "\u672c\u673a\u00b7")
        }
        try {
            File("/sdcard/tingmu/models").listFiles()?.filter { it.isDirectory }?.forEach {
                out += classify(it, "\u672c\u673a\u00b7")
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** External files dir, created lazily. */
    fun getExternalModelsDir(ctx: Context): File? {
        val base = ctx.getExternalFilesDir(null) ?: return null
        return File(base, "models")
    }

    private fun classify(dir: File, labelPrefix: String): List<ModelEntry> {
        val entries = mutableListOf<ModelEntry>()
        val hasCtc = dir.resolve("model.int8.onnx").isFile || dir.resolve("model.onnx").isFile
        val hasTransducer = dir.listFiles()?.any { it.name.startsWith("encoder") } == true
        // SenseVoice 等离线模型同样是 model.int8.onnx 布局，靠目录名（含 sense）区分：
        // 离线模型没有流式接口，走 VAD 切段 + 整句识别
        val isSense = dir.name.contains("sense", ignoreCase = true)
        if (hasCtc && isSense) {
            entries += ModelEntry(dir.name + "-off", dir, false, "$labelPrefix${dir.name}", isOffline = true)
        } else if (hasCtc) {
            entries += ModelEntry(dir.name, dir, true, "$labelPrefix${dir.name}")
        }
        if (hasTransducer) entries += ModelEntry(dir.name + "-td", dir, false, "$labelPrefix${dir.name}")
        return entries
    }

    /** 加载离线整句模型（当前仅 SenseVoice）。耗时数秒，必须在工作线程调用。 */
    fun buildOfflineRecognizer(m: ModelEntry): OfflineRecognizer {
        val dir = m.dir
        val model = (dir.resolve("model.int8.onnx").takeIf { it.isFile } ?: dir.resolve("model.onnx")).absolutePath
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model,
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = dir.resolve("tokens.txt").absolutePath,
                numThreads = 2,
            ),
        )
        return OfflineRecognizer(null, config)
    }

    /** 加载识别器；耗时数秒，必须在工作线程调用。 */
    fun buildRecognizer(m: ModelEntry): OnlineRecognizer {
        val tokens = m.dir.resolve("tokens.txt").absolutePath
        val modelConfig = if (m.isCtc) {
            val model = (m.dir.resolve("model.int8.onnx").takeIf { it.isFile }
                ?: m.dir.resolve("model.onnx")).absolutePath
            OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = model),
                tokens = tokens,
                numThreads = 2,
            )
        } else {
            fun pick(base: String): String {
                val int8 = m.dir.listFiles()?.firstOrNull { it.name == "$base.int8.onnx" }
                val plain = m.dir.listFiles()?.firstOrNull { it.name == "$base.onnx" }
                return (int8 ?: plain ?: throw IllegalStateException("缺 $base.onnx")).absolutePath
            }
            // 目录里带 bpe.model 的是 zipformer2 + BPE 词表模型（如 x-asr 中英双语包）
            val bpe = m.dir.resolve("bpe.model").takeIf { it.isFile }?.absolutePath
            OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = pick("encoder"),
                    decoder = pick("decoder"),
                    joiner = pick("joiner"),
                ),
                tokens = tokens,
                numThreads = 2,
                modelType = if (bpe != null) "zipformer2" else "zipformer",
                bpeVocab = bpe ?: "",
            )
        }
        return OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(),
                modelConfig = modelConfig,
                enableEndpoint = true,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                decodingMethod = "greedy_search",
            ),
        )
    }

    fun buildVad(ctx: Context): Vad? {
        return try {
            val f = File(ctx.filesDir, "models/silero_vad.onnx")
            if (!f.isFile) {
                ctx.assets.open("models/silero_vad.onnx").use { input ->
                    f.parentFile?.mkdirs()
                    f.outputStream().use { input.copyTo(it) }
                }
            }
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(model = f.absolutePath),
                    sampleRate = 16000,
                    numThreads = 1,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "vad unavailable", e)
            null
        }
    }
}
