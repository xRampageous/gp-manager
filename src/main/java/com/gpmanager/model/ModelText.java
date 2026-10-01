package com.gpmanager;
import java.util.Locale;
/** Display-name defaults and phrase matching shared by the model and the classifiers. */
class ModelText {
/** Blank activity names fall back to the General bucket. */
static String normalizeActivity(String value) {
return nonBlank(value, "General");
}

static String orEmpty(String text) {
return text == null ? "" : text;
}

static boolean empty(String text) {
return text == null || text.isEmpty();
}

static boolean empty(java.util.Collection<?> values) {
return values == null || values.isEmpty();
}

static boolean empty(java.util.Map<?, ?> values) {
return values == null || values.isEmpty();
}

static boolean blank(String text) {
return text == null || text.trim().isEmpty();
}

/** The trimmed text, or the fallback when it is blank. */
static String nonBlank(String text, String fallback) {
return blank(text) ? fallback : text.trim();
}

/** Trimmed lower-case text, or null when blank. */
static String norm(String value) {
if (value == null) return null;
String key = value.trim().toLowerCase(Locale.ROOT);
return key.isEmpty() ? null : key;
}

/** True when the text contains any of the words. */
static boolean has(String text, String... words) {
for (String word : words) {
if (text.contains(word)) return true;
}
return false;
}

static boolean containsAll(String text, String... words) {
for (String word : words) {
if (!text.contains(word)) return false;
}
return true;
}
}
