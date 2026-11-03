package org.campusreader.reader;

import java.net.URI;
import java.net.URISyntaxException;
import org.campusreader.notices.SummaryInput;

/** Pure limits and source checks; contains no network or Android dependencies. */
public final class AiPolicy {
    public static final int MAX_SOURCE_CHARS=12000,MAX_RESULT_BYTES=16384,MAX_RESPONSE_BYTES=65536,MAX_OUTPUT_TOKENS=1000,DAILY_LIMIT=20;
    public static final String PROMPT_VERSION="campus-summary-v1";
    private AiPolicy(){}
    public static String endpoint(String raw){
        if(raw==null||raw.length()>2048)throw new IllegalArgumentException("请填写完整的 HTTPS chat/completions 地址");
        String value=raw.trim();
        try{URI uri=new URI(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort()>65535||!uri.getRawPath().endsWith("/chat/completions"))throw new IllegalArgumentException("地址须为完整 HTTPS …/chat/completions，不可含凭据、查询参数或片段");
            for(int i=0;i<value.length();i++)if(Character.isWhitespace(value.charAt(i))||Character.isISOControl(value.charAt(i))||value.charAt(i)=='\\')throw new IllegalArgumentException("服务地址格式不正确");
            return uri.toASCIIString();
        }catch(URISyntaxException malformed){throw new IllegalArgumentException("服务地址格式不正确");}
    }
    public static String model(String raw){String value=raw==null?"":raw.trim();if(value.isEmpty()||value.length()>100||!value.matches("[A-Za-z0-9._:/-]+"))throw new IllegalArgumentException("模型名须为 1–100 位字母、数字或 . _ : / -");return value;}
    public static String key(String raw){String value=raw==null?"":raw.trim();if(value.isEmpty()||value.length()>512||!value.matches("[!-~]+"))throw new IllegalArgumentException("API Key 不能为空、不可含空白，最多 512 字符");return value;}
    public static String sentText(SummaryInput source){
        if(source==null||source.bodyText.trim().isEmpty())throw new IllegalArgumentException("没有可用的文字正文，请返回通知重新获取正文；不分析图片或附件");
        String text="标题："+source.title+"\n\n正文：\n"+source.bodyText;
        if(text.length()>MAX_SOURCE_CHARS)throw new IllegalArgumentException("标题与正文超过 12000 字符，本次不发送；请直接阅读原文");
        return text;
    }
    public static String configHash(String endpoint,String model){return SummaryInput.hash(endpoint+"\u0000"+model+"\u0000"+PROMPT_VERSION);}
    public static boolean requiresNewKey(String previousEndpoint,String nextEndpoint,boolean hasStoredKey,String newKey){return hasStoredKey&&!previousEndpoint.equals(nextEndpoint)&&(newKey==null||newKey.trim().isEmpty());}
    public static boolean validEvidence(String value,String quote,String exactSource){
        return value!=null&&quote!=null&&exactSource!=null&&!value.trim().isEmpty()&&value.length()<=600&&!quote.trim().isEmpty()&&quote.length()<=800&&exactSource.contains(quote);
    }
}
