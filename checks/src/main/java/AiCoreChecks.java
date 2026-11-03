package org.campusreader.reader;

import org.campusreader.notices.SummaryInput;

/** Synthetic source, endpoint and evidence boundaries; never opens a socket. */
public final class AiCoreChecks {
    private static int checked;
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checked++;}
    private static void rejects(Runnable action,String reason){try{action.run();}catch(IllegalArgumentException expected){checked++;return;}throw new AssertionError(reason);}
    public static void main(String[] args){
        String endpoint="https://api.example.org/v1/chat/completions";
        check(AiPolicy.endpoint(endpoint).equals(endpoint),"Explicit HTTPS endpoint changed");
        String[] unsafe={"http://api.example.org/v1/chat/completions","https://user:pass@api.example.org/v1/chat/completions","https://api.example.org/v1/chat/completions?token=sample","https://api.example.org/v1/chat/completions#fragment","https://api.example.org/v1/messages","file:///v1/chat/completions","https://api.example.org:0/v1/chat/completions","https://api.example.org:99999/v1/chat/completions","https://api.example.org/v1/\nchat/completions",""};
        for(String value:unsafe)rejects(()->AiPolicy.endpoint(value),"Unsafe endpoint accepted");
        check(AiPolicy.requiresNewKey(endpoint,"https://other.example.org/v1/chat/completions",true,""),"Old provider key may not migrate silently");
        check(AiPolicy.requiresNewKey(endpoint,endpoint+"/../chat/completions",true," "),"Changed path requires explicit key too");
        check(!AiPolicy.requiresNewKey(endpoint,endpoint,true,""),"Same endpoint should keep encrypted key");
        check(!AiPolicy.requiresNewKey(endpoint,"https://other.example.org/v1/chat/completions",true,"new-key"),"New endpoint explicit key rejected");
        check(!AiPolicy.requiresNewKey(endpoint,"https://other.example.org/v1/chat/completions",false,""),"No stored key cannot leak");
        SummaryInput source=new SummaryInput("志愿活动","11月5日截止报名，地点：学生活动中心。");
        String sent=AiPolicy.sentText(source);
        check(sent.equals("标题：志愿活动\n\n正文：\n11月5日截止报名，地点：学生活动中心。"),"Confirmation and request need the same exact plaintext");
        check(!sent.contains("https://")&&!sent.contains("Cookie"),"Source metadata leaked into text");
        check(!source.contentHash.equals(new SummaryInput(source.title,source.bodyText+"更新").contentHash),"Body change must invalidate summary");
        check(!source.contentHash.equals(new SummaryInput(source.title+"更新",source.bodyText).contentHash),"Title change must invalidate summary");
        check(!AiPolicy.configHash(endpoint,"model-a").equals(AiPolicy.configHash(endpoint,"model-b")),"Model must scope cached result");
        check(!AiPolicy.configHash(endpoint,"model-a").equals(AiPolicy.configHash("https://other.example.org/v1/chat/completions","model-a")),"Endpoint must scope cached result");
        check(AiPolicy.validEvidence("11月5日","11月5日截止报名",sent),"Exact quote lost");
        check(!AiPolicy.validEvidence("11月6日","11月6日截止报名",sent),"Invented quote accepted");
        check(!AiPolicy.validEvidence("值","",sent),"Evidence-less value accepted");
        check(!AiPolicy.validEvidence("值"," ",sent),"Whitespace evidence accepted");
        rejects(()->AiPolicy.sentText(new SummaryInput("图片通知"," ")),"Image-only article may not be summarized as source text");
        char[] huge=new char[AiPolicy.MAX_SOURCE_CHARS];java.util.Arrays.fill(huge,'a');
        rejects(()->AiPolicy.sentText(new SummaryInput("通知",new String(huge))),"Long article silently truncated or sent");
        rejects(()->AiPolicy.model("model\nInjected: header"),"Control in model accepted");
        rejects(()->AiPolicy.key("secret\nInjected: header"),"Control in API key accepted");
        check(AiPolicy.MAX_OUTPUT_TOKENS==2000&&AiPolicy.DAILY_LIMIT==20,"Billing bounds changed");
        System.out.println("AI core checks passed: "+checked);
    }
}
