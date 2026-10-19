package com.chotaai.app.models

import com.chotaai.llm.EngineType

data class CatalogModel(
    val name: String,
    val description: String,
    val repo: String,
    val file: String,
    val sizeBytes: Long,
) {
    val url get() = "https://huggingface.co/$repo/resolve/main/$file"
    val engine get() = EngineType.forFileName(file)!!
}

/**
 * Starting points with tool calling, checked to exist on Hugging Face (October 2026).
 * Your own fine-tunes go in through "Add from URL" or "Import file".
 */
object ModelCatalog {
    val models = listOf(
        CatalogModel(
            name = "FunctionGemma 270M",
            description = "Tiny, built only for function calling. The base to fine-tune on your own tools.",
            repo = "unsloth/functiongemma-270m-it-GGUF",
            file = "functiongemma-270m-it-Q8_0.gguf",
            sizeBytes = 291_558_624,
        ),
        CatalogModel(
            name = "Qwen3.5 0.8B",
            description = "Small general model with tool calling. Good all-rounder for phones.",
            repo = "unsloth/Qwen3.5-0.8B-GGUF",
            file = "Qwen3.5-0.8B-Q4_K_M.gguf",
            sizeBytes = 532_517_120,
        ),
        CatalogModel(
            name = "LFM2 1.2B Tool",
            description = "Liquid AI model tuned specifically for tool use. Fast on CPU.",
            repo = "LiquidAI/LFM2-1.2B-Tool-GGUF",
            file = "LFM2-1.2B-Tool-Q4_K_M.gguf",
            sizeBytes = 730_894_048,
        ),
        CatalogModel(
            name = "LFM2.5 1.2B Instruct",
            description = "Newer Liquid AI chat model with tool calling.",
            repo = "LiquidAI/LFM2.5-1.2B-Instruct-GGUF",
            file = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            sizeBytes = 730_895_168,
        ),
        CatalogModel(
            name = "Qwen3 0.6B (LiteRT)",
            description = "Runs on LiteRT-LM. Try the GPU accelerator in settings.",
            repo = "litert-community/Qwen3-0.6B",
            file = "Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264,
        ),
        CatalogModel(
            name = "Gemma 4 E2B (LiteRT)",
            description = "Google's on-device model with native function calling. Needs ~6 GB RAM.",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            file = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712,
        ),
    )
}
