package org.campusreader.config;

/** Build-time connection settings. Real institution values belong in ignored local configuration. */
public final class CampusSite {
    public static final String DOMAIN=SiteValues.DOMAIN;
    public static final String VPN_URL=SiteValues.VPN_URL;
    public static final String PORTAL_LIST_URL=SiteValues.PORTAL_LIST_URL;
    public static final String PORTAL_HOST=SiteValues.PORTAL_HOST;
    public static final boolean CONFIGURED=SiteValues.CONFIGURED;
    private CampusSite(){}
    public static void requireConfigured(){
        if(!CONFIGURED)throw new IllegalStateException("请先配置校园访问参数并重新构建应用");
    }
}
