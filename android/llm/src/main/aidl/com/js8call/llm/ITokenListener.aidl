package com.js8call.llm;

/** Called from the isolated AI process with each new piece of text; return false to stop. */
interface ITokenListener {
    boolean onToken(String piece);
}
