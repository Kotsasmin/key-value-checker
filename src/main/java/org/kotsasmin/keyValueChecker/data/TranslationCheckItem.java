package org.kotsasmin.keyValueChecker.data;

public class TranslationCheckItem {
    private final String key;
    private final String name;
    private final boolean required; // false = blacklist (den prepei na ginei translate), true = whitelist (prepei na ginei translate)

    public TranslationCheckItem(String key, String name, boolean required) {
        this.key = key;
        this.name = name != null ? name : key;
        this.required = required;
    }

    public TranslationCheckItem(String key, boolean required) {
        this(key, key, required);
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public boolean isRequired() {
        return required;
    }
}
