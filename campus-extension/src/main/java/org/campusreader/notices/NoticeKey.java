package org.campusreader.notices;

import java.util.Objects;

/** Stable portal identity, scoped to one source. Never use a title or body hash. */
public final class NoticeKey {
    public final String source;
    public final String stableId;

    private NoticeKey(String source, String stableId) {
        this.source = requireText(source, "source");
        this.stableId = requireText(stableId, "stableId");
    }

    public static NoticeKey portalId(String source, String portalId) {
        return new NoticeKey(source, "id:" + requireText(portalId, "portalId"));
    }

    static NoticeKey canonicalUrl(String source, String canonicalUrl) {
        return new NoticeKey(source, "url:" + canonicalUrl);
    }

    static NoticeKey stored(String source, String storedId) {
        return new NoticeKey(source, storedId);
    }

    static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof NoticeKey)) return false;
        NoticeKey key = (NoticeKey) other;
        return source.equals(key.source) && stableId.equals(key.stableId);
    }

    @Override public int hashCode() { return Objects.hash(source, stableId); }
}
