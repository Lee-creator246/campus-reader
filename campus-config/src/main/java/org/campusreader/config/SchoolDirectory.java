package org.campusreader.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Data-only directory. Templates never install a site or authorize credential submission. */
public final class SchoolDirectory {
    public static final int MAX_BYTES=256*1024, MAX_SCHOOLS=100;
    public static final String FORMAT="campus-reader-schools";
    public static final class Entry {
        public final String id,name;
        public final CampusSite.Profile profile;
        Entry(String id,String name,CampusSite.Profile profile){this.id=id;this.name=name;this.profile=profile;}
    }
    public final List<Entry> schools;
    private SchoolDirectory(List<Entry> entries){schools=Collections.unmodifiableList(new ArrayList<>(entries));}
    public List<Entry> search(String query){
        String term=query==null?"":query.trim().toLowerCase(Locale.ROOT);List<Entry> result=new ArrayList<>();
        for(Entry school:schools)if(term.isEmpty()||(school.name+" "+school.id+" "+school.profile.domain).toLowerCase(Locale.ROOT).contains(term))result.add(school);
        return result;
    }
    public static String readText(InputStream stream)throws IOException{
        if(stream==null)throw invalid();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=stream.read(buffer,0,Math.min(buffer.length,MAX_BYTES+1-bytes.size())))!=-1){
            if(n==0)throw new IOException("目录读取未完成");bytes.write(buffer,0,n);
            if(bytes.size()>MAX_BYTES)throw new IllegalArgumentException("学校目录不能超过 256 KiB");
        }
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("学校目录须使用 UTF-8 编码");}
    }
    public static SchoolDirectory parse(String text){
        if(text==null||text.length()>MAX_BYTES||text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid();
        return new Parser(text).directory();
    }
    /** Directory downloads never carry cookies, credentials, query parameters or redirects. */
    public static String downloadUrl(String value){
        try{
            URI uri=new URI(value==null?"":value.trim());String host=uri.getHost();
            if(!"https".equals(uri.getScheme())||host==null||!host.matches("(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                    ||host.matches("[0-9.]+")||host.toLowerCase(Locale.ROOT).endsWith(".local")||host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                    ||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw invalid();
            return uri.toASCIIString();
        }catch(Exception invalid){throw new IllegalArgumentException("请输入不含账号、查询参数或票据的 HTTPS 学校目录地址");}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("学校目录格式不正确，请使用有效的 version 1 学校目录；不可包含账号、密码或脚本");}
    private static final class Parser {
        final String text;int at;
        Parser(String text){this.text=text;if(text.startsWith("\ufeff"))at=1;}
        SchoolDirectory directory(){
            Set<String> keys=new HashSet<>();List<Entry> entries=null;String format=null,version=null;expect('{');
            do{String key=string();if(!keys.add(key))throw invalid();expect(':');
                if("format".equals(key))format=string();else if("version".equals(key))version=number();else if("schools".equals(key))entries=entries();else throw invalid();
            }while(take(','));expect('}');space();
            if(at!=text.length()||!FORMAT.equals(format)||!"1".equals(version)||entries==null||entries.isEmpty())throw invalid();
            return new SchoolDirectory(entries);
        }
        List<Entry> entries(){
            List<Entry> entries=new ArrayList<>();Set<String> ids=new HashSet<>();expect('[');if(take(']'))return entries;
            do{if(entries.size()>=MAX_SCHOOLS)throw invalid();Entry entry=entry();if(!ids.add(entry.id))throw invalid();entries.add(entry);}while(take(','));expect(']');return entries;
        }
        Entry entry(){
            Map<String,String> fields=new LinkedHashMap<>();expect('{');
            do{String key=string();if(!(key.equals("id")||key.equals("name")||key.equals("domain")||key.equals("vpnUrl")||key.equals("portalListUrl")||key.equals("loginUrl")||key.equals("connection")||key.equals("portalAdapter"))||fields.containsKey(key))throw invalid();expect(':');fields.put(key,string());}while(take(','));expect('}');
            String id=required(fields,"id",64),name=required(fields,"name",80);
            if(!id.matches("[a-z0-9][a-z0-9_-]{0,63}")||!name.equals(name.trim()))throw invalid();
            if(!"sangfor".equals(required(fields,"connection",40))||!"campus-html-v1".equals(required(fields,"portalAdapter",40)))throw new IllegalArgumentException("此目录包含当前版本尚未支持的校园连接或门户类型");
            String login=fields.get("loginUrl");if(login!=null&&login.length()>CampusConfigImport.MAX_URL_LENGTH)throw invalid();
            return new Entry(id,name,CampusSite.create(required(fields,"domain",253),required(fields,"vpnUrl",4096),required(fields,"portalListUrl",4096),login));
        }
        String required(Map<String,String> fields,String key,int limit){String value=fields.get(key);if(value==null||value.trim().isEmpty()||value.length()>limit)throw invalid();return value;}
        String number(){space();int start=at;while(at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9')at++;String value=text.substring(start,at);if(value.isEmpty()||value.length()>3||(value.length()>1&&value.charAt(0)=='0'))throw invalid();return value;}
        String string(){
            expect('"');StringBuilder result=new StringBuilder();boolean closed=false;
            while(at<text.length()){char c=text.charAt(at++);if(c=='"'){closed=true;break;}
                if(c=='\\'){if(at==text.length())throw invalid();char esc=text.charAt(at++);
                    if(esc=='"'||esc=='\\'||esc=='/')c=esc;else if(esc=='u'){if(at+4>text.length())throw invalid();int n=0;for(int i=0;i<4;i++){char digit=text.charAt(at++);int hex=digit<=127?Character.digit(digit,16):-1;if(hex<0)throw invalid();n=n*16+hex;}c=(char)n;}else throw invalid();}
                if(Character.isISOControl(c)||c=='\u2028'||c=='\u2029'||(c>='\u202a'&&c<='\u202e')||(c>='\u2066'&&c<='\u2069'))throw invalid();result.append(c);
                if(result.length()>4096)throw invalid();
            }
            if(!closed)throw invalid();for(int i=0;i<result.length();i++){char c=result.charAt(i);if(Character.isHighSurrogate(c)){if(++i==result.length()||!Character.isLowSurrogate(result.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}return result.toString();
        }
        boolean take(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        void expect(char c){if(!take(c))throw invalid();}
        void space(){while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
    }
}
