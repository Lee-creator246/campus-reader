package org.campusreader.reader;

import org.campusreader.config.CampusSite;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.*;

/** Pure validation helpers, also exercised by host-JVM adversarial transfer checks. */
final class MediaSafety {
    static final class Rejected extends IOException {
        final String code;
        Rejected(String code){super(code);this.code=code;}
    }
    static String failureCode(Throwable failure) {
        if(failure instanceof Rejected)return ((Rejected)failure).code;
        if(failure instanceof java.net.SocketTimeoutException)return "TIMEOUT";
        if(failure instanceof java.net.UnknownHostException)return "DNS";
        if(failure instanceof javax.net.ssl.SSLException)return "TLS";
        if(failure instanceof java.net.ConnectException)return "CONNECT";
        if(failure instanceof EOFException)return "TRUNCATED";
        if(failure instanceof IOException)return "IO";
        return "INTERNAL";
    }
    /** Same profile contract as ReaderActivity: never use a raw SDK account as a media namespace. */
    static String readerProfile(String account) {
        String site=CampusSite.scopeId();
        if(site.isEmpty()||account==null||account.trim().isEmpty())return "local";
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((site+'\0'+account.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(Locale.ROOT,"%02x",value&255));
            return "campus:"+hex;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static long copyBounded(InputStream input, OutputStream output, long limit, long declared) throws IOException {
        if (limit <= 0 || declared > limit) throw new Rejected("SIZE_LIMIT");
        byte[] buffer = new byte[8192]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - total) throw new Rejected("SIZE_LIMIT");
            output.write(buffer, 0, count); total += count;
        }
        if (declared >= 0 && total != declared) throw new Rejected("TRUNCATED");
        return total;
    }
    static boolean campus(URL url) {
        return url!=null && CampusSite.allowsPortalUrl(url.toString());
    }
    static URL checked(String address, boolean campus) throws IOException {
        URL url = new URL(address);
        try {if(url.toURI().getHost()==null)throw new Rejected("URL_POLICY");}
        catch(URISyntaxException invalid){throw new Rejected("URL_POLICY");}
        if (url.getUserInfo() != null || url.getHost().isEmpty() || url.getRef() != null) throw new Rejected("URL_POLICY");
        if (campus) {
            if (!campus(url)) throw new Rejected("ORIGIN_CHANGED");
        } else if (!"https".equals(url.getProtocol()) || (url.getPort() != -1 && url.getPort() != 443)
                || !url.getHost().contains(".") || url.getHost().matches("[0-9.]+") || url.getHost().contains(":")) {
            throw new Rejected("URL_POLICY");
        }
        return url;
    }
    static URL redirect(URL original, URL current, String location, boolean campus) throws IOException {
        URL next = checked(new URL(current, location).toString(), campus);
        if ("https".equals(current.getProtocol()) && !"https".equals(next.getProtocol())) throw new Rejected("HTTPS_DOWNGRADE");
        if (!campus && !original.getHost().equalsIgnoreCase(next.getHost())) throw new Rejected("ORIGIN_CHANGED");
        return next;
    }
    static boolean looksHtml(String mime, byte[] prefix) {
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (type.contains("text/html") || type.contains("xhtml")) return true;
        String text = new String(prefix, StandardCharsets.UTF_8).replace("\uFEFF", "").replace("\u0000", "").trim().toLowerCase(Locale.ROOT);
        return text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head")
                || text.startsWith("<body") || text.startsWith("<form") || text.startsWith("<script")
                || (text.startsWith("<!--") && text.contains("<html"))
                || (text.startsWith("<?xml") && text.contains("<html"));
    }
    static String filename(String disposition, String suggested) {
        String result = suggested;
        if (disposition != null) {
            Matcher encoded = Pattern.compile("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").matcher(disposition);
            Matcher basic = Pattern.compile("(?i)filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))").matcher(disposition);
            if (encoded.find()) {
                try { result = URLDecoder.decode(encoded.group(1).trim().replace("+", "%2B"), "UTF-8"); }
                catch (Exception ignored) { /* Fall back to caller-provided display name. */ }
            } else if (basic.find()) result = basic.group(1) == null ? basic.group(2) : basic.group(1);
        }
        if (result == null) result = "附件";
        result = result.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (result.isEmpty()) result = "附件";
        return result.length() > 120 ? result.substring(0, 120) : result;
    }
    static String mime(String header, String filename) {
        String value = header == null ? "" : header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") ? value : "application/octet-stream";
    }
}
