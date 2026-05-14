package org.lastidea.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordVault {
    private static final int HASH_BITS = 256;
    private static final int ITERATIONS = 50000;
    private static final int SALT_BYTES = 16;
    private static final String KEY_HASH = "password_hash";
    private static final String KEY_SALT = "password_salt";
    private static final String PREFS = "last_idea_lock";

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    PasswordVault(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean hasPassword() {
        return prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT);
    }

    void clearPassword() {
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).apply();
    }

    void setPassword(String password) {
        if (TextUtils.isEmpty(password)) {
            clearPassword();
            return;
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = hash(password, salt);
        prefs.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .apply();
    }

    boolean verify(String password) {
        if (!hasPassword() || TextUtils.isEmpty(password)) {
            return false;
        }
        byte[] salt = Base64.decode(prefs.getString(KEY_SALT, ""), Base64.NO_WRAP);
        byte[] expected = Base64.decode(prefs.getString(KEY_HASH, ""), Base64.NO_WRAP);
        byte[] actual = hash(password, salt);
        if (actual.length != expected.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < actual.length; i++) {
            diff |= actual[i] ^ expected[i];
        }
        return diff == 0;
    }

    private byte[] hash(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash password", e);
        }
    }
}
