package org.lastidea.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    private static final class ThemePalette {
        final int accentBronze;
        final int accentCrimson;
        final int backgroundBottom;
        final int backgroundMiddle;
        final int backgroundTop;
        final int border;
        final int card;
        final int cardPressed;
        final int mutedText;
        final int paper;
        final int paperBorder;
        final int paperText;
        final int surface;
        final int text;

        ThemePalette(
                int backgroundTop,
                int backgroundMiddle,
                int backgroundBottom,
                int surface,
                int card,
                int cardPressed,
                int paper,
                int paperBorder,
                int paperText,
                int text,
                int mutedText,
                int border,
                int accentCrimson,
                int accentBronze) {
            this.accentBronze = accentBronze;
            this.accentCrimson = accentCrimson;
            this.backgroundBottom = backgroundBottom;
            this.backgroundMiddle = backgroundMiddle;
            this.backgroundTop = backgroundTop;
            this.border = border;
            this.card = card;
            this.cardPressed = cardPressed;
            this.mutedText = mutedText;
            this.paper = paper;
            this.paperBorder = paperBorder;
            this.paperText = paperText;
            this.surface = surface;
            this.text = text;
        }
    }

    private enum Screen {
        LOGIN,
        OPENING,
        INDEX,
        PAGE,
        GUIDE,
        SETTINGS
    }

    private final SimpleDateFormat cardDateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
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
        ThemePalette colors = theme();
        applyWindowTheme(colors);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(32), dp(80), dp(32), dp(32));
        layout.setBackgroundColor(colors.backgroundBottom);

        TextView logo = text("Last Idea", 46, appTypeface());
        logo.setGravity(Gravity.CENTER);
        logo.setLetterSpacing(0.02f);
        logo.setTextColor(colors.text);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        logoParams.topMargin = dp(18);
        layout.addView(logo, logoParams);

        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setGravity(Gravity.CENTER);
        password.setTextColor(colors.text);
        password.setHintTextColor(colors.mutedText);
        password.setHint("Password");
        password.setTypeface(appTypeface());
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
        ThemePalette colors = theme();
        applyWindowTheme(colors);
        openingWordIndex = 0;
        openingCharIndex = 0;
        openingDeleting = false;
        openingCursorVisible = true;

        PageFrameLayout frame = themedFrame(colors);
        setFrameMargins(frame);
        if (settings.isStorageReady()) {
            frame.setOnClickListener(view -> startQuickCapture());
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(12), 0, dp(12), dp(20));

        TextView brand = text("Last Idea", 52, appTypeface());
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.015f);
        brand.setTextColor(colors.text);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brandParams.topMargin = dp(0);
        content.addView(brand, brandParams);

        openingTypewriter = text("", 20, appTypeface());
        openingTypewriter.setGravity(Gravity.CENTER);
        openingTypewriter.setTextColor(colors.text);
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        typeParams.topMargin = dp(12);
        content.addView(openingTypewriter, typeParams);

        if (settings.isStorageReady()) {
            TextView tap = text("tap to capture", 13, appTypeface());
            tap.setGravity(Gravity.CENTER);
            tap.setTextColor(colors.mutedText);
            LinearLayout.LayoutParams tapParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tapParams.topMargin = dp(44);
            content.addView(tap, tapParams);
        } else {
            TextView prompt = text("where should ideas live?", 14, appTypeface());
            prompt.setGravity(Gravity.CENTER);
            prompt.setTextColor(colors.mutedText);
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
        applyWindowTheme(theme());
        setContentView(framedScroll(indexContent()));
    }

    private LinearLayout indexContent() {
        ThemePalette colors = theme();
        LinearLayout content = vertical();
        content.setPadding(0, 0, 0, dp(18));
        content.addView(indexHeader(colors));
        content.addView(actionBoard(colors));

        boolean showingRoot = TextUtils.isEmpty(currentCategory);
        if (showingRoot) {
            addFolderSection(content, MarkdownStore.ROOT_CATEGORY, colors, true);
            for (String category : store.listCategories()) {
                addFolderSection(content, category, colors, false);
            }
        } else {
            addFolderSection(content, currentCategory, colors, true);
            content.addView(folderShortcutStrip(colors));
        }
        return content;
    }

    private View indexHeader(ThemePalette colors) {
        LinearLayout header = vertical();
        header.setPadding(dp(18), dp(16), dp(18), dp(16));
        header.setBackground(rounded(colors.surface, 14, colors.border, 1));
        header.setElevation(dp(2));

        TextView label = text("EVIDENCE BOARD", 12, appTypeface());
        label.setTextColor(colors.accentBronze);
        label.setLetterSpacing(0.08f);
        header.addView(label);

        TextView title = text(store.displayCategory(currentCategory), 30, appTypeface());
        title.setTextColor(colors.text);
        title.setTypeface(boldAppTypeface());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(4);
        header.addView(title, titleParams);

        String storage = settings.isDriveStorage() ? "Google Drive" : "Local Markdown";
        TextView subtitle = text(storage + " / " + store.displayCategory(currentCategory), 13, appTypeface());
        subtitle.setTextColor(colors.mutedText);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(4);
        header.addView(subtitle, subtitleParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(14);
        header.setLayoutParams(params);
        return header;
    }

    private View actionBoard(ThemePalette colors) {
        LinearLayout actions = vertical();
        actions.setPadding(0, 0, 0, dp(4));

        LinearLayout firstRow = horizontal();
        firstRow.addView(actionChip("Capture", colors.accentCrimson,
                () -> showPage(currentCategory, store.firstOpenPage(currentCategory))),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        firstRow.addView(gap(10), new LinearLayout.LayoutParams(dp(10), 1));
        firstRow.addView(actionChip("Folder", colors.card,
                this::showNewCategoryDialog),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        if (!TextUtils.isEmpty(currentCategory)) {
            firstRow.addView(gap(10), new LinearLayout.LayoutParams(dp(10), 1));
            firstRow.addView(actionChip("Inbox", colors.card,
                    () -> switchCategory(MarkdownStore.ROOT_CATEGORY)),
                    new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        actions.addView(firstRow);

        LinearLayout secondRow = horizontal();
        if (settings.isShowGuideEnabled()) {
            secondRow.addView(actionChip("Guide", colors.cardPressed, this::showGuide),
                    new LinearLayout.LayoutParams(0, dp(40), 1f));
            secondRow.addView(gap(10), new LinearLayout.LayoutParams(dp(10), 1));
        }
        secondRow.addView(actionChip("Settings", colors.cardPressed, this::showSettings),
                new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        secondParams.topMargin = dp(10);
        actions.addView(secondRow, secondParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        actions.setLayoutParams(params);
        return actions;
    }

    private View folderShortcutStrip(ThemePalette colors) {
        LinearLayout strip = vertical();
        strip.setPadding(0, dp(12), 0, 0);

        TextView label = text("Folders", 12, appTypeface());
        label.setTextColor(colors.accentBronze);
        label.setLetterSpacing(0.08f);
        strip.addView(label);

        if (!TextUtils.isEmpty(currentCategory)) {
            strip.addView(folderShortcut(MarkdownStore.ROOT_CATEGORY, colors));
        }
        for (String category : store.listCategories()) {
            if (!category.equals(currentCategory)) {
                strip.addView(folderShortcut(category, colors));
            }
        }
        return strip;
    }

    private View folderShortcut(String category, ThemePalette colors) {
        TextView shortcut = text(store.displayCategory(category), 16, appTypeface());
        shortcut.setTextColor(colors.text);
        shortcut.setSingleLine(true);
        shortcut.setEllipsize(TextUtils.TruncateAt.END);
        shortcut.setPadding(dp(14), 0, dp(14), 0);
        shortcut.setGravity(Gravity.CENTER_VERTICAL);
        shortcut.setBackground(rounded(colors.card, 10, colors.border, 1));
        shortcut.setOnClickListener(view -> switchCategory(category));
        if (!TextUtils.isEmpty(category)) {
            shortcut.setOnLongClickListener(view -> {
                showFolderActions(category);
                return true;
            });
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44));
        params.topMargin = dp(8);
        shortcut.setLayoutParams(params);
        return shortcut;
    }

    private void addFolderSection(LinearLayout content, String category, ThemePalette colors, boolean active) {
        List<PageInfo> pages = store.listPages(category);
        content.addView(folderSectionHeader(category, pages.size(), colors, active));
        if (pages.isEmpty()) {
            content.addView(emptyFolderCard(category, colors));
            return;
        }
        for (PageInfo page : pages) {
            content.addView(noteCard(page, colors));
        }
    }

    private View folderSectionHeader(String category, int count, ThemePalette colors, boolean active) {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(18), dp(2), dp(8));
        header.setOnClickListener(view -> switchCategory(category));
        if (!TextUtils.isEmpty(category)) {
            header.setOnLongClickListener(view -> {
                showFolderActions(category);
                return true;
            });
        }

        View marker = new View(this);
        marker.setBackground(rounded(active ? colors.accentCrimson : colors.accentBronze, 4, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(5), dp(28));
        markerParams.rightMargin = dp(10);
        header.addView(marker, markerParams);

        TextView title = text(store.displayCategory(category), 20, appTypeface());
        title.setTypeface(boldAppTypeface());
        title.setTextColor(colors.text);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = text(count + (count == 1 ? " idea" : " ideas"), 12, appTypeface());
        badge.setTextColor(active ? colors.accentCrimson : colors.mutedText);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), 0, dp(10), 0);
        badge.setBackground(rounded(colors.card, 16, colors.border, 1));
        header.addView(badge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(30)));
        return header;
    }

    private View emptyFolderCard(String category, ThemePalette colors) {
        TextView empty = text("No ideas pinned in " + store.displayCategory(category) + " yet.", 15, appTypeface());
        empty.setTextColor(colors.mutedText);
        empty.setPadding(dp(16), 0, dp(16), 0);
        empty.setGravity(Gravity.CENTER_VERTICAL);
        empty.setBackground(rounded(colors.card, 12, colors.border, 1));
        empty.setOnClickListener(view -> showPage(category, store.firstOpenPage(category)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58));
        params.bottomMargin = dp(8);
        empty.setLayoutParams(params);
        return empty;
    }

    private View noteCard(PageInfo page, ThemePalette colors) {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        card.setBackground(rounded(colors.paper, 12, colors.paperBorder, 1));
        card.setElevation(dp(3));
        card.setOnClickListener(view -> showPage(page.category, page.page));
        card.setOnLongClickListener(view -> {
            showIdeaActions(page);
            return true;
        });

        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);

        View pin = new View(this);
        pin.setBackground(oval(colors.accentCrimson, colors.accentBronze, 1));
        LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(dp(11), dp(11));
        pinParams.rightMargin = dp(10);
        top.addView(pin, pinParams);

        TextView title = text(page.title, 19, appTypeface());
        title.setTypeface(boldAppTypeface());
        title.setTextColor(colors.paperText);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        TextView preview = text(page.preview, 14, appTypeface());
        preview.setTextColor(colors.mutedText);
        preview.setMaxLines(3);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        previewParams.topMargin = dp(8);
        card.addView(preview, previewParams);

        TextView meta = text(formatCardDate(page.modifiedMillis)
                + " / " + store.displayCategory(page.category)
                + " / " + pageFileLabel(page.page), 12, appTypeface());
        meta.setTextColor(colors.accentBronze);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(12);
        card.addView(meta, metaParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private String pageFileLabel(int page) {
        return String.format(Locale.US, "page-%06d.md", Math.max(1, page));
    }

    private String formatCardDate(long modifiedMillis) {
        if (modifiedMillis <= 0L) {
            return "undated";
        }
        return cardDateFormat.format(new Date(modifiedMillis));
    }

    private void showPage(int page) {
        showPage(currentCategory, page);
    }

    private void showPage(String category, int page) {
        flushPendingSave();
        ThemePalette colors = theme();
        applyWindowTheme(colors);
        currentCategory = category == null ? MarkdownStore.ROOT_CATEGORY : category;
        settings.setCurrentCategory(currentCategory);
        currentPage = Math.max(1, page);
        pendingMarkdown = store.readPage(currentCategory, currentPage);
        screen = Screen.PAGE;

        PageFrameLayout frame = themedFrame(colors);
        setFrameMargins(frame);
        frame.setOnClickListener(view -> showIndex());

        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextColor(colors.text);
        editor.setHintTextColor(colors.mutedText);
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
        editor.setPadding(0, 0, 0, dp(16));

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
        number.setTextColor(colors.mutedText);
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
        ThemePalette colors = theme();
        applyWindowTheme(colors);

        LinearLayout content = vertical();
        TextView guide = text(GUIDE_MARKDOWN, 20, settings.getTypeface());
        guide.setTextColor(colors.text);
        guide.setLineSpacing(dp(3), 1.0f);
        content.addView(guide);
        setContentView(framedScroll(content));
    }

    private void showSettings() {
        flushPendingSave();
        screen = Screen.SETTINGS;
        ThemePalette colors = theme();
        applyWindowTheme(colors);

        LinearLayout content = vertical();
        content.addView(row("Wipe ideas", 0, this::confirmWipe, R.drawable.dn_ic_wipe));
        content.addView(row("Password", 0, this::showPasswordDialog, R.drawable.dn_ic_lock));
        content.addView(row("Font", 0, this::showFontDialog, R.drawable.dn_ic_font));
        content.addView(row("Appearance", 0, this::showAppearanceDialog));
        content.addView(row("Storage", 0, this::showStorageDialog));

        CheckBox showGuide = new CheckBox(this);
        showGuide.setText(getString(R.string.setting_show_guide));
        showGuide.setTextColor(colors.text);
        showGuide.setTextSize(22);
        showGuide.setTypeface(settings.getTypeface());
        showGuide.setChecked(settings.isShowGuideEnabled());
        showGuide.setPadding(dp(4), dp(16), dp(4), dp(16));
        showGuide.setOnCheckedChangeListener((buttonView, isChecked) -> settings.setShowGuideEnabled(isChecked));
        content.addView(showGuide, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView directory = text(store.describeLocation(), 14, appTypeface());
        directory.setTextColor(colors.mutedText);
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
        String[] labels = {"Garamond", "System", "Death", "Near", "Ryuk", "Serif", "Monospace"};
        String[] values = {
                AppSettings.FONT_DEFAULT,
                AppSettings.FONT_SYSTEM,
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

    private void showAppearanceDialog() {
        String[] labels = {"Dark Collage", "Pure Black", "Minimal"};
        String[] values = {
                AppSettings.APPEARANCE_DARK_COLLAGE,
                AppSettings.APPEARANCE_PURE_BLACK,
                AppSettings.APPEARANCE_MINIMAL
        };
        int checked = 0;
        String current = settings.getAppearanceMode();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Appearance")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    settings.setAppearanceMode(values[which]);
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

    private void showIdeaActions(PageInfo page) {
        String[] actions = {"Open", "Move", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(page.title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        showPage(page.category, page.page);
                    } else if (which == 1) {
                        showMoveDialog(page);
                    } else {
                        confirmDeletePage(page);
                    }
                })
                .show();
    }

    private void showFolderActions(String category) {
        String[] actions = {"Open folder", "Delete folder"};
        new AlertDialog.Builder(this)
                .setTitle(store.displayCategory(category))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        switchCategory(category);
                    } else {
                        confirmDeleteCategory(category);
                    }
                })
                .show();
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

    private void confirmDeletePage(PageInfo page) {
        new AlertDialog.Builder(this)
                .setTitle("Delete idea")
                .setMessage("Delete " + pageFileLabel(page.page) + " from " + store.displayCategory(page.category) + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (store.deletePage(page.category, page.page)) {
                        if (page.category.equals(currentCategory) && page.page == currentPage) {
                            pendingMarkdown = "";
                        }
                        Toast.makeText(this, "Idea deleted", Toast.LENGTH_SHORT).show();
                        showIndex();
                    } else {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteCategory(String category) {
        new AlertDialog.Builder(this)
                .setTitle("Delete folder")
                .setMessage("Delete " + store.displayCategory(category) + " and all Markdown files inside it?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (store.deleteCategory(category)) {
                        if (category.equals(currentCategory)) {
                            currentCategory = MarkdownStore.ROOT_CATEGORY;
                            settings.setCurrentCategory(currentCategory);
                        }
                        Toast.makeText(this, "Folder deleted", Toast.LENGTH_SHORT).show();
                        showIndex();
                    } else {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
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
        PageFrameLayout frame = themedFrame(theme());
        setFrameMargins(frame);
        frame.setOnClickListener(view -> showIndex());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(child, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private PageFrameLayout themedFrame(ThemePalette colors) {
        PageFrameLayout frame = new PageFrameLayout(this);
        frame.setFramePalette(
                colors.backgroundTop,
                colors.backgroundMiddle,
                colors.backgroundBottom,
                colors.mutedText,
                colors.accentBronze);
        frame.setTitleTypeface(appTypeface());
        return frame;
    }

    private Typeface appTypeface() {
        return settings == null ? Typeface.SERIF : settings.getTypeface();
    }

    private Typeface boldAppTypeface() {
        return Typeface.create(appTypeface(), Typeface.BOLD);
    }

    private LinearLayout vertical() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        return content;
    }

    private LinearLayout horizontal() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        return content;
    }

    private View row(String title, int page, Runnable click) {
        return row(title, page, click, 0);
    }

    private View row(String title, int page, Runnable click, int iconRes) {
        ThemePalette colors = theme();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setBackground(rounded(colors.card, 12, colors.border, 1));
        row.setOnClickListener(view -> click.run());
        row.setElevation(dp(1));

        if (iconRes != 0) {
            ImageView icon = assetImage(iconRes, dp(32), dp(32));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(52));
            row.addView(icon, iconParams);
        }

        TextView titleView = text(title, 22, settings.getTypeface());
        titleView.setTextColor(colors.text);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(titleView, new LinearLayout.LayoutParams(0, dp(52), 1f));

        TextView pageView = text(page > 0 ? String.valueOf(page) : "", 18, settings.getTypeface());
        pageView.setGravity(Gravity.CENTER);
        pageView.setTextColor(colors.accentBronze);
        row.addView(pageView, new LinearLayout.LayoutParams(dp(48), dp(52)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56));
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private View pageRow(PageInfo page) {
        View view = row(page.title, page.page, () -> showPage(page.category, page.page));
        view.setOnLongClickListener(row -> {
            showIdeaActions(page);
            return true;
        });
        return view;
    }

    private TextView choiceButton(String title, Runnable click) {
        ThemePalette colors = theme();
        TextView button = text(title, 15, appTypeface());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(colors.text);
        button.setBackground(rounded(colors.card, 12, colors.border, 1));
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private TextView actionChip(String title, int color, Runnable click) {
        ThemePalette colors = theme();
        TextView button = text(title, 13, appTypeface());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(colors.text);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(color, 12, colors.border, 1));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private GradientDrawable oval(int color, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private View gap(int dp) {
        View view = new View(this);
        view.setMinimumWidth(dp(dp));
        return view;
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

    private ThemePalette theme() {
        String mode = settings == null ? AppSettings.APPEARANCE_DARK_COLLAGE : settings.getAppearanceMode();
        if (AppSettings.APPEARANCE_PURE_BLACK.equals(mode)) {
            return new ThemePalette(
                    0xFF000000,
                    0xFF000000,
                    0xFF000000,
                    0xFF050505,
                    0xFF101010,
                    0xFF161616,
                    0xFF121212,
                    0xFF2F2F2F,
                    0xFFF3F0E8,
                    0xFFF5F2EA,
                    0xFF9E9A92,
                    0xFF272727,
                    0xFFB44646,
                    0xFF8B7438);
        }
        if (AppSettings.APPEARANCE_MINIMAL.equals(mode)) {
            return new ThemePalette(
                    0xFF111111,
                    0xFF161616,
                    0xFF0B0B0B,
                    0xFF181818,
                    0xFF1E1E1E,
                    0xFF262626,
                    0xFF202020,
                    0xFF343434,
                    0xFFF0EEE8,
                    0xFFF2F0EA,
                    0xFFA7A39C,
                    0xFF303030,
                    0xFF9E4848,
                    0xFF8D7A4A);
        }
        return new ThemePalette(
                0xFF080706,
                0xFF16100F,
                0xFF050505,
                0xFF161211,
                0xFF201A18,
                0xFF2A211E,
                0xFF211D1B,
                0xFF4A332E,
                0xFFF4EDDF,
                0xFFF4EFE6,
                0xFFBEB1A4,
                0xFF3A2B27,
                0xFFC14F4F,
                0xFFA5833C);
    }

    private void applyWindowTheme(ThemePalette colors) {
        Window window = getWindow();
        window.setStatusBarColor(colors.backgroundBottom);
        window.setNavigationBarColor(colors.backgroundBottom);
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
