package dev.towertools.mediatranscriber

data class ModelRecommendation(
    val model: String,
    val fileSize: String,
    val speed: String,
    val quality: String,
    val suggestedVram: String,
    val suitableFor: String,
)

object DependencyGuidance {
    const val ffmpegDownloadUrl = "https://www.gyan.dev/ffmpeg/builds/"
    const val whisperCliDownloadUrl = "https://github.com/ggml-org/whisper.cpp/releases/latest"
    const val modelDownloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/tree/main"

    val models = listOf(
        ModelRecommendation("Tiny", "75 MiB", "最快", "基础", "1 GB+", "快速试用、配置较低"),
        ModelRecommendation("Base", "142 MiB", "很快", "一般", "1 GB+", "短音频、速度优先"),
        ModelRecommendation("Small", "466 MiB", "较快", "良好", "2 GB+", "日常使用、低显存设备"),
        ModelRecommendation("Medium", "1.5 GiB", "中等", "很好", "4 GB+", "质量优先、设备性能适中"),
        ModelRecommendation("Large V3 Turbo", "1.5 GiB", "较快", "优秀", "4 GB+", "大多数用户的质量/速度平衡"),
        ModelRecommendation("Large V3", "2.9 GiB", "较慢", "最高", "6 GB+", "复杂语音、多语言、准确率优先"),
    )
}
