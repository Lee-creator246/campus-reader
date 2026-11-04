package org.campusreader.config;

/** Public service addresses for this single-campus product; contains no account information. */
public final class BuptPreset {
    public static final String DOMAIN="bupt.edu.cn";
    public static final String VPN="https://vpn.bupt.edu.cn";
    public static final String LIST="http://my.bupt.edu.cn/xs_index.jsp?urltype=tree.TreeTempUrl&wbtreeid=1541";
    public static final String LOGIN="https://auth.bupt.edu.cn/authserver/login?service=http%3A%2F%2Fmy.bupt.edu.cn%3A80%2Fsystem%2Fresource%2Fcode%2Fauth%2Fclogin.jsp%3Fowner%3D1664271694";
    private BuptPreset(){}
    public static CampusSite.Profile profile(){return CampusSite.create(DOMAIN,VPN,LIST,LOGIN);}
    public static boolean matches(CampusSite.Profile p){return p!=null&&p.configured&&DOMAIN.equals(p.domain)&&"my.bupt.edu.cn".equals(p.portalHost);}
}
