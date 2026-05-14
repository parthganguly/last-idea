package org.lastidea.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

final class AppSettings {
    static final String FONT_DEFAULT = "default";
    static final String FONT_DEATH = "death";
    static final String FONT_NEAR = "near";
    static final String FONT_RYUK = "ryuk";
    static final String FONT_SERIF = "serif";
    static final String FONT_MONO = "mono";
    static final String FONT_SYSTEM = "system";

    private static final String PREFS = "last_idea_settings";
    private static final String KEY_AUTO_LOCK = "auto_lock";
    private static final String KEY_FONT = "font";
    private static final String KEY_LAST_PAUSE = "last_pause";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_SHOW_GUIDE = "show_guide";
    private static final String KEY_STORAGE_MODE = "storage_mode";
    private static final String KEY_STORAGE_READY = "storage_ready";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_CURRENT_CATEGORY = "current_category";
    private static final String STORAGE_DRIVE = "drive";
    private static final String STORAGE_LOCAL = "local";

    private final Context context;
    private final SharedPreferences prefs;
    private Typeface deathTypeface;
    private Typeface nearTypeface;
    private Typeface ryukTypeface;

    AppSettings(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isAutoLockEnabled() {
        return prefs.getBoolean(KEY_AUTO_LOCK, false);
    }

    void setAutoLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_LOCK, enabled).apply();
    }

    boolean isShowGuideEnabled() {
        return prefs.getBoolean(KEY_SHOW_GUIDE, true);
    }

    void setShowGuideEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHOW_GUIDE, enabled).apply();
    }

    boolean isSeeded() {
        return prefs.getBoolean(KEY_SEEDED, false);
    }

    void setSeeded() {
        prefs.edit().putBoolean(KEY_SEEDED, true).apply();
    }

    void clearSeeded() {
        prefs.edit().putBoolean(KEY_SEEDED, false).apply();
    }

    boolean isStorageReady() {
        return prefs.getBoolean(KEY_STORAGE_READY, false);
    }

    boolean isDriveStorage() {
        return STORAGE_DRIVE.equals(prefs.getString(KEY_STORAGE_MODE, STORAGE_LOCAL));
    }

    void useLocalStorage() {
        prefs.edit()
                .putBoolean(KEY_STORAGE_READY, true)
                .putString(KEY_STORAGE_MODE, STORAGE_LOCAL)
                .remove(KEY_TREE_URI)
                .apply();
    }

    void useDriveStorage(String treeUri) {
        prefs.edit()
                .putBoolean(KEY_STORAGE_READY, true)
                .putString(KEY_STORAGE_MODE, STORAGE_DRIVE)
                .putString(KEY_TREE_URI, treeUri)
                .putString(KEY_CURRENT_CATEGORY, "")
                .apply();
    }

    String getTreeUri() {
        return prefs.getString(KEY_TREE_URI, "");
    }

    String getCurrentCategory() {
        return prefs.getString(KEY_CURRENT_CATEGORY, "");
    }

    void setCurrentCategory(String category) {
        prefs.edit().putString(KEY_CURRENT_CATEGORY, category == null ? "" : category).apply();
    }

    long getLastPauseTime() {
        return prefs.getLong(KEY_LAST_PAUSE, 0L);
    }

    void setLastPauseTime(long time) {
        prefs.edit().putLong(KEY_LAST_PAUSE, time).apply();
    }

    String getFont() {
        return prefs.getString(KEY_FONT, FONT_DEFAULT);
    }

    void setFont(String font) {
        prefs.edit().putString(KEY_FONT, font).apply();
    }

    Typeface getTypeface() {
        return getTypeface(getFont());
    }

    Typeface getTypeface(String font) {
        if (FONT_DEFAULT.equals(font) || FONT_SYSTEM.equals(font)) {
            return Typeface.SANS_SERIF;
        }
        if (FONT_DEATH.equals(font)) {
            return assetTypeface("fonts/death.ttf", Typeface.SANS_SERIF);
        }
        if (FONT_NEAR.equals(font)) {
            return assetTypeface("fonts/near.ttf", Typeface.SERIF);
        }
        if (FONT_RYUK.equals(font)) {
            return assetTypeface("fonts/ryuk.ttf", Typeface.SERIF);
        }
        if (FONT_MONO.equals(font)) {
            return Typeface.MONOSPACE;
        }
        if (FONT_SERIF.equals(font)) {
            return Typeface.SERIF;
        }
        return Typeface.SANS_SERIF;
    }

    private Typeface assetTypeface(String path, Typeface fallback) {
        try {
            if ("fonts/death.ttf".equals(path)) {
                if (deathTypeface == null) {
                    deathTypeface = Typeface.createFromAsset(context.getAssets(), path);
                }
                return deathTypeface;
            }
            if ("fonts/near.ttf".equals(path)) {
                if (nearTypeface == null) {
                    nearTypeface = Typeface.createFromAsset(context.getAssets(), path);
                }
                return nearTypeface;
            }
            if ("fonts/ryuk.ttf".equals(path)) {
                if (ryukTypeface == null) {
                    ryukTypeface = Typeface.createFromAsset(context.getAssets(), path);
                }
                return ryukTypeface;
            }
            return Typeface.createFromAsset(context.getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
