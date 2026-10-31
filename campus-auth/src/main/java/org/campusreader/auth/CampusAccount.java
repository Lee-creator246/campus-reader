package org.campusreader.auth;

import org.campusreader.config.CampusSite;
/** Normalizes account names within the configured authentication domain. */
public final class CampusAccount {
    public static final String DOMAIN = CampusSite.DOMAIN;
    private CampusAccount() {}
    public static String profileId(String input) {
        if (input == null) throw new IllegalArgumentException("请输入学工号");
        String account = input.trim();
        int at = account.indexOf('@');
        if (at >= 0) {
            if (!account.substring(at + 1).equalsIgnoreCase(DOMAIN))
                throw new IllegalArgumentException("请选择当前校园账号认证域");
            account = account.substring(0, at);
        }
        if (account.isEmpty() || account.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("请输入有效学工号");
        return account;
    }
    public static String sdkUsername(String input) { return profileId(input) + "@" + DOMAIN; }
}
