package org.campusreader.reader;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;

/** Every displayed field retains an exact source quote; unmatched fields are discarded. */
public final class AiSummaryModel {
    public static final String[] FIELDS={"purpose","audience","actions","deadline","location"};
    public static final String[] LABELS={"通知事项","适用对象","需要做什么","截止 / 关键时间","地点"};
    public static final class Field {
        public final String label,value,quote;
        Field(String label,String value,String quote){this.label=label;this.value=value;this.quote=quote;}
    }
    public final List<Field> fields;
    public final int discarded;
    public final String json;
    private AiSummaryModel(List<Field> fields,int discarded,String json){this.fields=Collections.unmodifiableList(fields);this.discarded=discarded;this.json=json;}
    public static AiSummaryModel parse(String json,String exactSource)throws JSONException{
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>AiPolicy.MAX_RESULT_BYTES)throw new JSONException("Summary size");
        JSONObject object=new JSONObject(json);if(object.length()!=FIELDS.length)throw new JSONException("Summary schema");
        List<Field> fields=new ArrayList<>();JSONObject clean=new JSONObject();int discarded=0;
        for(int i=0;i<FIELDS.length;i++){
            JSONObject item=object.getJSONObject(FIELDS[i]);if(item.length()!=2||!(item.get("value") instanceof String)||!(item.get("quote") instanceof String))throw new JSONException("Summary field schema");
            String value=item.getString("value"),quote=item.getString("quote");
            if(value.isEmpty()&&quote.isEmpty()){}
            else if(!AiPolicy.validEvidence(value,quote,exactSource)){value="";quote="";discarded++;}
            // Time/location interpretations can invent dates or rooms despite a genuine quote.
            // Display the exact quoted source for these fields instead of trusting paraphrases.
            if(!quote.isEmpty()&&(FIELDS[i].equals("deadline")||FIELDS[i].equals("location"))){
                if(quote.length()>600){value="";quote="";discarded++;}else value=quote;
            }
            clean.put(FIELDS[i],new JSONObject().put("value",value).put("quote",quote));fields.add(new Field(LABELS[i],value,quote));
        }
        return new AiSummaryModel(fields,discarded,clean.toString());
    }
}
