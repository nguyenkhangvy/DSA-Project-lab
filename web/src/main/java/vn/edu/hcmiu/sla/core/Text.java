package vn.edu.hcmiu.sla.core;

/** Small text helpers shared by every module. */
public final class Text {

    private Text() {
    }

    /** Like Python's str.strip(): also removes non-breaking spaces pasted from Word, Outlook or a web page. */
    public static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
