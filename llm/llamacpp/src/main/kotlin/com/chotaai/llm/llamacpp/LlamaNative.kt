package com.chotaai.llm.llamacpp

/**
 * JNI surface of `chota_llama.cpp`. Not meant to be used directly; see [LlamaCppEngine].
 *
 * Text crosses JNI as UTF-8 byte arrays because JNI strings can't carry emoji.
 */
object LlamaNative {
    init {
        System.loadLibrary("chota_llama")
    }

    fun interface DeltaCallback {
        /** [kind] is [DELTA_CONTENT] or [DELTA_REASONING]. Return false to stop generating. */
        fun onDelta(kind: Int, utf8: ByteArray): Boolean
    }

    const val DELTA_CONTENT = 0
    const val DELTA_REASONING = 1

    external fun backendInit(nativeLibDir: String)

    /** Returns a handle; throws IllegalStateException on failure. */
    external fun loadModel(path: String, contextSize: Int, threads: Int): Long

    external fun freeModel(handle: Long)

    external fun cancel(handle: Long)

    external fun modelInfo(handle: Long): String

    /** Blocks until generation ends. Returns the result JSON as UTF-8. */
    external fun generate(handle: Long, requestJson: String, callback: DeltaCallback): ByteArray
}
