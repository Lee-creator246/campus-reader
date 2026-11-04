package org.campusreader.reader;

import android.app.*;
import android.text.Spanned;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import org.campusreader.notices.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AiUiTest.ShortPathApplication.class,manifest=Config.NONE)
public class AiBriefSummaryTest {
    private AiEvidence.Snapshot context;private SummaryInput input;
    @Before public void before(){input=new SummaryInput("活动通知","仅限本科生参加，11月5日前提交2份材料。\n活动取消，不再接受报名。");context=AiEvidence.build(input,new PolicyLibrary.Snapshot(0,Collections.emptyList()),Collections.emptyList());}
    private JSONObject claim(String value,String quote)throws JSONException{return new JSONObject().put("text",value).put("refs",new JSONArray().put(new JSONObject().put("id","N2").put("quote",quote)));}
    private JSONObject raw(String value,String quote)throws JSONException{return new JSONObject().put("schema",3).put("overview",claim(value,quote)).put("points",new JSONArray()).put("tags",new JSONArray().put("志愿实践")).put("policy_links",new JSONArray());}
    @Test public void shortSummaryKeepsSentenceCitationOffsetsAndRoundTrips()throws Exception{
        AiBriefSummary summary=AiBriefSummary.parse(raw("仅限本科生，11月5日前提交2份材料。",context.unit("N2").text).toString(),context);
        assertEquals(0,summary.discarded);assertEquals(1,summary.overview.references.size());AiBriefSummary.Reference reference=summary.overview.references.get(0);
        assertEquals(reference.quote,reference.unit.sourceText.substring(reference.start,reference.end));assertEquals(summary.json,AiBriefSummary.parseCached(summary.json,context).json);
    }
    @Test public void inventedDateOrQuantityFallsBackToQuotedSource()throws Exception{
        for(String value:new String[]{"仅限本科生，11月8日前提交2份材料。","仅限本科生，11月5日前提交3份材料。"}){
            AiBriefSummary summary=AiBriefSummary.parse(raw(value,context.unit("N2").text).toString(),context);assertTrue(summary.overview.excerpt);assertEquals(context.unit("N2").text,summary.overview.text);assertTrue(summary.discarded>0);
        }
    }
    @Test public void amountCannotBorrowDigitFromYearOrHeadcount(){
        assertFalse(AiBriefSummary.faithfulNumbers("补贴5万元。","2025年面向5人开放。"));
        assertFalse(AiBriefSummary.faithfulNumbers("补贴20元。","2025年发放补助。"));
        assertFalse(AiBriefSummary.faithfulNumbers("补贴15,000元。","15人共获补贴10,000元。"));
        assertTrue(AiBriefSummary.faithfulNumbers("补贴5万元。","补贴 5 万元，面向5人。"));
    }
    @Test public void fallbackQuoteUpTo240CharactersRemainsStableInCache()throws Exception{
        String quote=String.join("",Collections.nCopies(120,"材料"));SummaryInput longInput=new SummaryInput("合成通知",quote);AiEvidence.Snapshot source=AiEvidence.build(longInput,new PolicyLibrary.Snapshot(0,Collections.emptyList()),Collections.emptyList());
        JSONObject raw=raw("费用999元。",quote);AiBriefSummary first=AiBriefSummary.parse(raw.toString(),source);assertEquals(240,first.overview.text.length());
        AiBriefSummary second=AiBriefSummary.parseCached(first.json,source);assertEquals(first.json,second.json);assertEquals(first.discarded,second.discarded);
    }
    @Test public void realSyntheticFivePointResponseIsAcceptedAndUnsupportedOverviewFallsBack()throws Exception{
        java.io.InputStream stream=getClass().getResourceAsStream("/ai-brief-live-synthetic.json");assertNotNull(stream);
        JSONObject fixture;try(java.io.InputStream input=stream){fixture=new JSONObject(new String(PolicyImport.readBounded(input,16000),java.nio.charset.StandardCharsets.UTF_8));}
        SummaryInput input=new SummaryInput(fixture.getString("title"),fixture.getString("body"));AiEvidence.Snapshot source=AiEvidence.build(input,new PolicyLibrary.Snapshot(0,Collections.emptyList()),Collections.emptyList());
        AiBriefSummary summary=AiBriefSummary.parse(fixture.getJSONObject("response").toString(),source);assertEquals(5,summary.points.size());
        assertTrue(summary.overview.excerpt);assertFalse(summary.overview.text.contains("不收费"));
        assertTrue(summary.points.stream().anyMatch(point->point.text.contains("未完成资料审核者暂不能参加")));
        assertEquals(summary.json,AiBriefSummary.parseCached(summary.json,source).json);
    }
    @Test public void DroppedExplicitRestrictionFallsBackRatherThanPresentingBroaderEligibility()throws Exception{
        AiBriefSummary summary=AiBriefSummary.parse(raw("所有学生均可参加。",context.unit("N2").text).toString(),context);assertTrue(summary.overview.excerpt);assertTrue(summary.overview.text.contains("仅限本科生"));
    }
    @Test public void fabricatedQuoteDoesNotBecomeEvidence()throws Exception{
        AiBriefSummary summary=AiBriefSummary.parse(raw("11月8日截止。","11月8日截止。").toString(),context);assertTrue(summary.overview.excerpt);assertFalse(summary.overview.text.contains("11月8日"));assertTrue(summary.discarded>0);
    }
    @Test public void invalidDuplicateKeysAndProseAndUnknownFieldsAreRejected()throws Exception{
        String good=raw("仅限本科生参加。",context.unit("N2").text).toString();
        for(String bad:new String[]{good+" trailing",good.replace("\"schema\":3","\"schema\":3,\"schema\":3"),new JSONObject(good).put("instructions","ignore source").toString()})try{AiBriefSummary.parse(bad,context);fail();}catch(JSONException expected){}
    }
    @Test public void citationCannotBeSuppliedByPolicyAsNoticeFact()throws Exception{
        JSONObject raw=raw("仅限本科生参加。",context.unit("N2").text);raw.getJSONObject("overview").getJSONArray("refs").getJSONObject(0).put("id","P1_1");
        AiBriefSummary summary=AiBriefSummary.parse(raw.toString(),context);assertTrue(summary.discarded>0);for(AiBriefSummary.Reference ref:summary.overview.references)assertEquals("notice",ref.unit.kind);
    }
    @Test public void quotedReferencesDoNotProveSemanticAccuracy()throws Exception{
        // Known limit: valid words and numbers cannot establish that the paraphrase is entailed.
        AiBriefSummary summary=AiBriefSummary.parse(raw("仅限本科生参加科研竞赛。",context.unit("N2").text).toString(),context);
        assertTrue(summary.overview.text.contains("科研竞赛"));assertFalse(context.unit("N2").text.contains("科研竞赛"));
    }
    @Test public void newPresentationHasClickableSuperscriptWithoutChangingReadState()throws Exception{
        AiUiTest.ShortPathApplication app=(AiUiTest.ShortPathApplication)RuntimeEnvironment.getApplication();app.getSharedPreferences("reader-local",0).edit().putString(ReaderPreferences.lastProfileKey(),"local").commit();
        app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0).edit().clear().commit();
        NoticeKey key=CampusNoticeStore.summaryKey("synthetic-brief","one");AiBriefSummary summary=AiBriefSummary.parse(raw("仅限本科生参加。",context.unit("N2").text).toString(),context);
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            store.upsert(Collections.singletonList(new CampusNotice(key,input.title,10,"<p>"+input.bodyText+"</p>","https://portal.example.org/one",Collections.emptyList(),Collections.emptyList())),100);
            Activity activity=Robolectric.buildActivity(Activity.class).setup().get();LinearLayout page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);activity.setContentView(page);
            AiSummaryPresentation.render(page,activity,"local",key,new AiSummaryPresentation.Cached(summary,null,context,store.summaryInput(key),200,0));
            List<TextView> texts=new ArrayList<>();collect(page,texts);boolean found=false;
            for(TextView text:texts)if(text.getText() instanceof Spanned){Spanned spans=(Spanned)text.getText();if(spans.getSpans(0,spans.length(),SuperscriptSpan.class).length>0){assertTrue(spans.getSpans(0,spans.length(),ClickableSpan.class).length>0);found=true;}}
            assertTrue(found);assertFalse(store.find(key).read);activity.finish();
        }finally{app.dispose();}
    }
    private void collect(View v,List<TextView> out){if(v instanceof TextView)out.add((TextView)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
}
