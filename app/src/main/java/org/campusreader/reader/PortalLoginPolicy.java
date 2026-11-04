package org.campusreader.reader;

import java.net.URI;
import java.util.Locale;
import org.json.JSONObject;

/** Pure policy for the locally implemented CAS form adapter. No school constants or remote scripts. */
final class PortalLoginPolicy {
    static String binding(String url) {
        try {
            URI u=URI.create(url);
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null
                    ||u.getFragment()!=null||(u.getPort()!=-1&&u.getPort()!=443))throw new IllegalArgumentException();
            String path=u.getRawPath();
            if(path==null||!(path.endsWith("/login")))throw new IllegalArgumentException();
            return "https://"+u.getHost().toLowerCase(Locale.ROOT)+path;
        }catch(RuntimeException invalid){throw new IllegalArgumentException("需要已识别的 HTTPS 统一认证登录页");}
    }
    static boolean accepts(String page,String action,String id,String method,int users,int passwords,boolean execution,boolean event) {
        try{return binding(page).equals(binding(action))&&("loginForm".equals(id)||"casLoginForm".equals(id))
                &&"post".equalsIgnoreCase(method)&&users==1&&passwords==1&&execution&&event;}
        catch(IllegalArgumentException invalid){return false;}
    }
    static String account(String input,String campusAccount) {
        String value=input==null?"":input.trim();
        if(value.isEmpty()||value.length()>128||!value.equals(campusAccount))throw new IllegalArgumentException("请使用当前选择的门户学工号");
        return value;
    }
    static String password(String input){if(input==null||input.isEmpty()||input.length()>1024)throw new IllegalArgumentException("请输入门户密码");return input;}
    // The probe deliberately returns only structure and URL: no field values, tickets, or page text.
    static final String PROBE="(function(){var f=document.querySelector('form#loginForm,form#casLoginForm');if(!f)return '{}';"
            +"var visible=function(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length)};"
            +"var extra=Array.prototype.some.call(f.querySelectorAll('input'),function(e){return visible(e)&&['hidden','submit','button','checkbox','radio'].indexOf(e.type)<0&&e.name!=='username'&&e.name!=='password'});"
            +"var err=Array.prototype.some.call(document.querySelectorAll('.error,.errors,#msg,#errorMsg,.alert-danger'),function(e){return visible(e)&&e.textContent.trim().length>0});"
            +"return JSON.stringify({page:location.href,action:f.action,id:f.id,method:f.method,users:f.querySelectorAll('input[name=username]').length,passwords:f.querySelectorAll('input[name=password][type=password]').length,execution:!!f.querySelector('input[name=execution]'),event:!!f.querySelector('input[name=_eventId]'),challenge:extra,error:err});})()";
    static String submitScript(String page,String action,String id,String account,String password) {
        // Validate again inside the page at the exact moment of submission. requestSubmit uses native submit events.
        return "(function(){var f=document.getElementById("+JSONObject.quote(id)+");if(location.href!=="+JSONObject.quote(page)
                +"||!f||f.action!=="+JSONObject.quote(action)+"||f.method.toLowerCase()!=='post')return false;"
                +"var u=f.querySelectorAll('input[name=username]'),p=f.querySelectorAll('input[name=password][type=password]');if(u.length!==1||p.length!==1)return false;"
                +"var b=f.querySelector('button[type=submit],input[type=submit]');if(!b||b.hasAttribute('formaction')||b.disabled)return false;"
                +"var extra=Array.prototype.some.call(f.querySelectorAll('input'),function(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length)&&['hidden','submit','button','checkbox','radio'].indexOf(e.type)<0&&e.name!=='username'&&e.name!=='password'});if(extra)return false;"
                +"u[0].value="+JSONObject.quote(account)+";p[0].value="+JSONObject.quote(password)+";"
                +"[u[0],p[0]].forEach(function(e){e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))});b.click();return true;})()";
    }
}
