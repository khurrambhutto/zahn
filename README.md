# Chota

A personal to-do and habit assistant for Android that runs small language models **entirely on the phone**. You talk to it ("remind me to call mom tomorrow at 6"), and the model calls tools that change your tasks and habits.

Two inference runtimes sit behind one interface:

| Runtime | File type | Runs on | Good for |
|---|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | `.gguf` | CPU (ARM-optimized) | Almost any model on Hugging Face, your own fine-tunes |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | `.litertlm` | CPU, GPU, NPU | Gemma / FunctionGemma / Qwen exports from Google's tooling |

The app picks the runtime from the file extension.

## Project layout

```
llm/core      Pure Kotlin. The engine interface (LlmEngine, ChatSession), tool specs, and the Agent tool loop.
llm/litert    LiteRtEngine: wraps LiteRT-LM's Kotlin API.
llm/llamacpp  LlamaCppEngine + a JNI bridge (src/main/cpp/chota_llama.cpp) to llama.cpp.
app           Compose UI, Room database, the assistant's tools, model downloads.
```

How a message flows:

```
ChatViewModel ─▶ Agent.run(text) ─▶ ChatSession.send(...)      (LiteRT-LM or llama.cpp)
                     │                   │
                     │                   └─ streams text, ends with tool calls or a final answer
                     ├─▶ ToolRegistry.execute(call)            (validates + coerces arguments)
                     │      └─▶ AssistantTools ─▶ Room         (add_task, complete_task, log_habit, ...)
                     └─ sends tool results back, repeats until the model answers in plain text
```

Both engines turn off their own tool handling and leave tool execution to `Agent`, so tool behaviour is identical whichever runtime is active.

### Design choices for small models

* **The model passes the user's words; Kotlin does the hard parts.** `due: "tomorrow 5pm"` is parsed by `DateParser`, and `task: "milk"` is resolved to "Buy milk" by `NameMatcher`. A 270M model can't do date math or look up ids reliably.
* **Errors go back to the model** as tool results (`"No task matches 'dentist'"`, `"Missing required argument 'title'"`), so it can retry or ask a question.
* **Arguments are coerced** (`"2"` → `2`, `"HIGH"` → `"high"`) before tools see them.
* **llama.cpp uses the model's own chat template** (Jinja, embedded in the GGUF) and llama.cpp's tool-call parser. Tool calls are grammar-constrained, which makes small models far more reliable. Qwen, Llama, Gemma, LFM2 etc. work without per-model code.
* **KV-cache reuse**: each turn only processes the new tokens, not the whole conversation.

## Build

Requirements: JDK 17, Android SDK with platform 37, NDK `29.0.14206865` and CMake `3.31.6` (`sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`).

```bash
./gradlew :app:assembleDebug                         # arm64 + x86_64 (emulator)
./gradlew :app:assembleDebug -Pchota.abis=arm64-v8a  # phones only, builds ~2x faster
./gradlew test                                       # unit tests
```

The first build downloads the pinned llama.cpp release (`chota.llamaCppVersion` in `gradle.properties`, checksum-verified) and compiles it, which takes a few minutes. To upgrade llama.cpp, change the version and checksum there.

## Using models

Open the **Models** tab:

* **Get a model**: curated models that support tool calling (FunctionGemma 270M, Qwen3.5 0.8B, LFM2 1.2B Tool, Gemma 4 E2B, ...).
* **Add your own**: paste a Hugging Face file link (`https://huggingface.co/<user>/<repo>/resolve/main/<file>.gguf`; `/blob/` links work too), or import a file from the phone.
* Gated models need a Hugging Face token (Models → Settings).

Tap a model to load it. Only one model is in memory at a time.

## Fine-tuning your own model

The tools and system prompt are the contract your model learns:

* Tool definitions: `app/src/main/kotlin/com/chotaai/app/ai/AssistantTools.kt`
* System prompt: `app/src/main/kotlin/com/chotaai/app/ai/SystemPrompt.kt`

Once you start fine-tuning, avoid renaming tools or parameters. A suggested workflow:

1. Generate a dataset (2–5k examples) of conversations in the OpenAI messages format: the system prompt above, a user request, the assistant's tool call(s), tool results, and the final reply. Include requests that need no tool, ambiguous ones (where the right answer is a question), and multi-call ones.
2. Fine-tune with Hugging Face TRL or Unsloth (LoRA is fine; a 270M model can be fully fine-tuned on a free Colab GPU), computing loss only on assistant turns.
3. Export:
   * **GGUF** for llama.cpp: `python convert_hf_to_gguf.py <model_dir> --outtype q8_0` (from the llama.cpp repo). Make sure the chat template with tool support is in `tokenizer_config.json`, since it gets embedded in the GGUF.
   * **.litertlm** for LiteRT-LM: `litert-torch export_hf --model <hf_repo_or_dir> --output_dir out/` (see Google's [convert & run guide](https://developers.google.com/edge/litert-lm/tutorials/convert-and-run)).
4. Upload the file to Hugging Face and paste the link in the app, or copy it to the phone and import it.

## Not done yet

* Long downloads run in the app process. Moving them to WorkManager would let them survive the app being killed.
* llama.cpp runs on the CPU only. ggml has OpenCL (Adreno) and Vulkan backends that could be enabled later.
* Chat history isn't saved between app launches.
* No reminders/notifications for due tasks yet (WorkManager + notifications would be the next step).
* No undo for actions the assistant takes.
