package org.campusreader.notices;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Opt-in URL fallback for an adapter that has established a permanent detail URL.
 * All query keys must be declared stable; unknown keys are rejected, not stripped.
 * This class cannot establish URL permanence from an unobserved portal.
 */
public final class CanonicalUrlIdentity {
    private final String source;
    private final String origin;
    private final Pattern permanentPath;
    private final Set<String> allowedKeys;
    private final Set<String> requiredKeys;
    private static final Pattern UNSAFE_KEY = Pattern.compile(
            "(?i).*(token|ticket|session|auth|password|passwd|secret|signature|nonce|expire|timestamp|csrf|credential|redirect).*|(?i)^(sid|sig|code|key|t|ts|sso|jwt)$");

    public CanonicalUrlIdentity(String source, String origin, Pattern permanentPath,
            Set<String> stableQueryKeys, Set<String> requiredQueryKeys) {
        this.source = NoticeKey.requireText(source, "source");
        if (permanentPath == null || stableQueryKeys == null || requiredQueryKeys == null) {
            throw new IllegalArgumentException("An explicit permanent URL policy is required");
        }
        URI uri = parse(origin);
        this.origin = normalizedOrigin(uri);
        if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("origin must contain only scheme, host and port");
        }
        this.permanentPath = permanentPath;
        this.allowedKeys = Collections.unmodifiableSet(new HashSet<>(stableQueryKeys));
        this.requiredKeys = Collections.unmodifiableSet(new HashSet<>(requiredQueryKeys));
        if (!allowedKeys.containsAll(requiredKeys)) throw new IllegalArgumentException("Required keys must be allowed");
        for (String key : allowedKeys) {
            if (key == null || key.isEmpty() || UNSAFE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Temporary or unsafe query key cannot identify a notice");
            }
        }
    }

    public NoticeKey keyFor(String detailUrl) {
        URI uri = parse(detailUrl);
        if (!origin.equals(normalizedOrigin(uri)) || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Wrong origin or fragment URL; portal-specific identity required");
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || !path.equals(uri.normalize().getRawPath())
                || path.contains(";") || path.contains("%") || path.contains("\\")
                || !permanentPath.matcher(path).matches()) {
            throw new IllegalArgumentException("URL does not match the verified permanent detail path");
        }
        Map<String, String> query = new TreeMap<>();
        if (uri.getRawQuery() != null) {
            for (String item : uri.getRawQuery().split("&", -1)) {
                int equals = item.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("Ambiguous query item");
                String key = decode(item.substring(0, equals));
                String value = decode(item.substring(equals + 1));
                if (UNSAFE_KEY.matcher(key).matches() || !allowedKeys.contains(key)
                        || value.isEmpty() || query.put(key, value) != null) {
                    throw new IllegalArgumentException("Temporary, undeclared, empty or duplicate query parameter");
                }
            }
        }
        if (!query.keySet().containsAll(requiredKeys)) throw new IllegalArgumentException("Missing stable identity parameter");
        StringBuilder result = new StringBuilder(origin).append(path);
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            result.append(first ? '?' : '&').append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            first = false;
        }
        return NoticeKey.canonicalUrl(source, result.toString());
    }

    private static URI parse(String value) {
        try { return new URI(NoticeKey.requireText(value, "URL")); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid URL", e); }
    }

    private static String normalizedOrigin(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Absolute HTTP(S) URL without embedded credentials required");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port);
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
