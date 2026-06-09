package com.gugesoft.lodibookreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Html;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.domain.SpineReference;
import nl.siegmann.epublib.domain.TOCReference;
import nl.siegmann.epublib.domain.TableOfContents;
import nl.siegmann.epublib.epub.EpubReader;

public class BookLoader {
    private static final String TAG = "BookLoader";

    public interface OnProgressListener {
        void onProgress(int current, int total, String message);
    }

    public static class TOCItem {
        public final String title;
        public final String href;
        public final int chapterIndex;

        public TOCItem(String title, String href, int chapterIndex) {
            this.title = title;
            this.href = href;
            this.chapterIndex = chapterIndex;
        }
    }

    public static class BookInfo {
        public final String title;
        public final String author;
        public final String coverUri;
        public final List<TOCItem> toc;
        public final int totalChapters;

        public BookInfo(String title, String author, String coverUri, List<TOCItem> toc, int totalChapters) {
            this.title = title != null && !title.isEmpty() ? title : "Unknown Book";
            this.author = author != null ? author : "";
            this.coverUri = coverUri;
            this.toc = toc != null ? toc : new ArrayList<>();
            this.totalChapters = totalChapters;
        }
    }

    public BookInfo extractBookInfo(Context context, Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;

            if ("text/plain".equals(mimeType) || (uri.getPath() != null && uri.getPath().toLowerCase().endsWith(".txt"))) {
                List<TOCItem> toc = new ArrayList<>();
                toc.add(new TOCItem("Contents", uri.toString(), 0));
                return new BookInfo("TXT Book", "", null, toc, 1);
            } else {
                Book book = new EpubReader().readEpub(is);
                String title = book.getMetadata().getFirstTitle();
                String author = book.getMetadata().getAuthors().isEmpty() ? "" : book.getMetadata().getAuthors().get(0).toString();
                
                String coverUri = null;
                Resource coverRes = book.getCoverImage();
                if (coverRes != null) {
                    try {
                        Bitmap bmp = BitmapFactory.decodeStream(coverRes.getInputStream());
                        if (bmp != null) coverUri = saveCoverToCache(context, bmp, title);
                    } catch (Exception ignored) {}
                }

                List<SpineReference> spine = book.getSpine().getSpineReferences();
                List<TOCItem> allChapters = new ArrayList<>();
                for (int i = 0; i < spine.size(); i++) {
                    allChapters.add(new TOCItem("Chapter " + (i + 1), spine.get(i).getResource().getHref(), i));
                }

                List<TOCItem> tocItems = new ArrayList<>();
                extractTOC(book.getTableOfContents(), tocItems, spine);
                
                // Map TOC titles to spine chapters
                for (TOCItem toc : tocItems) {
                    if (toc.chapterIndex >= 0 && toc.chapterIndex < allChapters.size()) {
                        // Update with better title if available
                        TOCItem original = allChapters.get(toc.chapterIndex);
                        if (original.title.startsWith("Chapter ")) {
                             allChapters.set(toc.chapterIndex, toc);
                        }
                    }
                }

                return new BookInfo(title, author, coverUri, allChapters, spine.size());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting book info", e);
            return null;
        }
    }

    public List<Sentence> loadChapter(Context context, Uri uri, int chapterIndex) {
        String mimeType = context.getContentResolver().getType(uri);
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;

            if ("text/plain".equals(mimeType) || (uri.getPath() != null && uri.getPath().toLowerCase().endsWith(".txt"))) {
                return loadTxt(is);
            } else {
                Book book = new EpubReader().readEpub(is);
                List<SpineReference> spine = book.getSpine().getSpineReferences();
                if (chapterIndex < 0 || chapterIndex >= spine.size()) return null;

                Resource resource = spine.get(chapterIndex).getResource();
                String html = readResource(resource);
                return processHtmlIntoSentences(html, resource.getHref(), 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading chapter " + chapterIndex, e);
            return null;
        }
    }

    private List<Sentence> loadTxt(InputStream is) {
        List<Sentence> sentences = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append(" ");
            sentences.addAll(splitIntoSentences(sb.toString(), null));
        } catch (Exception e) { Log.e(TAG, "TXT error", e); }
        return sentences;
    }

    private void extractTOC(TableOfContents toc, List<TOCItem> items, List<SpineReference> spine) {
        if (toc == null || toc.getTocReferences() == null) return;
        for (TOCReference ref : toc.getTocReferences()) {
            flattenTOC(ref, items, spine);
        }
    }

    private void flattenTOC(TOCReference ref, List<TOCItem> items, List<SpineReference> spine) {
        int chapterIdx = findChapterIndex(ref.getResource(), spine);
        items.add(new TOCItem(ref.getTitle(), ref.getCompleteHref(), chapterIdx));
        if (ref.getChildren() != null) {
            for (TOCReference child : ref.getChildren()) {
                flattenTOC(child, items, spine);
            }
        }
    }

    private int findChapterIndex(Resource res, List<SpineReference> spine) {
        if (res == null) return -1;
        for (int i = 0; i < spine.size(); i++) {
            if (spine.get(i).getResource().getHref().equals(res.getHref())) return i;
        }
        return -1;
    }

    private String readResource(Resource res) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(res.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private List<Sentence> processHtmlIntoSentences(String html, String resourceHref, int startId) {
        List<Sentence> result = new ArrayList<>();
        String markedHtml = html.replaceAll("(?i)<[^>]+id=\"([^\"]+)\"[^>]*>", "$0 [IDMARKER:$1] ");
        markedHtml = markedHtml.replaceAll("(?i)<[^>]+name=\"([^\"]+)\"[^>]*>", "$0 [IDMARKER:$1] ");

        Spanned spanned = Html.fromHtml(markedHtml, Html.FROM_HTML_MODE_LEGACY);
        String fullText = spanned.toString();
        
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(fullText);

        int idCounter = startId;
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentenceText = fullText.substring(start, end).trim();
            if (sentenceText.isEmpty()) continue;

            String link = null;
            String internalId = null;

            if (sentenceText.contains("[IDMARKER:")) {
                Pattern p = Pattern.compile("\\[IDMARKER:([^\\]\\s]+)\\]");
                Matcher m = p.matcher(sentenceText);
                if (m.find()) {
                    internalId = m.group(1);
                    sentenceText = sentenceText.replace(m.group(0), "").trim();
                }
            }
            
            if (result.isEmpty() && internalId == null) internalId = resourceHref;

            URLSpan[] spans = spanned.getSpans(start, end, URLSpan.class);
            if (spans != null && spans.length > 0) link = spans[0].getURL();

            if (!sentenceText.isEmpty()) {
                String fullInternalId = internalId;
                if (internalId != null && !internalId.contains("/") && !internalId.equals(resourceHref)) {
                    fullInternalId = resourceHref + "#" + internalId;
                }
                result.add(new Sentence(idCounter++, sentenceText, link, fullInternalId));
            }
        }
        return result;
    }

    private List<Sentence> splitIntoSentences(String text, String internalId) {
        List<Sentence> sentences = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(text);
        int id = 0;
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String s = text.substring(start, end).trim();
            if (!s.isEmpty()) sentences.add(new Sentence(id++, s, null, internalId));
        }
        return sentences;
    }

    private String saveCoverToCache(Context context, Bitmap bmp, String title) {
        try {
            File dir = new File(context.getCacheDir(), "covers");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, Math.abs(title.hashCode()) + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }
            return Uri.fromFile(file).toString();
        } catch (Exception e) { return null; }
    }
}
