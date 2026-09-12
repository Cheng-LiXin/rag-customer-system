package com.rag.retrieve;

/**
 * 单次请求的检索模式覆盖（ThreadLocal）。
 *
 * <p>为什么用 ThreadLocal 而不是往 {@code ChatService.ask/stream} 上加参数：
 * 那会给接口签名再加一个「只为评估存在」的参数，并一路透传到 `generate()`。
 * 而问答的实际工作（检索与生成）都在 **servlet 请求线程**上同步完成
 * —— `stream()` 返回 Flux 之前答案已经算完了 —— 所以在线程上挂一个覆盖值就够，
 * 不需要动任何方法签名。
 *
 * <p>生产环境默认禁用：只有配置 {@code rag.eval.mode-override-enabled=true} 时
 * {@code ChatController} 才会从 {@code X-Retrieval-Mode} 头读值并设置。
 */
public final class RetrievalModeContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RetrievalModeContext() {
    }

    public static void set(String mode) {
        HOLDER.set(mode);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
