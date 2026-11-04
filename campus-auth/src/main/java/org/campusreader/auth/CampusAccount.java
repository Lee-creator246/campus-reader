package org.campusreader.auth;

import org.campusreader.config.CampusSite;
/** Normalizes account names within the configured authentication domain. */
public final class CampusAccount {
    private CampusAccount() {}
    public static String profileId(String input) {
        if (input == null) throw new IllegalArgumentException("请输入学工号");
        String account = input.trim();
        int at = account.indexOf('@');
        if (at >= 0) {
            if (!account.substring(at + 1).equalsIgnoreCase(CampusSite.domain()))
                throw new IllegalArgumentException("请选择当前校园账号认证域");
            account = account.substring(0, at);
        }
        if (account.isEmpty() || account.length()>128 || account.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))) throw new IllegalArgumentException("请输入有效学工号");
        return account;
    }
    public static String sdkUsername(String input) { CampusSite.requireConfigured(); return profileId(input) + "@" + CampusSite.domain(); }
}
