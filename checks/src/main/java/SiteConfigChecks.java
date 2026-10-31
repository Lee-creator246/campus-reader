import org.campusreader.config.CampusSite;

/** Pure JVM policy checks. These synthetic settings are test data, never packaged app defaults. */
public final class SiteConfigChecks {
    private static int checked;
    private static final String DOMAIN = "campus.example.edu";
    private static final String VPN = "https://vpn.campus.example.edu/";
    private static final String LIST = "http://portal.campus.example.edu/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000";
    private static final String LOGIN = "https://auth.campus.example.edu/login";

    public static CampusSite.Profile fixture() {
        return CampusSite.create(DOMAIN, VPN, LIST, LOGIN);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checked++;
    }

    private static void rejects(Runnable action, String reason) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checked++; return; }
        throw new AssertionError(reason);
    }

    private static void unconfigured() {
        check(!CampusSite.isConfigured(), "New process must have no institution configured");
        check(!CampusSite.current().configured, "Empty snapshot must be marked unconfigured");
        check(CampusSite.domain().isEmpty() && CampusSite.vpnUrl().isEmpty()
                && CampusSite.portalListUrl().isEmpty() && CampusSite.portalHost().isEmpty()
                && CampusSite.loginUrl().isEmpty() && CampusSite.scopeId().isEmpty(),
                "Unconfigured accessors must not contain institution defaults");
        check(!CampusSite.allowsPortalUrl(LIST), "Unconfigured portal guard must deny");
        check(!CampusSite.allowsLoginUrl(LOGIN), "Unconfigured login guard must deny");
        check(!CampusSite.isHttpPortalUrl(LIST), "Unconfigured HTTP guard must deny");
        try { CampusSite.requireConfigured(); }
        catch (IllegalStateException expected) { checked++; return; }
        throw new AssertionError("Unconfigured use must fail before network access");
    }

    public static void main(String[] args) {
        // Deliberately check the fresh-process default before installing any fixture.
        unconfigured();
        rejects(() -> CampusSite.install(null), "Null installation must fail");
        CampusSite.Profile site = fixture();
        check(!CampusSite.isConfigured(), "Validation alone must not install a profile");
        CampusSite.install(site);
        check(CampusSite.current() == site && CampusSite.requireConfigured() == site,
                "Installed snapshot must be available atomically");
        check(CampusSite.isConfigured() && DOMAIN.equals(CampusSite.domain()), "Installed domain missing");
        check(VPN.equals(CampusSite.vpnUrl()) && LIST.equals(CampusSite.portalListUrl())
                && LOGIN.equals(CampusSite.loginUrl())
                && "portal.campus.example.edu".equals(CampusSite.portalHost()), "Installed URLs missing");

        String[] invalidDomains = {"", "localhost", "https://campus.example.edu", "campus.example.edu/path",
                "campus.example.edu:443", ".campus.example.edu", "campus..example.edu", "campus.example.edu.",
                "-campus.example.edu", "campus-.example.edu", "campus_example.edu", "*.example.edu",
                "127.0.0.1", "[::1]", "校园.example.edu", "campus.example.edu@evil.example"};
        for (String domain : invalidDomains)
            rejects(() -> CampusSite.create(domain, VPN, LIST, LOGIN), "Invalid domain accepted: " + domain);
        rejects(() -> CampusSite.create(null, VPN, LIST, LOGIN), "Null domain accepted");
        String[] invalidVpn = {"http://vpn.campus.example.edu/", "ftp://vpn.campus.example.edu/",
                "javascript:alert(1)", "https://vpn.campus.example.edu:80/", "https://vpn.campus.example.edu:8443/",
                "https://user:pass@vpn.campus.example.edu/", "https://vpn.campus.example.edu/#home",
                "https://vpn.campus.example.edu/?access_token=sample", "https://127.0.0.1/",
                "https://vpn.campus.example.edu:/", "https://vpn.campus.example.edu./"};
        for (String vpn : invalidVpn)
            rejects(() -> CampusSite.create(DOMAIN, vpn, LIST, LOGIN), "Unsafe VPN URL accepted: " + vpn);
        check(CampusSite.create(DOMAIN, "https://gateway.provider.example/", LIST, LOGIN).configured,
                "Explicitly configured external VPN provider must be supported");

        String[] invalidPortal = {"https://evil.example/list", "https://campus.example.edu.evil.example/list",
                "https://notcampus.example.edu/list", "https://portal.campus.example.edu@evil.example/list",
                "https://user@portal.campus.example.edu/list", "http://portal.campus.example.edu:443/list",
                "https://portal.campus.example.edu:80/list", "file:///portal/list", "//portal.campus.example.edu/list",
                "https://portal.campus.example.edu/list#route", "https://portal.campus.example.edu/list%0d%0aInjected",
                "https://portal.campus.example.edu/%5cevil", "https://portal.campus.example.edu/list?x=%GG",
                "https://portal.campus.example.edu/list?", "https://portal.campus.example.edu/list?=value"};
        for (String portal : invalidPortal)
            rejects(() -> CampusSite.create(DOMAIN, VPN, portal, LOGIN), "Unsafe portal URL accepted: " + portal);

        String[] secretSuffixes = {"?token=sample", "?ticket=ST-sample", "?Cookie=sample", "?JSESSIONID=sample",
                "?password=sample", "?access_token=sample", "?api_key=sample", "?code=sample", "?username=sample",
                "?%74oken=sample", "?%2574oken=sample", "?x=Cookie%3Dsample", "?x=Bearer%20sample",
                "?service=https%3A%2F%2Fauth.campus.example.edu%2Flogin%3Fticket%3DST-sample",
                "?service=https%3A%2F%2Fuser%3Apass%40auth.campus.example.edu%2Flogin",
                ";jsessionid=sample", "%3Bjsessionid%3Dsample", "?x=%250aInjected"};
        for (String suffix : secretSuffixes) {
            rejects(() -> CampusSite.create(DOMAIN, VPN, "https://portal.campus.example.edu/list" + suffix, LOGIN),
                    "Saved credential or malformed query accepted: " + suffix);
        }
        rejects(() -> CampusSite.create(DOMAIN, VPN, LIST, "https://auth.campus.example.edu/#access_token=sample"),
                "Saved login fragment must not contain a token");
        rejects(() -> CampusSite.create(DOMAIN, VPN, LIST, "https://auth.campus.example.edu/login?ticket=sample"),
                "Saved login entry must not contain a one-time ticket");
        rejects(() -> CampusSite.create(DOMAIN, VPN, LIST, "http://auth.campus.example.edu/login"),
                "HTTP login on another campus host accepted");
        rejects(() -> CampusSite.create(DOMAIN, VPN, LIST, "https://campus.example.edu.evil.example/login"),
                "Login domain suffix attack accepted");
        check(CampusSite.current() == site, "Rejected edits must preserve active configuration");

        CampusSite.Profile blankLogin = CampusSite.create(DOMAIN, VPN, LIST, "  ");
        check(LIST.equals(blankLogin.loginUrl), "Blank login must default to the portal list");
        check(blankLogin.scopeId.equals(CampusSite.create(DOMAIN, VPN, LIST, null).scopeId),
                "Missing login defaults must be stable");
        check(blankLogin.scopeId.equals(CampusSite.create(DOMAIN, VPN, LIST, LIST).scopeId),
                "Explicit default login must preserve scope");
        CampusSite.Profile spa = CampusSite.create(DOMAIN, VPN, LIST, "https://portal.campus.example.edu/#/login");
        check(spa.loginUrl.endsWith("#/login"), "Saved SPA login route must survive normalization");
        check(CampusSite.create(DOMAIN, VPN, LIST,
                "https://auth.campus.example.edu/login?service=https%3A%2F%2Fportal.campus.example.edu%2F").configured,
                "Safe encoded CAS service URL must be accepted");
        check(site.scopeId.equals(CampusSite.create(" CAMPUS.EXAMPLE.EDU ",
                "HTTPS://VPN.CAMPUS.EXAMPLE.EDU:443", "HTTP://PORTAL.CAMPUS.EXAMPLE.EDU:80/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000",
                "HTTPS://AUTH.CAMPUS.EXAMPLE.EDU:443/login").scopeId),
                "Domain/URL case, root slash and default ports must normalize to the same scope");
        check(!site.scopeId.equals(CampusSite.create("other.example.edu", "https://vpn.other.example.edu/",
                "http://portal.other.example.edu/list", "").scopeId), "Different institutions must have separate scopes");
        check(!site.scopeId.equals(CampusSite.create(DOMAIN, VPN, LIST + "&page=2", LOGIN).scopeId),
                "Different portal configuration must have a distinct scope");
        check(!site.scopeId.equals(CampusSite.create(DOMAIN, "https://another-vpn.campus.example.edu/", LIST, LOGIN).scopeId),
                "Different authentication server must have a distinct scope");
        check(!site.scopeId.equals(spa.scopeId), "Different login endpoint must have a distinct scope");

        check(CampusSite.allowsPortalUrl("https://PORTAL.campus.example.edu:443/detail?id=42"), "Safe HTTPS portal rejected");
        check(CampusSite.allowsPortalUrl("http://portal.campus.example.edu:80/detail?id=42"), "Safe HTTP portal rejected");
        check(CampusSite.isHttpPortalUrl(LIST), "Cleartext portal classification failed");
        check(!CampusSite.isHttpPortalUrl("https://portal.campus.example.edu/detail"), "HTTPS classified as cleartext");
        check(CampusSite.allowsLoginUrl("https://auth.campus.example.edu/login?ticket=ST-sample"),
                "Runtime login redirects must accept short-lived tickets");
        check(CampusSite.allowsLoginUrl("https://portal.campus.example.edu/#/login"), "SPA login route rejected at runtime");
        check(CampusSite.allowsLoginUrl("https://portal.campus.example.edu/#access_token=runtime-sample"),
                "Runtime auth fragment must remain separate from saved configuration policy");
        check(CampusSite.allowsLoginUrl("https://campus.example.edu/login"), "Campus apex HTTPS login rejected");
        check(CampusSite.allowsLoginUrl("http://portal.campus.example.edu/login"), "Same portal HTTP login rejected");
        check(!CampusSite.allowsPortalUrl("https://auth.campus.example.edu/detail"), "Other campus host accepted as portal");
        check(!CampusSite.allowsPortalUrl("https://portal.campus.example.edu/detail#fragment"), "Portal fragment accepted");
        check(!CampusSite.allowsLoginUrl("http://auth.campus.example.edu/login"), "HTTP campus login accepted off portal host");
        String[] unsafeRuntime = {"https://portal.campus.example.edu.evil.example/", "https://notcampus.example.edu/",
                "https://portal.campus.example.edu@evil.example/", "https://user@portal.campus.example.edu/",
                "https://portal.campus.example.edu:8443/", "http://portal.campus.example.edu:443/",
                "https://portal.campus.example.edu:80/", "https://portal.campus.example.edu./",
                "https://portal.campus.example.edu\\@evil.example/", "https://portal.campus.example.edu/%0a",
                "javascript:alert(1)", "data:text/html,hello", "file:///etc/passwd", "//portal.campus.example.edu/", "", null};
        for (String value : unsafeRuntime) {
            check(!CampusSite.allowsPortalUrl(value), "Unsafe runtime portal URL accepted: " + value);
            check(!CampusSite.allowsLoginUrl(value), "Unsafe runtime login URL accepted: " + value);
            check(!CampusSite.isHttpPortalUrl(value), "Unsafe URL classified as HTTP portal: " + value);
        }
        CampusSite.clear();
        unconfigured();
        check(site.configured && site.allowsPortalUrl(LIST), "Existing immutable operation snapshot was mutated by clear");
        System.out.println("Site configuration checks passed: " + checked);
    }
}
