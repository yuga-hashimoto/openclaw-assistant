package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TTSの共通ユーティリティ
 */
object TTSUtils {
    private const val TAG = "TTSUtils"
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    /**
     * ロケールと高品質な音声のセットアップ
     */
    fun setupVoice(tts: TextToSpeech?, speed: Float) {
        val currentLocale = Locale.getDefault()
        Log.e(TAG, "Current system locale: $currentLocale")

        // エンジン情報をログ出力
        try {
            val engine = tts?.defaultEngine
            Log.e(TAG, "Using TTS Engine: $engine")
        } catch (e: Exception) {
            Log.e(TAG, "Could not get default engine: ${e.message}")
        }

        // システムロケールの設定を試みる
        val result = tts?.setLanguage(currentLocale)
        Log.e(TAG, "setLanguage($currentLocale) result=$result")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // デフォルトが失敗した場合は英語(US)にフォールバック
            val fallbackResult = tts?.setLanguage(Locale.US)
            Log.e(TAG, "Fallback setLanguage(Locale.US) result=$fallbackResult")
        }

        // 高品質な音声（ネットワーク不要のもの優先）を選択
        try {
            val targetLang = tts?.language?.language
            val voices = tts?.voices
            Log.e(TAG, "Available voices count: ${voices?.size ?: 0}")
            
            val bestVoice = voices?.filter { it.locale.language == targetLang }
                ?.firstOrNull { !it.isNetworkConnectionRequired }
                ?: voices?.firstOrNull { it.locale.language == targetLang }

            if (bestVoice != null) {
                tts?.voice = bestVoice
                Log.e(TAG, "Selected voice: ${bestVoice.name} (${bestVoice.locale})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting voice: ${e.message}")
        }

        applyUserConfig(tts, speed)
    }

    /**
     * ユーザー設定の速度を適用する
     */
    fun applyUserConfig(tts: TextToSpeech?, speed: Float) {
        if (tts == null) return
        tts.setSpeechRate(speed)
        tts.setPitch(1.0f)
    }

    /**
     * Markdownフォーマットを除去してTTS向けのプレーンテキストに変換する
     */
    fun stripMarkdownForSpeech(text: String): String {
        var result = text
        // コードブロック (```...```) -> 中身だけ残す
        // 言語指定がある場合 (```kotlin ...) も考慮して、バッククォートと最初の行(言語名)を削除などの調整が必要だが、
        // 単純化してバッククォートだけ削除し、中身は読むようにする。または「コードブロック」と読み上げさせるか？
        // ユーザー要望「記号以外は読み上げる」に従い、中身は残す。
        result = result.replace(Regex("```.*\\n?"), "") // 開始の ```language を削除
        result = result.replace(Regex("```"), "")       // 終了の ``` を削除

        // ヘッダー (# ## ### 等) を除去
        result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        
        // ボールド/イタリック (**text**, *text*, __text__, _text_)
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        result = result.replace(Regex("\\*([^*]+)\\*"), "$1")
        result = result.replace(Regex("__([^_]+)__"), "$1")
        result = result.replace(Regex("_([^_]+)_"), "$1")
        
        // インラインコード (`code`)
        result = result.replace(Regex("`([^`]+)`"), "$1")
        
        // リンク [text](url) → text
        result = result.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        
        // 画像 ![alt](url) → alt
        result = result.replace(Regex("!\\[([^\\]]*)]\\([^)]+\\)"), "$1")
        
        // 水平線 (---, ***) -> 削除
        result = result.replace(Regex("^[-*_]{3,}$", RegexOption.MULTILINE), "")
        
        // ブロッククオート (>)
        result = result.replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
        
        // 箇条書きマーカー (-, *, +)
        result = result.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        
        // 番号付きリストマーカー (1., 2., 等) - これは読んでもいいかもしれないが、数字だけ残す
        // result = result.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        
        // 連続改行を整理
        result = result.replace(Regex("\n{3,}"), "\n\n")
        
        return result.trim()
    }
}
