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

    public interface OnProgressListener {
        void onProgress(int current, int total, String message);
    }

    public static class TOCItem {
        public final String title;
        public final String href;

        public TOCItem(String title, String href) {
            this.title = title;
            this.href = href;
        }
    }

    public static class BookMetadata {
        public final String title;
        public final String author;
        public final String coverUri;
        public final List<Sentence> sentences;
        public final List<TOCItem> toc;

        public BookMetadata(String title, String author, String coverUri, List<Sentence> sentences, List<TOCItem> toc) {
            this.title = title != null && !title.isEmpty() ? title : "Unknown Book";
            this.author = author != null ? author : "";
            this.coverUri = coverUri;
            this.sentences = sentences != null ? sentences : new ArrayList<>();
            this.toc = toc != null ? toc : new ArrayList<>();
        }
    }

    public BookMetadata loadBookWithMetadata(Context context, Uri uri, OnProgressListener listener) {
        String mimeType = context.getContentResolver().getType(uri);

        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return new BookMetadata("Error Opening", "", null, null, null);

            if ("text/plain".equals(mimeType) || (uri.getPath() != null && uri.getPath().toLowerCase().endsWith(".txt"))) {
                if (listener != null) listener.onProgress(0, 1, "Loading text file...");
                return new BookMetadata("TXT Book", "", null, loadTxt(is), null);
            } else {
                return loadEpubWithMetadata(context, is, listener);
            }
        } catch (Exception e) {
            Log.e("BookLoader", "Load error", e);
            return new BookMetadata("Error", "", null, null, null);
        }
    }

    private List<Sentence> loadTxt(InputStream is) {
        List<Sentence> sentences = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append(" ");
            sentences.addAll(splitIntoSentences(sb.toString(), null));
        } catch (Exception e) { Log.e("BookLoader", "TXT error", e); }
        return sentences;
    }

    private BookMetadata loadEpubWithMetadata(Context context, InputStream is, OnProgressListener listener) {
        List<Sentence> allSentences = new ArrayList<>();
        List<TOCItem> tocItems = new ArrayList<>();
        String title = "Unknown Book";
        String author = "";
        String coverUri = null;

        try {
            if (listener != null) listener.onProgress(0, 100, "Reading EPUB structure...");
            Book book = new EpubReader().readEpub(is);
            if (!book.getMetadata().getTitles().isEmpty()) title = book.getMetadata().getTitles().get(0);
            if (!book.getMetadata().getAuthors().isEmpty()) author = book.getMetadata().getAuthors().get(0).toString();

            // TOC
            extractTOC(book.getTableOfContents(), tocItems);

            // Cover
            Resource coverRes = book.getCoverImage();
            if (coverRes == null) {
                for (Resource res : book.getResources().getAll()) {
                    String href = res.getHref().toLowerCase();
                    if ((href.contains("cover") || href.contains("front")) && 
                        (href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".png") || href.endsWith(".webp"))) {
                        coverRes = res;
                        break;
                    }
                }
            }
            if (coverRes != null) {
                Bitmap bmp = BitmapFactory.decodeStream(coverRes.getInputStream());
                if (bmp != null) coverUri = saveCoverToCache(context, bmp, title);
            }

            List<SpineReference> spineReferences = book.getSpine().getSpineReferences();
            int totalChapters = spineReferences.size();
            
            for (int i = 0; i < totalChapters; i++) {
                SpineReference spine = spineReferences.get(i);
                Resource resource = spine.getResource();
                String resourceHref = resource.getHref();
                
                if (listener != null) {
                    listener.onProgress(i, totalChapters, "Processing chapter " + (i + 1) + " of " + totalChapters);
                }
                
                String html = readResource(resource);
                allSentences.addAll(processHtmlIntoSentences(html, resourceHref, allSentences.size()));
            }

        } catch (Exception e) { Log.e("BookLoader", "EPUB error", e); }

        return new BookMetadata(title, author, coverUri, allSentences, tocItems);
    }

    private void extractTOC(TableOfContents toc, List<TOCItem> items) {
        if (toc == null || toc.getTocReferences() == null) return;
        for (TOCReference ref : toc.getTocReferences()) {
            flattenTOC(ref, items);
        }
    }

    private void flattenTOC(TOCReference ref, List<TOCItem> items) {
        items.add(new TOCItem(ref.getTitle(), ref.getCompleteHref()));
        if (ref.getChildren() != null) {
            for (TOCReference child : ref.getChildren()) {
                flattenTOC(child, items);
            }
        }
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
        
        // Mark anchors so they survive tag stripping
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
