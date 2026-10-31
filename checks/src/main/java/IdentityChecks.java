import org.campusreader.notices.CanonicalUrlIdentity;
import org.campusreader.notices.NoticeKey;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Host-JVM checks for identity rules only; does not exercise Android SQLite. */
public final class IdentityChecks {
    private static int checked;
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checked++; return; }
        throw new AssertionError("Unsafe or ambiguous URL accepted");
    }
    public static void main(String[] args) {
        CanonicalUrlIdentity policy = new CanonicalUrlIdentity("test:portal", "https://example.edu",
                Pattern.compile("/notice/detail"), new HashSet<>(Arrays.asList("id", "category")),
                Collections.singleton("id"));
        NoticeKey one = policy.keyFor("https://EXAMPLE.edu:443/notice/detail?id=123&category=2");
        NoticeKey two = policy.keyFor("https://example.edu/notice/detail?category=2&id=123");
        if (!one.equals(two)) throw new AssertionError("Query order or default port changed identity");
        if (one.equals(NoticeKey.portalId("test:portal", "123"))) throw new AssertionError("Identity strategies collided");
        rejects(() -> policy.keyFor("https://example.edu/notice/detail?id=123&token=secret"));
        rejects(() -> policy.keyFor("https://example.edu/notice/detail?id=123&nonce=1"));
        rejects(() -> policy.keyFor("https://example.edu/notice/detail?id=123&unknown=1"));
        rejects(() -> policy.keyFor("https://example.edu/notice/detail?id=123&id=456"));
        rejects(() -> policy.keyFor("https://example.edu/notice/detail?category=2"));
        rejects(() -> policy.keyFor("https://example.edu/notice/detail?id=123#session"));
        rejects(() -> policy.keyFor("https://user:password@example.edu/notice/detail?id=123"));
        rejects(() -> policy.keyFor("https://evil.edu/notice/detail?id=123"));
        rejects(() -> policy.keyFor("https://example.edu/notice/detail;jsessionid=abc?id=123"));
        rejects(() -> policy.keyFor("https://example.edu/notice/detail?id="));
        rejects(() -> new CanonicalUrlIdentity("test", "https://example.edu", Pattern.compile("/detail"),
                Collections.singleton("access_token"), Collections.emptySet()));
        System.out.println("Identity checks passed: " + (checked + 2));
    }
}
