import org.campusreader.config.CampusConfigImport;
import org.campusreader.config.CampusSite;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Synthetic documents: validate before applying, with no mutation or credential imports. */
public final class ConfigImportChecks {
    private static int checked;
    private static final String DOCUMENT = "{\"format\":\"campus-reader-site\",\"version\":1,"
            + "\"domain\":\"campus.example.edu\",\"vpnUrl\":\"https://vpn.campus.example.edu/\","
            + "\"portalListUrl\":\"https://portal.campus.example.edu/list?category=1000\"}";
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checked++; }
    private static void rejects(String document, String message) {
        try { CampusConfigImport.parse(document); }
        catch (IllegalArgumentException expected) { checked++; return; }
        throw new AssertionError(message);
    }
    private static String extra(String field) { return DOCUMENT.substring(0, DOCUMENT.length()-1) + "," + field + "}"; }
    private static String repeat(char c, int count) { char[] text = new char[count]; java.util.Arrays.fill(text,c); return new String(text); }
    public static void main(String[] args) throws Exception {
        CampusSite.clear();
        CampusSite.Profile candidate = CampusConfigImport.parse(DOCUMENT);
        check(!CampusSite.isConfigured(), "Preview must not install a valid document");
        check(candidate.configured && candidate.loginUrl.equals(candidate.portalListUrl), "Optional login defaults incorrectly");
        CampusSite.install(candidate);
        check(CampusConfigImport.parse("\ufeff \n" + DOCUMENT + "\r\n").scopeId.equals(candidate.scopeId), "UTF-8 BOM/JSON whitespace rejected");
        check(CampusConfigImport.parse(DOCUMENT.replace("campus.example.edu", "CAMPUS.EXAMPLE.EDU")).scopeId.equals(candidate.scopeId), "Import must preserve normalized site identity");
        check(CampusConfigImport.parse(extra("\"loginUrl\":\"https://sso.campus.example.edu/#/login\"")).loginUrl.endsWith("#/login"), "Valid portal route lost");
        check(CampusConfigImport.read(new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8))).scopeId.equals(candidate.scopeId), "File and paste must use same policy");
        rejects(null, "Null document accepted");
        String[] malformed = {"", "{}", "[]", "null", "true", "1", DOCUMENT + "{}", DOCUMENT + " trailing",
                DOCUMENT.substring(0,DOCUMENT.length()-1), DOCUMENT + ",", DOCUMENT.replace(",\"version\"", ",,\"version\""),
                DOCUMENT.substring(0,DOCUMENT.length()-1)+",}", DOCUMENT.replace("\"version\":1", "\"version\":\"1\""),
                DOCUMENT.replace("\"version\":1", "\"version\":1.0"), DOCUMENT.replace("\"version\":1", "\"version\":1e0"),
                DOCUMENT.replace("\"version\":1", "\"version\":01"), DOCUMENT.replace("\"version\":1", "\"version\":-1"),
                DOCUMENT.replace("\"version\":1", "\"version\":0"), DOCUMENT.replace("\"version\":1", "\"version\":2"),
                DOCUMENT.replace("campus-reader-site", "another-product"), DOCUMENT.replace("\"domain\":\"campus.example.edu\",", ""),
                DOCUMENT.replace("\"domain\":\"campus.example.edu\"", "\"domain\":null"),
                DOCUMENT.replace("\"domain\":\"campus.example.edu\"", "\"domain\":{}"),
                DOCUMENT.replace("\"domain\":\"campus.example.edu\"", "\"domain\":[\"campus.example.edu\"]"),
                DOCUMENT.replace("campus.example.edu\",\"vpnUrl", "campus.example.edu\\n\",\"vpnUrl"),
                DOCUMENT.replace("campus.example.edu\",\"vpnUrl", "campus.example.edu\n\",\"vpnUrl"),
                DOCUMENT.replace("\"domain\"", "\"do\\uZZZZmain\""), DOCUMENT.replace("\"domain\"", "\"do\\uD800main\""),
                DOCUMENT.replace("\"domain\"", "\"do\\uDC00main\""), DOCUMENT.replace("\"domain\"", "\"do\\x6dain\""),
                DOCUMENT.replace("\"domain\"", "\"Domain\""), "/* comment */" + DOCUMENT, DOCUMENT.replace("{", "{ // comment\n")};
        for (String text : malformed) rejects(text, "Malformed/unsupported document accepted");
        String[] unknownKeys = {"password", "Cookie", "token", "ticket", "account", "username", "apiKey", "consent",
                "connection_sdk_consent", "restore_allowed", "sdk_scope", "siteName", "extra"};
        for(String key:unknownKeys) rejects(extra("\""+key+"\":\"sample\""), "Unknown/private field accepted: "+key);
        rejects(extra("\"domain\":\"other.example.edu\""), "Duplicate domain accepted");
        rejects(extra("\"do\\u006dain\":\"other.example.edu\""), "Escaped duplicate key accepted");
        rejects(extra("\"version\":1"), "Duplicate version accepted");
        String[] unsafeQueries = {"token=sample", "%74oken=sample", "%2574oken=sample", "ticket=sample", "password=sample",
                "Cookie=sample", "username=sample", "x=Cookie%3Dsample", "x=Bearer%20sample", "x=%250aInjected"};
        for(String query:unsafeQueries) rejects(DOCUMENT.replace("category=1000",query), "Credential query accepted");
        rejects(DOCUMENT.replace("https://vpn.", "http://vpn."), "Insecure VPN accepted");
        rejects(DOCUMENT.replace("https://portal.campus.example.edu/", "https://portal.campus.example.edu.evil.example/"), "Spoofed portal accepted");
        rejects(DOCUMENT.replace("https://portal.", "https://user:pass@portal."), "Embedded credentials accepted");
        rejects(DOCUMENT.replace("/list?", ":8443/list?"), "Nonstandard port accepted");
        rejects(extra("\"loginUrl\":\"http://sso.campus.example.edu/login\""), "Off-portal cleartext login accepted");
        rejects(extra("\"loginUrl\":\"https://sso.campus.example.edu/login?ticket=sample\""), "Saved login ticket accepted");
        rejects(DOCUMENT.replace("category=1000", "category="+repeat('a',CampusConfigImport.MAX_URL_LENGTH)), "Oversize URL accepted");
        rejects(repeat(' ',CampusConfigImport.MAX_BYTES)+DOCUMENT, "Oversize paste accepted");
        rejects(repeat('\u4e2d',CampusConfigImport.MAX_BYTES/2), "UTF-8 byte limit ignored");
        try { CampusConfigImport.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,(byte)0x28})); throw new AssertionError("Malformed UTF-8 accepted"); }
        catch(IllegalArgumentException expected) { checked++; }
        try { CampusConfigImport.read(new ByteArrayInputStream(new byte[CampusConfigImport.MAX_BYTES+1])); throw new AssertionError("Oversize file accepted"); }
        catch(IllegalArgumentException expected) { checked++; }
        try { CampusConfigImport.read(new java.io.InputStream(){@Override public int read() throws IOException {throw new IOException("synthetic");}}); throw new AssertionError("Read failure swallowed"); }
        catch(IOException expected) { checked++; }
        check(CampusSite.current()==candidate, "Import/rejection must never replace active configuration");
        CampusSite.clear();
        System.out.println("Configuration import checks passed: "+checked);
    }
}
