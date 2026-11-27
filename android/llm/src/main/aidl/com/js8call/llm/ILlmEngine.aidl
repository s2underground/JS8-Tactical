package com.js8call.llm;

import com.js8call.llm.ITokenListener;

/**
 * The AI engine in its isolated process (LlmService). Handles are only meaningful inside that
 * process; the service checks every one it is given.
 */
interface ILlmEngine {
    /** Empty if the native engine loaded, else why not. */
    String engineStatus();
    /** Loads the GGUF model read through [model]; 0 if it can't be loaded. */
    long load(in ParcelFileDescriptor model, int nCtx, int nThreads);
    double loadMs(long handle);
    String generate(long handle, String system, String user, int maxTokens, float temperature, ITokenListener listener);
    long[] stats(long handle);
    String error(long handle);
    void free(long handle);
}
