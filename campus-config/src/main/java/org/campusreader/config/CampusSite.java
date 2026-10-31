package org.campusreader.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Runtime connection settings. An application starts without any institution configured. */
public final class CampusSite {
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:^|[?&;\\s/#])(?:[a-z0-9_.-]*(?:password|passwd|token|ticket|cookie|session|secret|credential)[a-z0-9_.-]*|pwd|authorization|auth|otp|code|username|account|email|api[-_]?key|access[-_]?key)\\s*=");
    private static final Profile EMPTY = new Profile();
    private static volatile Profile active = EMPTY;

    private CampusSite() { }

    /** A complete, immutable configuration; readers may keep this snapshot during an operation. */
    public static final class Profile {
        public final String domain;
        public final String vpnUrl;
        public final String portalListUrl;
        public final String portalHost;
        public final String loginUrl;
        public final String scopeId;
        public final boolean configured;

        private Profile() {
            domain = vpnUrl = portalListUrl = portalHost = loginUrl = scopeId = "";
            configured = false;
        }

        private Profile(String domain, String vpnUrl, String portalListUrl, String portalHost, String loginUrl) {
            this.domain = domain;
            this.vpnUrl = vpnUrl;
            this.portalListUrl = portalListUrl;
            this.portalHost = portalHost;
            this.loginUrl = loginUrl;
            this.scopeId = scopeFor(domain, vpnUrl, portalListUrl, loginUrl);
            this.configured = true;
        }

        public boolean allowsPortalUrl(String value) {
            if (!configured) return false;
            try {
                URI uri = parseUrl(value, false);
                return portalHost.equals(lower(uri.getHost()));
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        public boolean allowsLoginUrl(String value) {
            if (!configured) return false;
            try {
                URI uri = parseUrl(value, true);
                return loginHostAllowed(uri, domain, portalHost);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        public boolean isHttpPortalUrl(String value) {
            if (!allowsPortalUrl(value)) return false;
            return "http".equalsIgnoreCase(URI.create(value.trim()).getScheme());
        }
    }

    public static Profile current() { return active; }
    public static void install(Profile profile) {
        if (profile == null) throw new IllegalArgumentException("校园配置不能为空");
        active = profile;
    }
    public static void clear() { active = EMPTY; }
    public static boolean isConfigured() { return active.configured; }
    public static String domain() { return active.domain; }
    public static String vpnUrl() { return active.vpnUrl; }
    public static String portalListUrl() { return active.portalListUrl; }
    public static String portalHost() { return active.portalHost; }
    public static String loginUrl() { return active.loginUrl; }
    public static String scopeId() { return active.scopeId; }
    public static boolean allowsLoginUrl(String value) { return active.allowsLoginUrl(value); }
    public static boolean allowsPortalUrl(String value) { return active.allowsPortalUrl(value); }
    public static boolean isHttpPortalUrl(String value) { return active.isHttpPortalUrl(value); }

    public static Profile requireConfigured() {
        Profile profile = active;
        if (!profile.configured) throw new IllegalStateException("请先在校园设置中填写认证和信息门户地址");
        return profile;
    }

    /** Validate all values before installing; a failed edit cannot replace the active profile. */
    public static Profile create(String domain, String vpnUrl, String portalListUrl, String loginUrl) {
        String normalizedDomain = validateDomain(domain);
        URI vpn = parseConfiguredUrl(vpnUrl, false);
        if (!"https".equalsIgnoreCase(vpn.getScheme()))
            throw new IllegalArgumentException("认证服务器必须使用 HTTPS");
        // An explicitly configured VPN service may use a provider's domain.
        URI portal = parseConfiguredUrl(portalListUrl, false);
        String portalHost = lower(portal.getHost());
        if (!isUnderDomain(portalHost, normalizedDomain))
            throw new IllegalArgumentException("门户地址必须属于填写的校园域名");
        URI login = loginUrl == null || loginUrl.trim().isEmpty()
                ? portal : parseConfiguredUrl(loginUrl, true);
        if (!loginHostAllowed(login, normalizedDomain, portalHost))
            throw new IllegalArgumentException("登录地址须使用校园域名内的 HTTPS，或门户同主机的 HTTP");
        return new Profile(normalizedDomain, normalize(vpn), normalize(portal), portalHost, normalize(login));
    }

    private static boolean loginHostAllowed(URI uri, String domain, String portalHost) {
        return "https".equalsIgnoreCase(uri.getScheme())
                ? isUnderDomain(lower(uri.getHost()), domain)
                : portalHost.equals(lower(uri.getHost()));
    }

    private static boolean isUnderDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String validateDomain(String value) {
        if (value == null) throw new IllegalArgumentException("请输入校园域名");
        String domain = lower(value.trim());
        if (domain.length() > 253 || domain.indexOf('.') < 1)
            throw new IllegalArgumentException("请输入完整域名，不要包含协议、路径或端口");
        String[] labels = domain.split("\\.", -1);
        for (String label : labels) {
            if (!DNS_LABEL.matcher(label).matches())
                throw new IllegalArgumentException("域名格式不正确；国际化域名请使用 ASCII 格式");
        }
        if (labels[labels.length - 1].matches("[0-9]+"))
            throw new IllegalArgumentException("请使用域名，不要填写 IP 地址");
        return domain;
    }

    /** No network lookups: validate the URI's actual authority, not a text prefix. */
    private static URI parseUrl(String value, boolean allowFragment) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("请输入完整网址");
        String text = value.trim();
        if (text.length() > 8192) throw new IllegalArgumentException("网址过长");
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i)) || Character.isISOControl(text.charAt(i)))
                throw new IllegalArgumentException("网址不能包含空白或控制字符");
        }
        final URI uri;
        try { uri = new URI(text); }
        catch (URISyntaxException invalid) { throw new IllegalArgumentException("网址格式不正确"); }
        String scheme = uri.getScheme() == null ? "" : lower(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme))
            throw new IllegalArgumentException("网址须使用 HTTP 或 HTTPS");
        if (uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                || (!allowFragment && uri.getRawFragment() != null))
            throw new IllegalArgumentException("网址不能包含账号密码、非法主机或片段");
        String host = validateDomain(uri.getHost());
        int port = uri.getPort();
        if (port != -1 && port != ("https".equals(scheme) ? 443 : 80))
            throw new IllegalArgumentException("网址只能使用对应协议的标准端口");
        String expectedAuthority = host + (port == -1 ? "" : ":" + port);
        if (!expectedAuthority.equalsIgnoreCase(uri.getRawAuthority()))
            throw new IllegalArgumentException("网址主机或端口格式不正确");
        validateEscapes(uri.getRawPath());
        validateEscapes(uri.getRawQuery());
        validateEscapes(uri.getRawFragment());
        return uri;
    }

    private static void validateEscapes(String part) {
        if (part == null) return;
        for (int i = 0; i + 2 < part.length(); i++) {
            if (part.charAt(i) != '%') continue;
            int octet = Character.digit(part.charAt(i + 1), 16) * 16 + Character.digit(part.charAt(i + 2), 16);
            if (octet <= 31 || octet == 127 || octet == 92)
                throw new IllegalArgumentException("网址不能包含编码后的控制字符或反斜线");
            i += 2;
        }
    }

    private static URI parseConfiguredUrl(String value, boolean allowFragment) {
        URI uri = parseUrl(value, allowFragment);
        rejectSecretText(uri.getRawPath());
        rejectSecretText(uri.getRawFragment());
        validateConfigQuery(uri.getRawQuery());
        return uri;
    }

    private static void validateConfigQuery(String query) {
        if (query == null) return;
        rejectSecretText(query);
        for (String part : query.split("[&;]", -1)) {
            if (part.isEmpty()) throw new IllegalArgumentException("网址查询参数不能为空");
            int separator = part.indexOf('=');
            String key = decode(part.substring(0, separator < 0 ? part.length() : separator));
            if (key.isEmpty() || isSecretKey(key))
                throw new IllegalArgumentException("请填写固定入口网址，不要包含账号、Cookie、令牌或登录票据");
            if (separator >= 0) rejectSecretText(part.substring(separator + 1));
        }
    }

    private static boolean isSecretKey(String key) {
        String compact = lower(key).replaceAll("[^a-z0-9]", "");
        return compact.matches(".*(?:password|passwd|token|ticket|cookie|session|secret|credential).*")
                || compact.matches("pwd|authorization|auth|otp|code|username|account|email|apikey|accesskey");
    }

    private static void rejectSecretText(String raw) {
        if (raw == null) return;
        String value = decode(raw);
        if (SECRET_ASSIGNMENT.matcher(value).find() || lower(value).trim().startsWith("bearer "))
            throw new IllegalArgumentException("请填写固定入口网址，不要包含账号、Cookie、令牌或登录票据");
        if (value.contains("://")) {
            try {
                URI nested = new URI(value);
                if (nested.getRawUserInfo() != null)
                    throw new IllegalArgumentException("网址不能包含嵌入的账号密码");
            } catch (URISyntaxException ignored) {
                // A query or path need not itself be a URI; its fields are checked separately.
            }
        }
    }

    private static String decode(String value) {
        String decoded = value;
        for (int i = 0; i < 4 && (decoded.indexOf('%') >= 0 || (i == 0 && decoded.indexOf('+') >= 0)); i++) {
            try { decoded = URLDecoder.decode(decoded, "UTF-8"); }
            catch (Exception invalid) { throw new IllegalArgumentException("网址参数编码不正确"); }
            for (int j = 0; j < decoded.length(); j++) {
                if (Character.isISOControl(decoded.charAt(j)) || decoded.charAt(j) == '\\')
                    throw new IllegalArgumentException("网址参数不能包含控制字符或反斜线");
            }
        }
        if (decoded.matches(".*%[0-9a-fA-F]{2}.*"))
            throw new IllegalArgumentException("网址参数编码层数过多");
        return decoded;
    }

    private static String normalize(URI uri) {
        URI normalized = uri.normalize();
        String path = normalized.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return URI.create(lower(uri.getScheme()) + "://" + lower(uri.getHost()) + path
                + (uri.getRawQuery() == null || uri.getRawQuery().isEmpty() ? "" : "?" + uri.getRawQuery())
                + (uri.getRawFragment() == null || uri.getRawFragment().isEmpty() ? "" : "#" + uri.getRawFragment()))
                .toASCIIString();
    }

    private static String scopeFor(String domain, String vpn, String portal, String login) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("campus-site-v1\n" + domain + "\n" + vpn + "\n" + portal + "\n" + login)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("site-");
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String lower(String value) { return value.toLowerCase(Locale.ROOT); }
}
