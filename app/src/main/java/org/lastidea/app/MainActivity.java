package org.lastidea.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private static final long AUTO_LOCK_DELAY_MS = 15000L;
    private static final long OPENING_MAX_MS = 1900L;
    private static final long SAVE_DELAY_MS = 1000L;
    private static final int PAGE_GUIDE = -2;
    private static final int PAGE_SETTINGS = -1;
    private static final int REQUEST_DRIVE_FOLDER = 1001;
    private static final String[] OPENING_WORDS = {
            "catch it",
            "before it fades",
            "write now"
    };
    private static final String TUTORIAL_MARKDOWN =
            "# Last Idea\n\n" +
            "Dump the thought before it cools.\n\n" +
            "- Write in Markdown.\n" +
            "- Swipe left and right between ideas.\n" +
            "- Tap `Last Idea` at the top to open the index.\n" +
            "- Every idea is saved as a plain `.md` file.\n\n" +
            "Autosave is already on.";
    private static final String GUIDE_MARKDOWN =
            "# Last Idea guide\n\n" +
            "Last Idea is a local-first Markdown idea catcher built for quick capture.\n\n" +
            "## Markdown\n\n" +
            "Use headings, lists, links, code notes, quotes, or any plain text you like. " +
            "Each idea page is stored as a separate `.md` file.\n\n" +
            "## Navigation\n\n" +
            "- Swipe left to move to the next page.\n" +
            "- Swipe right to move back.\n" +
            "- Tap `Last Idea` at the top to open the index.\n" +
            "- Create folders from the index to categorize ideas.\n\n" +
            "## Files\n\n" +
            "Files can live locally or in a folder you choose through Android's system picker, including Google Drive.";

    private enum Screen {
        LOGIN,
        OPENING,
        INDEX,
        PAGE,
        GUIDE,
        SETTINGS
    }

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private AppSettings settings;
    private EditText editor;
    private GestureDetector gestureDetector;
    private MarkdownStore store;
    private PasswordVault passwordVault;
    private TextView openingTypewriter;
    private Runnable pendingSave;
    private Screen screen = Screen.INDEX;
    private String currentCategory = MarkdownStore.ROOT_CATEGORY;
    private String pendingMarkdown;
    private int currentPage = 1;
    private int openingCharIndex;
    private int openingWordIndex;
    private boolean authenticated;
    private boolean drivePickerFromSettings;
    private boolean openingCursorVisible = true;
    private boolean openingDeleting;
    private boolean suppressTextEvents;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prepareWindow();
        settings = new AppSettings(this);
        passwordVault = new PasswordVault(this);
        store = new MarkdownStore(this, settings);
        currentCategory = settings.getCurrentCategory();
        if (settings.isStorageReady()) {
            seedFirstPageIfNeeded();
        }
        updateSecureFlag();
        gestureDetector = new GestureDetector(this, new SwipeListener());

        if (passwordVault.hasPassword()) {
            showLogin();
        } else {
            authenticated = true;
            showOpening();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (screen == Screen.PAGE && gestureDetector != null) {
            gestureDetector.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (authenticated && screen != Screen.LOGIN && shouldAutoLock()) {
            authenticated = false;
            flushPendingSave();
            showLogin();
        }
    }

    @Override
    protected void onPause() {
        flushPendingSave();
        settings.setLastPauseTime(System.currentTimeMillis());
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DRIVE_FOLDER) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, "Drive folder not selected", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = data.getData();
        int grantedFlags = data.getFlags();
        if ((grantedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        if ((grantedFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        settings.useDriveStorage(uri.toString());
        resetStoreAfterStorageChange();
        Toast.makeText(this, "Google Drive folder selected", Toast.LENGTH_SHORT).show();
        if (drivePickerFromSettings) {
            drivePickerFromSettings = false;
            showSettings();
        } else {
            screen = Screen.OPENING;
            startQuickCapture();
        }
    }

    @Override
    public void onBackPressed() {
        if (screen == Screen.OPENING) {
            startQuickCapture();
            return;
        }
        if (screen == Screen.PAGE || screen == Screen.GUIDE || screen == Screen.SETTINGS) {
            showIndex();
            return;
        }
        if (screen == Screen.LOGIN) {
            moveTaskToBack(true);
            return;
        }
        if (screen == Screen.INDEX && !TextUtils.isEmpty(currentCategory)) {
            switchCategory(MarkdownStore.ROOT_CATEGORY);
            return;
        }
        super.onBackPressed();
    }

    private void prepareWindow() {
        Window window = getWindow();
        window.setStatusBarColor(0xFF070707);
        window.setNavigationBarColor(0xFF070707);
    }

    private void updateSecureFlag() {
        if (passwordVault.hasPassword()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void seedFirstPageIfNeeded() {
        if (!settings.isStorageReady()) {
            return;
        }
        if (!settings.isSeeded() && store.listPages(MarkdownStore.ROOT_CATEGORY).isEmpty()) {
            store.savePage(MarkdownStore.ROOT_CATEGORY, 1, TUTORIAL_MARKDOWN);
        }
        if (!settings.isSeeded()) {
            settings.setSeeded();
        }
    }

    private void resetStoreAfterStorageChange() {
        store = new MarkdownStore(this, settings);
        currentCategory = MarkdownStore.ROOT_CATEGORY;
        settings.setCurrentCategory(currentCategory);
        settings.clearSeeded();
        seedFirstPageIfNeeded();
    }

    private boolean shouldAutoLock() {
        if (!passwordVault.hasPassword() || !settings.isAutoLockEnabled()) {
            return false;
        }
        long lastPause = settings.getLastPauseTime();
        return lastPause > 0 && System.currentTimeMillis() - lastPause >= AUTO_LOCK_DELAY_MS;
    }

    private void showLogin() {
        screen = Screen.LOGIN;
        saveHandler.removeCallbacksAndMessages(null);
        uiHandler.removeCallbacksAndMessages(null);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(32), dp(80), dp(32), dp(32));
        layout.setBackgroundColor(0xFF070707);

        TextView logo = text("Last Idea", 46, Typeface.MONOSPACE);
        logo.setGravity(Gravity.CENTER);
        logo.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        logoParams.topMargin = dp(18);
        layout.addView(logo, logoParams);

        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setGravity(Gravity.CENTER);
        password.setTextColor(Color.WHITE);
        password.setHintTextColor(0x88FFFFFF);
        password.setHint("Password");
        password.setTypeface(Typeface.MONOSPACE);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setBackgroundColor(Color.TRANSPARENT);
        password.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (passwordVault.verify(s.toString())) {
                    authenticated = true;
                    settings.setLastPauseTime(0L);
                    showOpening();
                }
            }
        });
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        passwordParams.topMargin = dp(80);
        layout.addView(password, passwordParams);

        setContentView(layout);
        password.requestFocus();
        password.postDelayed(() -> {
            InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null) {
                input.showSoftInput(password, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200L);
    }

    private void showOpening() {
        screen = Screen.OPENING;
        uiHandler.removeCallbacksAndMessages(null);
        openingWordIndex = 0;
        openingCharIndex = 0;
        openingDeleting = false;
        openingCursorVisible = true;

        PageFrameLayout frame = new PageFrameLayout(this);
        setFrameMargins(frame);
        if (settings.isStorageReady()) {
            frame.setOnClickListener(view -> startQuickCapture());
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(12), 0, dp(12), dp(20));

        TextView brand = text("Last Idea", 52, Typeface.MONOSPACE);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.015f);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brandParams.topMargin = dp(0);
        content.addView(brand, brandParams);

        openingTypewriter = text("", 20, Typeface.MONOSPACE);
        openingTypewriter.setGravity(Gravity.CENTER);
        openingTypewriter.setTextColor(0xFFE9E9E9);
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        typeParams.topMargin = dp(12);
        content.addView(openingTypewriter, typeParams);

        if (settings.isStorageReady()) {
            TextView tap = text("tap to capture", 13, Typeface.MONOSPACE);
            tap.setGravity(Gravity.CENTER);
            tap.setTextColor(0x99FFFFFF);
            LinearLayout.LayoutParams tapParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tapParams.topMargin = dp(44);
            content.addView(tap, tapParams);
        } else {
            TextView prompt = text("where should ideas live?", 14, Typeface.MONOSPACE);
            prompt.setGravity(Gravity.CENTER);
            prompt.setTextColor(0xCCFFFFFF);
            LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            promptParams.topMargin = dp(40);
            content.addView(prompt, promptParams);

            LinearLayout choices = new LinearLayout(this);
            choices.setOrientation(LinearLayout.HORIZONTAL);
            choices.setGravity(Gravity.CENTER);
            choices.addView(choiceButton("Local", this::chooseLocalStorage), new LinearLayout.LayoutParams(0, dp(48), 1f));
            View gap = new View(this);
            choices.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
            choices.addView(choiceButton("Google Drive", () -> chooseDriveStorage(false)), new LinearLayout.LayoutParams(0, dp(48), 1f));
            LinearLayout.LayoutParams choicesParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            choicesParams.topMargin = dp(18);
            content.addView(choices, choicesParams);
        }

        frame.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(frame);
        tickOpeningTypewriter();
        blinkOpeningCursor();
        if (settings.isStorageReady()) {
            uiHandler.postDelayed(this::startQuickCapture, OPENING_MAX_MS);
        }
    }

    private void startQuickCapture() {
        if (screen != Screen.OPENING || !settings.isStorageReady()) {
            return;
        }
        uiHandler.removeCallbacksAndMessages(null);
        showPage(currentCategory, store.firstOpenPage(currentCategory));
    }

    private void chooseLocalStorage() {
        settings.useLocalStorage();
        resetStoreAfterStorageChange();
        screen = Screen.OPENING;
        startQuickCapture();
    }

    private void chooseDriveStorage(boolean fromSettings) {
        drivePickerFromSettings = fromSettings;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DRIVE_FOLDER);
    }

    private void tickOpeningTypewriter() {
        if (screen != Screen.OPENING || openingTypewriter == null) {
            return;
        }
        String word = OPENING_WORDS[openingWordIndex];
        long delay;
        if (!openingDeleting && openingCharIndex < word.length()) {
            openingCharIndex++;
            delay = 54L;
        } else if (!openingDeleting) {
            openingDeleting = true;
            delay = 280L;
        } else if (openingCharIndex > 0) {
            openingCharIndex--;
            delay = 30L;
        } else {
            openingDeleting = false;
            openingWordIndex = (openingWordIndex + 1) % OPENING_WORDS.length;
            delay = 80L;
        }
        renderOpeningTypewriter();
        uiHandler.postDelayed(this::tickOpeningTypewriter, delay);
    }

    private void blinkOpeningCursor() {
        if (screen != Screen.OPENING || openingTypewriter == null) {
            return;
        }
        openingCursorVisible = !openingCursorVisible;
        renderOpeningTypewriter();
        uiHandler.postDelayed(this::blinkOpeningCursor, 500L);
    }

    private void renderOpeningTypewriter() {
        if (openingTypewriter == null) {
            return;
        }
        String word = OPENING_WORDS[openingWordIndex];
        int length = Math.min(openingCharIndex, word.length());
        String cursor = openingCursorVisible ? "|" : " ";
        openingTypewriter.setText(word.substring(0, length) + cursor);
    }

    private void showIndex() {
        flushPendingSave();
        screen = Screen.INDEX;
        setContentView(framedScroll(indexContent()));
    }

    private LinearLayout indexContent() {
        LinearLayout content = vertical();
        TextView folder = text("Folder: " + store.displayCategory(currentCategory), 16, Typeface.MONOSPACE);
        folder.setTextColor(0xCCFFFFFF);
        folder.setPadding(dp(4), 0, dp(4), dp(12));
        content.addView(folder);

        content.addView(row("Capture now", store.firstOpenPage(currentCategory),
                () -> showPage(currentCategory, store.firstOpenPage(currentCategory))));
        content.addView(row("New folder", 0, this::showNewCategoryDialog));
        if (!TextUtils.isEmpty(currentCategory)) {
            content.addView(row("Inbox", 0, () -> switchCategory(MarkdownStore.ROOT_CATEGORY)));
        }
        if (TextUtils.isEmpty(currentCategory)) {
            for (String category : store.listCategories()) {
                content.addView(row(category + "/", 0, () -> switchCategory(category)));
            }
        }
        if (settings.isShowGuideEnabled()) {
            content.addView(row("Guide", PAGE_GUIDE, this::showGuide));
        }
        content.addView(row("Settings", PAGE_SETTINGS, this::showSettings));

        List<PageInfo> pages = store.listPages(currentCategory);
        if (!pages.isEmpty()) {
            content.addView(spacer(12));
        }
        for (PageInfo page : pages) {
            content.addView(pageRow(page));
        }
        return content;
    }

    private void showPage(int page) {
        showPage(currentCategory, page);
    }

    private void showPage(String category, int page) {
        flushPendingSave();
        currentCategory = category == null ? MarkdownStore.ROOT_CATEGORY : category;
        settings.setCurrentCategory(currentCategory);
        currentPage = Math.max(1, page);
        pendingMarkdown = store.readPage(currentCategory, currentPage);
        screen = Screen.PAGE;

        PageFrameLayout frame = new PageFrameLayout(this);
        setFrameMargins(frame);
        frame.setOnClickListener(view -> showIndex());

        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(0x66FFFFFF);
        editor.setHint("# Last Idea\n\n");
        editor.setTextSize(21);
        editor.setTypeface(settings.getTypeface());
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setMinLines(12);
        editor.setBackgroundColor(Color.TRANSPARENT);

        suppressTextEvents = true;
        editor.setText(pendingMarkdown);
        editor.setSelection(editor.getText().length());
        suppressTextEvents = false;
        editor.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressTextEvents) {
                    pendingMarkdown = s.toString();
                    scheduleSave();
                }
            }
        });

        FrameLayout.LayoutParams editorParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        editorParams.bottomMargin = dp(30);
        frame.addView(editor, editorParams);

        TextView number = text(store.displayCategory(currentCategory) + " / " + currentPage, 14, settings.getTypeface());
        number.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams numberParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        frame.addView(number, numberParams);

        setContentView(frame);
        editor.requestFocus();
        editor.postDelayed(() -> {
            InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null) {
                input.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120L);
    }

    private void showGuide() {
        flushPendingSave();
        screen = Screen.GUIDE;

        LinearLayout content = vertical();
        TextView guide = text(GUIDE_MARKDOWN, 20, settings.getTypeface());
        guide.setLineSpacing(dp(3), 1.0f);
        content.addView(guide);
        setContentView(framedScroll(content));
    }

    private void showSettings() {
        flushPendingSave();
        screen = Screen.SETTINGS;

        LinearLayout content = vertical();
        content.addView(row("Wipe ideas", 0, this::confirmWipe, R.drawable.dn_ic_wipe));
        content.addView(row("Password", 0, this::showPasswordDialog, R.drawable.dn_ic_lock));
        content.addView(row("Font", 0, this::showFontDialog, R.drawable.dn_ic_font));
        content.addView(row("Storage", 0, this::showStorageDialog));

        CheckBox showGuide = new CheckBox(this);
        showGuide.setText(getString(R.string.setting_show_guide));
        showGuide.setTextColor(Color.WHITE);
        showGuide.setTextSize(22);
        showGuide.setTypeface(settings.getTypeface());
        showGuide.setChecked(settings.isShowGuideEnabled());
        showGuide.setPadding(dp(4), dp(16), dp(4), dp(16));
        showGuide.setOnCheckedChangeListener((buttonView, isChecked) -> settings.setShowGuideEnabled(isChecked));
        content.addView(showGuide, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView directory = text(store.describeLocation(), 14, Typeface.MONOSPACE);
        directory.setPadding(0, dp(24), 0, 0);
        directory.setOnLongClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Last Idea storage", store.describeLocation()));
                Toast.makeText(this, "Storage path copied", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        content.addView(directory);

        setContentView(framedScroll(content));
    }

    private void showPasswordDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), 0);

        EditText password = new EditText(this);
        password.setHint("Leave empty to remove password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(password);

        CheckBox autoLock = new CheckBox(this);
        autoLock.setText(getString(R.string.setting_auto_lock));
        autoLock.setChecked(settings.isAutoLockEnabled() && passwordVault.hasPassword());
        autoLock.setEnabled(passwordVault.hasPassword());
        content.addView(autoLock);

        password.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                autoLock.setEnabled(!TextUtils.isEmpty(s) || passwordVault.hasPassword());
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Password")
                .setView(content)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = password.getText().toString();
                    passwordVault.setPassword(value);
                    settings.setAutoLockEnabled(passwordVault.hasPassword() && autoLock.isChecked());
                    updateSecureFlag();
                    Toast.makeText(this, passwordVault.hasPassword() ? "Password saved" : "Password removed", Toast.LENGTH_SHORT).show();
                    showSettings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFontDialog() {
        String[] labels = {"System", "Death", "Near", "Ryuk", "Serif", "Monospace"};
        String[] values = {
                AppSettings.FONT_DEFAULT,
                AppSettings.FONT_DEATH,
                AppSettings.FONT_NEAR,
                AppSettings.FONT_RYUK,
                AppSettings.FONT_SERIF,
                AppSettings.FONT_MONO
        };
        int checked = 0;
        String current = settings.getFont();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Font")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    settings.setFont(values[which]);
                    dialog.dismiss();
                    showSettings();
                })
                .show();
    }

    private void showStorageDialog() {
        String[] labels = {"Local", "Google Drive"};
        int checked = settings.isDriveStorage() ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Storage")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        settings.useLocalStorage();
                        resetStoreAfterStorageChange();
                        Toast.makeText(this, "Saving locally", Toast.LENGTH_SHORT).show();
                        showSettings();
                    } else {
                        chooseDriveStorage(true);
                    }
                })
                .show();
    }

    private void showNewCategoryDialog() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Folder name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setPadding(dp(20), dp(8), dp(20), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("New folder")
                .setView(name)
                .setPositiveButton("Create", (dialog, which) -> {
                    String value = name.getText().toString();
                    if (store.createCategory(value)) {
                        switchCategory(value.trim());
                    } else {
                        Toast.makeText(this, "Folder not created", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void switchCategory(String category) {
        currentCategory = category == null ? MarkdownStore.ROOT_CATEGORY : category;
        settings.setCurrentCategory(currentCategory);
        showIndex();
    }

    private void showMoveDialog(PageInfo page) {
        List<String> targets = store.listCategories();
        targets.add(0, MarkdownStore.ROOT_CATEGORY);
        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            labels[i] = store.displayCategory(targets.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Move idea")
                .setItems(labels, (dialog, which) -> {
                    String target = targets.get(which);
                    if (target.equals(page.category)) {
                        return;
                    }
                    int movedPage = store.movePage(page.category, page.page, target);
                    if (movedPage > 0) {
                        currentCategory = target;
                        settings.setCurrentCategory(currentCategory);
                        Toast.makeText(this, "Moved to " + store.displayCategory(target), Toast.LENGTH_SHORT).show();
                        showIndex();
                    } else {
                        Toast.makeText(this, "Move failed", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("Wipe ideas")
                .setMessage("Delete all Markdown pages?")
                .setPositiveButton("Wipe", (dialog, which) -> {
                    store.wipe();
                    Toast.makeText(this, "Ideas wiped", Toast.LENGTH_SHORT).show();
                    showIndex();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void scheduleSave() {
        saveHandler.removeCallbacksAndMessages(null);
        pendingSave = () -> {
            store.savePage(currentCategory, currentPage, pendingMarkdown);
            pendingSave = null;
        };
        saveHandler.postDelayed(pendingSave, SAVE_DELAY_MS);
    }

    private void flushPendingSave() {
        if (pendingSave != null) {
            saveHandler.removeCallbacks(pendingSave);
            pendingSave.run();
        }
    }

    private View framedScroll(View child) {
        PageFrameLayout frame = new PageFrameLayout(this);
        setFrameMargins(frame);
        frame.setOnClickListener(view -> showIndex());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(child, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private LinearLayout vertical() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        return content;
    }

    private View row(String title, int page, Runnable click) {
        return row(title, page, click, 0);
    }

    private View row(String title, int page, Runnable click, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setOnClickListener(view -> click.run());

        if (iconRes != 0) {
            ImageView icon = assetImage(iconRes, dp(32), dp(32));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(52));
            row.addView(icon, iconParams);
        }

        TextView titleView = text(title, 22, settings.getTypeface());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(titleView, new LinearLayout.LayoutParams(0, dp(52), 1f));

        TextView pageView = text(page > 0 ? String.valueOf(page) : "", 18, settings.getTypeface());
        pageView.setGravity(Gravity.CENTER);
        row.addView(pageView, new LinearLayout.LayoutParams(dp(48), dp(52)));
        return row;
    }

    private View pageRow(PageInfo page) {
        View view = row(page.title, page.page, () -> showPage(page.category, page.page));
        view.setOnLongClickListener(row -> {
            showMoveDialog(page);
            return true;
        });
        return view;
    }

    private TextView choiceButton(String title, Runnable click) {
        TextView button = text(title, 15, Typeface.MONOSPACE);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(0x22FFFFFF);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private ImageView assetImage(int resourceId, int width, int height) {
        ImageView image = new ImageView(this);
        image.setImageResource(resourceId);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return image;
    }

    private TextView text(String value, int sp, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setTypeface(typeface);
        return view;
    }

    private View spacer(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return view;
    }

    private void setFrameMargins(View frame) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        int margin = dp(16);
        params.setMargins(margin, margin, margin, margin);
        frame.setLayoutParams(params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }

    private final class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null || Math.abs(velocityX) < Math.abs(velocityY)) {
                return false;
            }
            float delta = e2.getX() - e1.getX();
            if (Math.abs(delta) < dp(80) || Math.abs(velocityX) < 250) {
                return false;
            }
            if (delta < 0) {
                showPage(currentPage + 1);
            } else if (currentPage <= 1) {
                showIndex();
            } else {
                showPage(currentPage - 1);
            }
            return true;
        }
    }
}
