package org.campusreader.reader;

import android.app.Application;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class AiSummaryModelTest {
    static String empty()throws JSONException{JSONObject object=new JSONObject();for(String key:AiSummaryModel.FIELDS)object.put(key,new JSONObject().put("value","").put("quote",""));return object.toString();}
    @Test public void exactEvidenceSurvivesButInventedEvidenceIsDiscarded()throws Exception{
        JSONObject data=new JSONObject(empty());data.put("purpose",new JSONObject().put("value","报名活动").put("quote","11月5日截止报名"));data.put("audience",new JSONObject().put("value","所有学生").put("quote","面向所有学生"));
        AiSummaryModel result=AiSummaryModel.parse(data.toString(),"11月5日截止报名，地点：中心。");assertEquals("报名活动",result.fields.get(0).value);assertEquals("",result.fields.get(1).value);assertEquals(1,result.discarded);
    }
    @Test public void fabricatedDeadlineOrRoomCannotOverrideActualQuote()throws Exception{
        JSONObject data=new JSONObject(empty());data.put("deadline",new JSONObject().put("value","2028年12月31日截止").put("quote","11月5日截止报名"));data.put("location",new JSONObject().put("value","行政楼999室").put("quote","地点：中心"));
        AiSummaryModel result=AiSummaryModel.parse(data.toString(),"11月5日截止报名，地点：中心。");assertEquals("11月5日截止报名",result.fields.get(3).value);assertEquals("地点：中心",result.fields.get(4).value);
        AiSummaryModel cached=AiSummaryModel.parse(result.json,"11月5日截止报名，地点：中心。");assertEquals(result.fields.get(3).value,cached.fields.get(3).value);
    }
    @Test public void absentFieldsStayEmptyAndMalformedSchemaFails()throws Exception{
        assertEquals("",AiSummaryModel.parse(empty(),"通知原文").fields.get(0).value);
        try{AiSummaryModel.parse("{}","通知原文");fail();}catch(JSONException expected){}
        JSONObject data=new JSONObject(empty());data.put("purpose",new JSONObject().put("value","未注明").put("quote",""));assertEquals(1,AiSummaryModel.parse(data.toString(),"通知原文").discarded);
    }
    @Test public void longTimeQuoteHasStableCachedInterpretation()throws Exception{
        char[] chars=new char[700];java.util.Arrays.fill(chars,'时');String quote=new String(chars);JSONObject data=new JSONObject(empty());data.put("deadline",new JSONObject().put("value","时间说明").put("quote",quote));
        AiSummaryModel first=AiSummaryModel.parse(data.toString(),quote),cached=AiSummaryModel.parse(first.json,quote);assertEquals("",first.fields.get(3).value);assertEquals(first.fields.get(3).value,cached.fields.get(3).value);
    }
}
