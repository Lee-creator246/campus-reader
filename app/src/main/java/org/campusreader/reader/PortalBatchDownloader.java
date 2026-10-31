package org.campusreader.reader;

import org.campusreader.notices.CampusNotice;
import org.campusreader.portal.CampusPortalParser;
import java.io.IOException;
import java.util.List;

/** Bounded detail download. Persist each verified response in its captured account's store. */
final class PortalBatchDownloader {
    enum State { READY, TUNNEL_DISCONNECTED, ACCOUNT_CHANGED }
    enum Stop { COMPLETE, PORTAL_LOGIN_REQUIRED, TUNNEL_DISCONNECTED, ACCOUNT_CHANGED }
    interface Session { State state(); }
    interface Fetcher { CampusNotice fetch(CampusPortalParser.Summary summary) throws IOException,CampusPortalParser.ParseException; }
    interface Sink { void save(CampusNotice notice); }
    interface Progress { void started(int position,int total); }
    static final class Result {
        final int saved,failed; final Stop stop;
        Result(int saved,int failed,Stop stop){this.saved=saved;this.failed=failed;this.stop=stop;}
        boolean mayAdvance(){return stop==Stop.COMPLETE&&failed==0;}
    }
    static Result download(List<CampusPortalParser.Summary> summaries,Session session,Fetcher fetcher,Sink sink,Progress progress) {
        int saved=0,failed=0;
        for(int index=0;index<summaries.size();index++) {
            State state=session.state();
            if(state!=State.READY)return stopped(saved,failed,state);
            CampusPortalParser.Summary summary=summaries.get(index);
            progress.started(index+1,summaries.size());
            try {
                CampusNotice notice=fetcher.fetch(summary);
                // An account switch or disconnect during a request makes that response uncertain.
                // Earlier committed records remain in the original account's database.
                state=session.state();
                if(state!=State.READY)return stopped(saved,failed,state);
                if(!summary.key.equals(notice.key)){failed++;continue;}
                sink.save(notice);saved++;
            } catch(CampusPortalParser.ParseException e) {
                state=session.state();if(state!=State.READY)return stopped(saved,failed,state);
                failed++;
                if(e.failure==CampusPortalParser.Failure.REAUTH_REQUIRED)return new Result(saved,failed,Stop.PORTAL_LOGIN_REQUIRED);
            } catch(IOException e) {
                state=session.state();if(state!=State.READY)return stopped(saved,failed,state);
                failed++;
            }
        }
        State finalState=session.state();
        return finalState==State.READY?new Result(saved,failed,Stop.COMPLETE):stopped(saved,failed,finalState);
    }
    private static Result stopped(int saved,int failed,State state){return new Result(saved,failed,state==State.ACCOUNT_CHANGED?Stop.ACCOUNT_CHANGED:Stop.TUNNEL_DISCONNECTED);}
}
