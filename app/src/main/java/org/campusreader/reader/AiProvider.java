package org.campusreader.reader;

import java.net.URI;
import org.json.JSONException;
import org.json.JSONObject;

/** Small capability adapter. Providers never supply credentials, network clients, or tools. */
public enum AiProvider {
    CUSTOM("custom", "自定义兼容接口", "", ""),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-flash");

    public final String id,label,defaultEndpoint,defaultModel;
    AiProvider(String id,String label,String endpoint,String model){this.id=id;this.label=label;defaultEndpoint=endpoint;defaultModel=model;}
    public static AiProvider fromId(String id){
        for(AiProvider provider:values())if(provider.id.equals(id))return provider;
        throw new IllegalArgumentException("AI 服务类型不受支持，请重新选择服务。");
    }
    public String endpoint(String value){
        String checked=AiPolicy.endpoint(value);
        if(this==DEEPSEEK){
            URI uri=URI.create(checked);String path=uri.getRawPath();
            if(!"api.deepseek.com".equalsIgnoreCase(uri.getHost())||(uri.getPort()!=-1&&uri.getPort()!=443)
                    ||!("/chat/completions".equals(path)||"/v1/chat/completions".equals(path)))
                throw new IllegalArgumentException("DeepSeek 预设只连接官方 API；其他服务或代理请选择自定义兼容接口。");
        }
        return checked;
    }
    public void configure(JSONObject request)throws JSONException{
        request.put("response_format",new JSONObject().put("type","json_object"));
        // Official DeepSeek currently enables thinking by default. Extraction uses bounded final JSON.
        // Do not send vendor-specific fields to a generic compatible service.
        if(this==DEEPSEEK)request.put("thinking",new JSONObject().put("type","disabled"));
    }
}
