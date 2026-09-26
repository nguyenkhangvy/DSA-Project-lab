package vn.edu.hcmiu.sla.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The database address from .env's DATABASE_URL, written the way the Python site writes it
 * (mysql+pymysql://user:password@host:3306/db?charset=utf8mb4), turned into what Java needs.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    static final String MORE_THAN_ONE_AT =
            "DATABASE_URL contains more than one '@'. If your database password has an '@' in it, "
                    + "write it as %40 in .env (for example, pass@word becomes pass%40word).";
    static final String NOT_MYSQL =
            "DATABASE_URL must be a MySQL address such as mysql+pymysql://user:password@localhost:3306/school_life.";

    public static DatabaseUrl parse(String url) {
        String value = unquote(url.strip());
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0 || !value.substring(0, schemeEnd).startsWith("mysql")) {
            throw new IllegalStateException(NOT_MYSQL);
        }
        String rest = value.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalStateException(NOT_MYSQL);
        }
        String authority = rest.substring(0, slash);
        String database = rest.substring(slash + 1).split("\\?", 2)[0];
        if (authority.chars().filter(c -> c == '@').count() > 1) {
            // In scheme://user:password@host/db, '@' ends the password, so a raw '@' inside it breaks the host.
            throw new IllegalStateException(MORE_THAN_ONE_AT);
        }
        String username = "";
        String password = "";
        String host = authority;
        int at = authority.indexOf('@');
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            host = authority.substring(at + 1);
            int colon = userInfo.indexOf(':');
            username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
        }
        if (host.isEmpty() || database.isEmpty()) {
            throw new IllegalStateException(NOT_MYSQL);
        }
        return new DatabaseUrl("jdbc:mysql://" + host + "/" + database + "?characterEncoding=UTF-8", username, password);
    }

    /** Percent-decoding only: a '+' in a password stays a '+'. */
    private static String decode(String text) {
        return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String unquote(String text) {
        boolean quoted = text.length() >= 2
                && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"));
        return quoted ? text.substring(1, text.length() - 1) : text;
    }
}
