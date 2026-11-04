import XCTest
@testable import CampusReaderCore

final class LibraryTests: XCTestCase {
    private func notice(_ id: String = "n:1", body: String = "正文", title: String = "通知", date: Date = Date(timeIntervalSince1970: 100)) -> Notice {
        Notice(id:id,title:title,publishedAt:date,bodyHTML:body,bodyText:body,originalURL:URL(string:"http://my.bupt.edu.cn/xntz_content.jsp?wbnewsid=1")!)
    }
    func testRefreshDoesNotDuplicateOrResetIndependentFlags() {
        var library=LibraryState(); XCTAssertEqual(library.merge([notice()]),["n:1"])
        library.markRead(id:"n:1",read:true);library.setLater(id:"n:1",later:true);library.setTags(id:"n:1",tags:["个人标签"])
        XCTAssertEqual(library.merge([notice(body:"更新正文")]),[])
        XCTAssertEqual(library.notices.count,1); XCTAssertTrue(library.notices[0].isRead);XCTAssertTrue(library.notices[0].later)
        XCTAssertEqual(library.notices[0].tags,["个人标签"]); XCTAssertEqual(library.notices[0].bodyText,"更新正文")
        library.markRead(id:"n:1",read:false);XCTAssertFalse(library.notices[0].isRead);XCTAssertTrue(library.notices[0].later)
    }
    func testMetadataRefreshPreservesBodyAndChangedContentInvalidatesSummaryHash() {
        var library=LibraryState();library.merge([notice()]);let hash=library.notices[0].bodyVersion
        library.merge([notice(body:"")]);XCTAssertEqual(library.notices[0].bodyText,"正文");XCTAssertEqual(library.notices[0].bodyVersion,hash)
        library.merge([notice(body:"新正文")]);XCTAssertNotEqual(library.notices[0].bodyVersion,hash)
        library.merge([notice(body:"",title:"标题已更正")]);XCTAssertFalse(library.notices[0].hasBody)
    }
    func testReadRetentionKeepsStateAndPrioritizesLaterAndTags() {
        var library=LibraryState();let now=Date(timeIntervalSince1970:200_000)
        library.merge([notice("n:1"),notice("n:2"),notice("n:3")],now:now)
        for id in ["n:1","n:2","n:3"] { library.markRead(id:id,read:true,now:now.addingTimeInterval(-90_000)) }
        library.setLater(id:"n:2",later:true);library.setTags(id:"n:3",tags:["保存"])
        let result=library.enforceRetention(now:now);XCTAssertEqual(result.removedBodies,1)
        XCTAssertFalse(library.notices.first(where:{$0.id=="n:1"})!.hasBody)
        XCTAssertTrue(library.notices.first(where:{$0.id=="n:2"})!.hasBody)
        XCTAssertTrue(library.notices.first(where:{$0.id=="n:3"})!.hasBody)
        XCTAssertTrue(library.notices.allSatisfy(\.isRead))
    }
    func testHardCacheCapStillAppliesToProtectedBodies() {
        var library=LibraryState();library.merge([notice("n:1"),notice("n:2")]);library.setLater(id:"n:1",later:true);library.setLater(id:"n:2",later:true)
        library.enforceRetention(policy:RetentionPolicy(maximumBodies:1))
        XCTAssertEqual(library.notices.filter(\.hasBody).count,1);XCTAssertTrue(library.notices.allSatisfy(\.later))
    }
    func testDisabledAgeCleanupKeepsOldReadBodiesButStillHonorsCapacity() {
        var library=LibraryState();let now=Date(timeIntervalSince1970:200_000)
        library.merge([notice("n:1"),notice("n:2")]);library.markRead(id:"n:1",read:true,now:Date(timeIntervalSince1970:0))
        library.enforceRetention(policy:RetentionPolicy(readBodyHours:-1),now:now)
        XCTAssertTrue(library.notices.first(where:{$0.id=="n:1"})!.hasBody)
        library.enforceRetention(policy:RetentionPolicy(maximumBodies:1,readBodyHours:-1),now:now)
        XCTAssertEqual(library.notices.filter(\.hasBody).count,1)
        XCTAssertTrue(library.stateLedger["n:1"]!.isRead)
    }
    func testEvictedMetadataReturnsWithPreviousStateAndNotNewIdentity() {
        var library=LibraryState();library.merge([notice("n:1",date:Date(timeIntervalSince1970:10)),notice("n:2",date:Date(timeIntervalSince1970:20))])
        library.markRead(id:"n:1",read:true);library.enforceRetention(policy:RetentionPolicy(maximumMetadata:1))
        XCTAssertEqual(library.notices.map(\.id),["n:2"])
        XCTAssertEqual(library.merge([notice("n:1")]),[])
        XCTAssertTrue(library.notices.first(where:{$0.id=="n:1"})!.isRead)
    }
    func testJSONRoundTripAndOtherAccountRemainIndependent() throws {
        var first=LibraryState();first.merge([notice()]);first.markRead(id:"n:1",read:true)
        let restored=try JSONDecoder().decode(LibraryState.self,from:JSONEncoder().encode(first))
        XCTAssertTrue(restored.notices[0].isRead)
        var second=LibraryState();second.merge([notice()]);XCTAssertFalse(second.notices[0].isRead)
    }
    func testIdentityLedgerFullStopsNewIdentitiesWithoutForgettingReadState() {
        var library=LibraryState()
        library.merge((0..<LibraryState.maximumIdentities).map { notice("n:\($0)",body:"") })
        library.markRead(id:"n:0",read:true)
        XCTAssertEqual(library.merge([notice("n:overflow")]),[])
        XCTAssertTrue(library.capacityReached)
        XCTAssertEqual(library.stateLedger.count,LibraryState.maximumIdentities)
        library.enforceRetention(policy:RetentionPolicy(maximumMetadata:1))
        XCTAssertEqual(library.merge([notice("n:0")]),[])
        XCTAssertTrue(library.notices.first(where:{$0.id=="n:0"})!.isRead)
    }
    func testAuthErrorDoesNotEraseLibrary() {
        var library=LibraryState();library.merge([notice()]);library.setLater(id:"n:1",later:true)
        let before=library.notices
        XCTAssertThrowsError(try PortalParser.parseList(url:PortalFixture.list,status:401,html:""))
        XCTAssertEqual(library.notices,before)
    }
    func testBlockAlwaysWinsOverKeywordThemeAndFeedback() {
        var p=Preferences();p.blocked=["任职"];p.keywords=["志愿"];p.topics=["志愿实践"];p.feedback=["志愿实践":4]
        let n=notice(title:"志愿服务队负责人任职通知")
        XCTAssertEqual(Recommendation.visible([n],preferences:p,mode:.recommended),[])
        XCTAssertNil(Recommendation.priorityReason(n,preferences:p))
    }
    func testRecommendationDoesNotLearnFromReadOrLater() {
        var p=Preferences();p.keywords=["志愿"];var n=notice(title:"志愿活动")
        let before=Recommendation.score(n,preferences:p);n.isRead=true;n.later=true
        XCTAssertEqual(Recommendation.score(n,preferences:p),before)
        p.recordFeedback(for:n,more:false);XCTAssertLessThan(Recommendation.score(n,preferences:p),before)
    }
    func testRepeatedKeywordDoesNotMultiplyScoreAndLearningCannotMakePriority() {
        var p=Preferences();p.keywords=["志愿"];let n=notice(title:"志愿活动")
        let before=Recommendation.score(n,preferences:p);p.keywords=["志愿","志愿"]
        XCTAssertEqual(Recommendation.score(n,preferences:p),before)
        p.keywords=[];p.feedback=["志愿实践":4];XCTAssertNil(Recommendation.priorityReason(n,preferences:p))
    }
    func testQueryTopicsAndUnreadFiltersWorkTogether() {
        var a=notice("n:1",title:"志愿招募");let b=notice("n:2",title:"任职通知");a.tags=["自定义"]
        XCTAssertEqual(Recommendation.visible([a,b],preferences:Preferences(),mode:.unread,topic:"自定义",query:"招募").map(\.id),["n:1"])
    }
    func testTermParsingTrimsDeduplicatesAndBounds() {
        XCTAssertEqual(Preferences.terms(" 志愿,竞赛\n志愿， AI;ai"),["志愿","竞赛","AI"])
        XCTAssertEqual(Preferences.terms((0..<150).map(String.init).joined(separator:",")).count,100)
    }
}
