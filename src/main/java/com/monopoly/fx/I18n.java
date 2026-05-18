package com.monopoly.fx;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class I18n {

    private static final String BUNDLE_BASE = "com.monopoly.fx.i18n.messages";
    private static Locale currentLocale = Locale.CHINESE;
    private static ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_BASE, currentLocale);

    private I18n() {}

    public static void setLocale(Locale locale) {
        currentLocale = locale;
        bundle = ResourceBundle.getBundle(BUNDLE_BASE, locale);
    }

    public static Locale getLocale() {
        return currentLocale;
    }

    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    public static String get(String key, Object... args) {
        String pattern = get(key);
        if (pattern.startsWith("!") && pattern.endsWith("!")) {
            return pattern;
        }
        return MessageFormat.format(pattern, args);
    }

    public static boolean isChinese() {
        return Locale.CHINESE.getLanguage().equals(currentLocale.getLanguage());
    }
}
