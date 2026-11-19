package com.offerpilot.core.i18n

/** UI / speech language. English is the default; Chinese can be chosen in settings. */
enum class Lang(val nativeName: String) {
    EN("English"),
    ZH("中文");

    /** Picks the string for this language. */
    fun t(en: String, zh: String): String = if (this == ZH) zh else en
}
