package org.campusreader.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded, flat, versioned setup document. Parsing never installs or saves a site. */
public final class CampusConfigImport {
    public static final String FORMAT = "campus-reader-site";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 16 * 1024;
    public static final int MAX_URL_LENGTH = 4096;
    private CampusConfigImport() {}

    public static CampusSite.Profile read(InputStream input) throws IOException {
        if (input == null) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()))) != -1) {
            if (count == 0) throw new IOException("无法读取配置文件");
            bytes.write(buffer, 0, count);
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("配置文件不能超过 16 KiB");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return parse(text);
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("配置文件须使用 UTF-8 编码");
        }
    }

    public static CampusSite.Profile parse(String text) {
        if (text == null || text.length() > MAX_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("配置内容不能为空且不能超过 16 KiB");
        Map<String, String> fields = new Parser(text).object();
        if (!FORMAT.equals(fields.get("format"))) throw new IllegalArgumentException("不是校园阅读站点配置");
        if (!Integer.toString(VERSION).equals(fields.get("version")))
            throw new IllegalArgumentException("暂不支持此配置版本，请使用 version: 1");
        String domain = required(fields, "domain", 253);
        String vpn = required(fields, "vpnUrl", MAX_URL_LENGTH);
        String list = required(fields, "portalListUrl", MAX_URL_LENGTH);
        String login = fields.get("loginUrl");
        if (login != null && login.length() > MAX_URL_LENGTH) throw invalid();
        return CampusSite.create(domain, vpn, list, login);
    }

    private static String required(Map<String, String> fields, String key, int limit) {
        String value = fields.get(key);
        if (value == null || value.trim().isEmpty() || value.length() > limit) throw invalid();
        return value;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("配置格式不正确：仅接受 format、version、domain、vpnUrl、portalListUrl 和可选 loginUrl，不可含账号或登录凭据");
    }

    /** Deliberately accepts only this schema's flat JSON object, with no duplicate/unknown keys. */
    private static final class Parser {
        final String text;
        int index;
        Parser(String text) { this.text = text; if (text.startsWith("\ufeff")) index = 1; }
        Map<String, String> object() {
            Map<String, String> fields = new LinkedHashMap<>();
            expect('{');
            if (take('}')) throw invalid();
            do {
                String key = string();
                if (!(key.equals("format") || key.equals("version") || key.equals("domain")
                        || key.equals("vpnUrl") || key.equals("portalListUrl") || key.equals("loginUrl"))
                        || fields.containsKey(key)) throw invalid();
                expect(':');
                String value;
                if (key.equals("version")) {
                    whitespace();
                    int start = index;
                    while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
                    value = text.substring(start, index);
                    if (value.isEmpty() || value.length() > 3 || (value.length() > 1 && value.charAt(0) == '0')) throw invalid();
                } else value = string();
                fields.put(key, value);
            } while (take(','));
            expect('}');
            whitespace();
            if (index != text.length()) throw invalid();
            return fields;
        }
        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            boolean closed = false;
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') { closed = true; break; }
                if (c == '\\') {
                    if (index == text.length()) throw invalid();
                    char escape = text.charAt(index++);
                    if (escape == '"' || escape == '\\' || escape == '/') c = escape;
                    else if (escape == 'u') {
                        if (index + 4 > text.length()) throw invalid();
                        int value = 0;
                        for (int i = 0; i < 4; i++) {
                            char digit = text.charAt(index++);
                            int hex = digit <= 127 ? Character.digit(digit, 16) : -1;
                            if (hex < 0) throw invalid();
                            value = value * 16 + hex;
                        }
                        c = (char) value;
                    } else throw invalid(); // No controls are meaningful in a saved URL or schema key.
                }
                if (Character.isISOControl(c)) throw invalid();
                out.append(c);
            }
            if (!closed) throw invalid();
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == out.length() || !Character.isLowSurrogate(out.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(c)) throw invalid();
            }
            return out.toString();
        }
        boolean take(char c) {
            whitespace();
            if (index < text.length() && text.charAt(index) == c) { index++; return true; }
            return false;
        }
        void expect(char c) { if (!take(c)) throw invalid(); }
        void whitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
                index++;
            }
        }
    }
}
