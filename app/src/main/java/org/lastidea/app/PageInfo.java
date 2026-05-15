package org.lastidea.app;

final class PageInfo {
    final String category;
    final long modifiedMillis;
    final int page;
    final String preview;
    final String title;

    PageInfo(String category, int page, String title, String preview, long modifiedMillis) {
        this.category = category;
        this.modifiedMillis = modifiedMillis;
        this.page = page;
        this.preview = preview;
        this.title = title;
    }
}
