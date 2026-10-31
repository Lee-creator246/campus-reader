package org.campusreader.notices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;

/** Content supplied by a real portal adapter; publication time is epoch milliseconds. */
public final class CampusNotice {
    public final NoticeKey key;
    public final String title;
    public final long publishedAtMillis;
    public final String bodyHtml;
    public final String originalUrl;
    public final List<String> imageUrls;
    public final List<Attachment> attachments;

    public CampusNotice(NoticeKey key, String title, long publishedAtMillis, String bodyHtml,
            String originalUrl, List<String> imageUrls, List<Attachment> attachments) {
        if (key == null || imageUrls == null || attachments == null) throw new IllegalArgumentException("Missing notice fields");
        if (publishedAtMillis < 0) throw new IllegalArgumentException("Negative publication time");
        this.key = key;
        this.title = NoticeKey.requireText(title, "title");
        this.publishedAtMillis = publishedAtMillis;
        if (bodyHtml == null) throw new IllegalArgumentException("bodyHtml must not be null");
        this.bodyHtml = bodyHtml;
        this.originalUrl = httpUrl(originalUrl);
        List<String> images = new ArrayList<>();
        for (String image : imageUrls) images.add(httpUrl(image));
        this.imageUrls = Collections.unmodifiableList(images);
        List<Attachment> copiedAttachments = new ArrayList<>(attachments);
        if (copiedAttachments.contains(null)) throw new IllegalArgumentException("Null attachment");
        this.attachments = Collections.unmodifiableList(copiedAttachments);
    }

    public boolean hasImage() { return !imageUrls.isEmpty(); }

    static String httpUrl(String value) {
        try {
            URI uri = new URI(NoticeKey.requireText(value, "URL"));
            if (uri.getHost() == null || uri.getRawUserInfo() != null ||
                    !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Only absolute HTTP(S) URLs without embedded credentials are supported");
            }
            return value;
        } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid resource URL", e); }
    }

    public static final class Attachment {
        public final String name;
        public final String url;
        public final String mimeType;
        public Attachment(String name, String url, String mimeType) {
            this.name = NoticeKey.requireText(name, "attachment name");
            this.url = httpUrl(url);
            this.mimeType = mimeType == null ? "" : mimeType;
        }
    }
}
