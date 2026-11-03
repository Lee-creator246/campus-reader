package org.campusreader.notices;

/** One bounded cached result for one immutable input/service fingerprint. */
public final class SummaryRecord {
    public final String contentHash, configHash, resultJson;
    public final long generatedAtMillis;
    public SummaryRecord(String contentHash,String configHash,String resultJson,long generatedAtMillis){
        this.contentHash=contentHash;this.configHash=configHash;this.resultJson=resultJson;this.generatedAtMillis=generatedAtMillis;
    }
}
