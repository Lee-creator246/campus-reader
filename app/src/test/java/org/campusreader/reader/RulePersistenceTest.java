package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class RulePersistenceTest {
    private Context context;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("keyword-rules",0).edit().clear().commit();}
    private KeywordRules rules(String word){return new KeywordRules(true,Arrays.asList(word),false,Collections.emptyList());}
    @Test public void previewDoesNotWriteAndUndoRestoresOneSave(){
        assertFalse(KeywordRules.loadStrict(context,"local").hasBlocked());
        KeywordRules candidate=KeywordRules.decode(rules("志愿").fingerprint());
        assertTrue(candidate.hasBlocked());assertNull(KeywordRules.previous(context,"local"));
        assertTrue(rules("任职").save(context,"local"));assertTrue(rules("志愿").save(context,"local"));
        assertTrue(KeywordRules.undo(context,"local"));assertEquals(Arrays.asList("任职"),KeywordRules.loadStrict(context,"local").blockedWords);
        assertFalse(KeywordRules.undo(context,"local"));
    }
    @Test public void corruptCurrentCanRestorePriorButCannotSilentlySaveEmpty(){
        rules("甲").save(context,"local");rules("乙").save(context,"local");
        context.getSharedPreferences("keyword-rules",0).edit().putString("rules:local","{bad").commit();
        assertThrows(IllegalStateException.class,()->KeywordRules.loadStrict(context,"local"));
        assertThrows(IllegalStateException.class,()->KeywordRules.empty().save(context,"local"));
        assertTrue(KeywordRules.undo(context,"local"));assertEquals(Arrays.asList("甲"),KeywordRules.loadStrict(context,"local").blockedWords);
    }
    @Test public void identicalSaveDoesNotConsumeUndo(){
        rules("甲").save(context,"local");rules("乙").save(context,"local");rules("乙").save(context,"local");
        assertEquals(Arrays.asList("甲"),KeywordRules.previous(context,"local").blockedWords);
    }
    @Test public void corruptRulesFailClosedAndExplicitResetRecoversWithoutPrevious(){
        context.getSharedPreferences("keyword-rules",0).edit().putString("rules:local","not-json").commit();
        KeywordRules fallback=KeywordRules.load(context,"local");assertTrue(fallback.loadFailed);
        org.campusreader.notices.NoticeQuery query=fallback.apply(org.campusreader.notices.NoticeQuery.builder(null),false).build();assertTrue(query.priorityOnly);assertTrue(query.priorityKeywords.isEmpty());
        assertTrue(KeywordRules.reset(context,"local"));assertFalse(KeywordRules.loadStrict(context,"local").loadFailed);assertNull(KeywordRules.previous(context,"local"));
    }
    @Test public void ruleHistoryIsPerProfile(){
        String owner="campus:"+String.join("",Collections.nCopies(64,"a"));
        rules("甲").save(context,owner);assertFalse(KeywordRules.loadStrict(context,"local").hasBlocked());
        assertNull(KeywordRules.previous(context,"local"));assertTrue(KeywordRules.loadStrict(context,owner).hasBlocked());
    }
}
