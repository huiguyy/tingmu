package com.zcode.tingmu

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** 听幕·屏：关键词哨兵（KWS）构建 + 汉字触发词 → 音素序列转换。
 *
 *  KWS 模型的 tokens 是带调拼音音素（声母+韵母），keywords.txt 每行形如
 *  `x ià y ī zh àn @下一站`；该文件由 scripts/gen_kws_keywords.py 在构建期生成，
 *  运行时自定义触发词走 HanziToPinyin/PinyinTable 两张自动生成的字表。 */
object KwsEngine {
    private const val TAG = "TingmuScreen"
    const val MODEL_DIR = "kws-wenetspeech-mobile"

    /** 单个汉字词 → keywords 行；含非汉字/字表未收录的字返回 null。 */
    fun toKeywordLine(word: String): String? {
        val w = word.trim()
        if (w.isEmpty()) return null
        val sb = StringBuilder()
        for (ch in w) {
            if (ch == ' ') continue
            val syl = HanziToPinyin.MAP[ch.toString()] ?: return null
            val parts = PinyinTable.MAP[syl] ?: return null
            for (p in parts) {
                if (p.isNotEmpty()) sb.append(p).append(' ')
            }
        }
        if (sb.isBlank()) return null
        return sb.toString().trim() + " @" + w
    }

    /** 多个词 → createStream 运行时 keywords 串（'/' 分隔）；任一词转换失败返回 null。 */
    fun toRuntimeKeywords(words: List<String>): String? {
        if (words.isEmpty()) return null
        val lines = words.map { toKeywordLine(it) }
        if (lines.any { it == null }) return null
        return lines.filterNotNull().joinToString("/")
    }

    /** 构建 KeywordSpotter（文件版，keywords 用模型目录里构建期生成的 keywords.txt）。 */
    fun buildSpotter(dir: File): KeywordSpotter {
        fun pick(base: String): String {
            val int8 = dir.listFiles()?.firstOrNull { it.name.startsWith(base) && it.name.endsWith(".int8.onnx") }
            val plain = dir.listFiles()?.firstOrNull { it.name.startsWith(base) && it.name.endsWith(".onnx") && !it.name.endsWith(".int8.onnx") }
            return (int8 ?: plain ?: throw IllegalStateException("KWS 缺 $base.onnx")).absolutePath
        }
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = pick("encoder"),
                    decoder = pick("decoder"),
                    joiner = pick("joiner"),
                ),
                tokens = dir.resolve("tokens.txt").absolutePath,
                numThreads = 1,
                modelType = "zipformer2",
            ),
            maxActivePaths = 4,
            keywordsFile = dir.resolve("keywords.txt").absolutePath,
            keywordsScore = 2.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2,
        )
        return try {
            KeywordSpotter(null, config)
        } catch (e: Exception) {
            Log.e(TAG, "buildSpotter fail", e)
            throw e
        }
    }
}
