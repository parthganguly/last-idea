package org.lastidea.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class MarkdownStore {
    static final String ROOT_CATEGORY = "";

    private static final String DIRECTORY_MIME = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String MARKDOWN_MIME = "text/plain";
    private static final String[] DOC_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    private final AppSettings settings;
    private final ContentResolver resolver;
    private final File localRoot;

    MarkdownStore(Context context, AppSettings settings) {
        this.settings = settings;
        resolver = context.getContentResolver();
        File external = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        localRoot = new File(external == null ? context.getFilesDir() : external, "LastIdea");
        if (!localRoot.exists() && !localRoot.mkdirs()) {
            throw new IllegalStateException("Unable to create notes directory: " + localRoot);
        }
    }

    String displayCategory(String category) {
        return TextUtils.isEmpty(category) ? "Inbox" : category;
    }

    String describeLocation() {
        if (isDrive()) {
            return "Google Drive folder:\n" + settings.getTreeUri();
        }
        return "Local Markdown folder:\n" + localRoot.getAbsolutePath();
    }

    List<String> listCategories() {
        if (isDrive()) {
            return listDriveCategories();
        }
        File[] dirs = localRoot.listFiles(File::isDirectory);
        List<String> categories = new ArrayList<>();
        if (dirs == null) {
            return categories;
        }
        for (File dir : dirs) {
            categories.add(dir.getName());
        }
        Collections.sort(categories, String.CASE_INSENSITIVE_ORDER);
        return categories;
    }

    boolean createCategory(String rawName) {
        String category = sanitizeCategory(rawName);
        if (TextUtils.isEmpty(category)) {
            return false;
        }
        if (isDrive()) {
            return !TextUtils.isEmpty(driveCategoryDocId(category, true));
        }
        File dir = new File(localRoot, category);
        return dir.exists() || dir.mkdirs();
    }

    String readPage(String category, int page) {
        if (isDrive()) {
            return readDrivePage(category, page);
        }
        File file = pageFile(category, page);
        if (!file.exists()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    void savePage(String category, int page, String markdown) {
        if (isDrive()) {
            saveDrivePage(category, page, markdown);
            return;
        }
        File file = pageFile(category, page);
        if (TextUtils.isEmpty(markdown) || TextUtils.isEmpty(markdown.trim())) {
            if (file.exists()) {
                file.delete();
            }
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + parent);
        }
        try {
            Files.write(file.toPath(), markdown.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save " + file, e);
        }
    }

    List<PageInfo> listPages(String category) {
        if (isDrive()) {
            return listDrivePages(category);
        }
        File dir = categoryDirectory(category, false);
        File[] files = dir.listFiles((parent, name) -> isPageName(name));
        List<PageInfo> pages = new ArrayList<>();
        if (files == null) {
            return pages;
        }
        for (File file : files) {
            int page = pageFromName(file.getName());
            if (page > 0) {
                pages.add(new PageInfo(normalizeCategory(category), page, titleFor(readFile(file))));
            }
        }
        Collections.sort(pages, Comparator.comparingInt(info -> info.page));
        return pages;
    }

    int firstOpenPage(String category) {
        int max = 0;
        for (PageInfo page : listPages(category)) {
            max = Math.max(max, page.page);
        }
        return Math.max(1, max + 1);
    }

    int movePage(String fromCategory, int page, String toCategory) {
        String markdown = readPage(fromCategory, page);
        if (TextUtils.isEmpty(markdown)) {
            return -1;
        }
        int destinationPage = firstOpenPage(toCategory);
        savePage(toCategory, destinationPage, markdown);
        savePage(fromCategory, page, "");
        return destinationPage;
    }

    void wipe() {
        if (isDrive()) {
            wipeDrive();
            return;
        }
        deleteLocalFiles(localRoot);
    }

    String titleFor(String markdown) {
        if (markdown == null) {
            return "Untitled";
        }
        String[] lines = markdown.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            while (line.startsWith("#")) {
                line = line.substring(1).trim();
            }
            line = line.replaceFirst("^[-*+]\\s+", "").trim();
            if (!line.isEmpty()) {
                return line.length() > 30 ? line.substring(0, 30) : line;
            }
        }
        return "Untitled";
    }

    private boolean isDrive() {
        return settings.isDriveStorage() && !TextUtils.isEmpty(settings.getTreeUri());
    }

    private String normalizeCategory(String category) {
        return category == null ? ROOT_CATEGORY : category;
    }

    private String sanitizeCategory(String rawName) {
        if (rawName == null) {
            return "";
        }
        String safe = rawName.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        while (safe.contains("..")) {
            safe = safe.replace("..", ".");
        }
        return ".".equals(safe) ? "" : safe;
    }

    private File categoryDirectory(String category, boolean create) {
        String normalized = normalizeCategory(category);
        File dir = TextUtils.isEmpty(normalized) ? localRoot : new File(localRoot, normalized);
        if (create && !dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + dir);
        }
        return dir;
    }

    private File pageFile(String category, int page) {
        return new File(categoryDirectory(category, true), pageName(page));
    }

    private String pageName(int page) {
        return String.format(Locale.US, "page-%06d.md", Math.max(1, page));
    }

    private boolean isPageName(String name) {
        return name != null && name.startsWith("page-") && name.endsWith(".md");
    }

    private int pageFromName(String name) {
        try {
            return Integer.parseInt(name.substring(5, name.length() - 3));
        } catch (Exception e) {
            return -1;
        }
    }

    private String readFile(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void deleteLocalFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteLocalFiles(file);
            }
            file.delete();
        }
    }

    private Uri treeUri() {
        return Uri.parse(settings.getTreeUri());
    }

    private String rootDocumentId() {
        return DocumentsContract.getTreeDocumentId(treeUri());
    }

    private Uri documentUri(String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri(), documentId);
    }

    private Uri childDocumentsUri(String documentId) {
        return DocumentsContract.buildChildDocumentsUriUsingTree(treeUri(), documentId);
    }

    private String driveCategoryDocId(String category, boolean create) {
        String normalized = normalizeCategory(category);
        if (TextUtils.isEmpty(normalized)) {
            return rootDocumentId();
        }
        String safe = sanitizeCategory(normalized);
        String existing = findDriveChild(rootDocumentId(), safe, DIRECTORY_MIME);
        if (!TextUtils.isEmpty(existing) || !create) {
            return existing;
        }
        try {
            Uri created = DocumentsContract.createDocument(
                    resolver,
                    documentUri(rootDocumentId()),
                    DIRECTORY_MIME,
                    safe);
            return created == null ? "" : DocumentsContract.getDocumentId(created);
        } catch (Exception e) {
            return "";
        }
    }

    private String findDriveChild(String parentDocumentId, String displayName, String mimeType) {
        if (TextUtils.isEmpty(parentDocumentId)) {
            return "";
        }
        try (Cursor cursor = resolver.query(childDocumentsUri(parentDocumentId), DOC_PROJECTION, null, null, null)) {
            if (cursor == null) {
                return "";
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameIndex);
                String mime = cursor.getString(mimeIndex);
                if (displayName.equals(name) && (mimeType == null || mimeType.equals(mime))) {
                    return cursor.getString(idIndex);
                }
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    private List<String> listDriveCategories() {
        List<String> categories = new ArrayList<>();
        try (Cursor cursor = resolver.query(childDocumentsUri(rootDocumentId()), DOC_PROJECTION, null, null, null)) {
            if (cursor == null) {
                return categories;
            }
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                if (DIRECTORY_MIME.equals(cursor.getString(mimeIndex))) {
                    categories.add(cursor.getString(nameIndex));
                }
            }
        } catch (Exception e) {
            return categories;
        }
        Collections.sort(categories, String.CASE_INSENSITIVE_ORDER);
        return categories;
    }

    private List<PageInfo> listDrivePages(String category) {
        List<PageInfo> pages = new ArrayList<>();
        String categoryDocId = driveCategoryDocId(category, false);
        if (TextUtils.isEmpty(categoryDocId)) {
            return pages;
        }
        try (Cursor cursor = resolver.query(childDocumentsUri(categoryDocId), DOC_PROJECTION, null, null, null)) {
            if (cursor == null) {
                return pages;
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameIndex);
                if (!isPageName(name) || DIRECTORY_MIME.equals(cursor.getString(mimeIndex))) {
                    continue;
                }
                int page = pageFromName(name);
                if (page > 0) {
                    String markdown = readFromUri(documentUri(cursor.getString(idIndex)));
                    pages.add(new PageInfo(normalizeCategory(category), page, titleFor(markdown)));
                }
            }
        } catch (Exception e) {
            return pages;
        }
        Collections.sort(pages, Comparator.comparingInt(info -> info.page));
        return pages;
    }

    private String readDrivePage(String category, int page) {
        String categoryDocId = driveCategoryDocId(category, false);
        if (TextUtils.isEmpty(categoryDocId)) {
            return "";
        }
        String docId = findDriveChild(categoryDocId, pageName(page), null);
        return TextUtils.isEmpty(docId) ? "" : readFromUri(documentUri(docId));
    }

    private void saveDrivePage(String category, int page, String markdown) {
        String categoryDocId = driveCategoryDocId(category, true);
        if (TextUtils.isEmpty(categoryDocId)) {
            throw new IllegalStateException("Unable to open Drive folder");
        }
        String docId = findDriveChild(categoryDocId, pageName(page), null);
        if (TextUtils.isEmpty(markdown) || TextUtils.isEmpty(markdown.trim())) {
            if (!TextUtils.isEmpty(docId)) {
                deleteDriveDocument(docId);
            }
            return;
        }
        Uri uri;
        try {
            if (TextUtils.isEmpty(docId)) {
                uri = DocumentsContract.createDocument(
                        resolver,
                        documentUri(categoryDocId),
                        MARKDOWN_MIME,
                        pageName(page));
            } else {
                uri = documentUri(docId);
            }
            if (uri == null) {
                throw new IllegalStateException("Unable to create Markdown file");
            }
            try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output == null) {
                    throw new IllegalStateException("Unable to write Markdown file");
                }
                output.write(markdown.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save Drive file", e);
        }
    }

    private String readFromUri(Uri uri) {
        try (InputStream input = resolver.openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                return "";
            }
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void deleteDriveDocument(String documentId) {
        try {
            DocumentsContract.deleteDocument(resolver, documentUri(documentId));
        } catch (Exception ignored) {
        }
    }

    private void wipeDrive() {
        try (Cursor cursor = resolver.query(childDocumentsUri(rootDocumentId()), DOC_PROJECTION, null, null, null)) {
            if (cursor == null) {
                return;
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameIndex);
                String mime = cursor.getString(mimeIndex);
                if (DIRECTORY_MIME.equals(mime) || isPageName(name)) {
                    deleteDriveDocument(cursor.getString(idIndex));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
