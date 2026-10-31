package org.campusreader.reader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Public input-contract checks; runs on the host JDK without Android or notification stubs. */
public final class KeywordTermsChecks {
    private static int assertions;
    private static final List<String> failures = new ArrayList<>();

    private interface Action { void run(); }

    private static void equal(Object expected, Object actual, String name) {
        assertions++;
        if (!expected.equals(actual)) failures.add(name + ": expected " + expected + ", actual " + actual);
    }

    private static void rejects(Action action, String name) {
        assertions++;
        try {
            action.run();
            failures.add(name + ": accepted invalid input");
        } catch (IllegalArgumentException expected) {
            // Invalid input must be actionable for the rule editor, not silently shortened.
        } catch (RuntimeException unexpected) {
            failures.add(name + ": wrong exception " + unexpected.getClass().getSimpleName());
        }
    }

    private static String repeated(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static List<String> numberedWords(int count) {
        List<String> result = new ArrayList<>();
        for (int index = 1; index <= count; index++) result.add("关键词" + index);
        return result;
    }

    public static void main(String[] args) {
        equal(Arrays.asList("志愿活动", "任职", "招聘"),
                KeywordTerms.parse(" 志愿活动，任职,招聘\n志愿活动\r\n 任职 ,，\n"),
                "Chinese/English commas and CRLF deduplicate in first-seen order");
        equal(Arrays.asList("Campus", "志愿"), KeywordTerms.parse("Campus,campus,CAMPUS，志愿\n志愿"),
                "ASCII case-insensitive duplicates retain first spelling");
        equal(Arrays.asList("志愿 活动", "学生 服务 中心"),
                KeywordTerms.parse("  志愿 活动  ，学生 服务 中心\n志愿 活动"),
                "Spaces inside Chinese phrases do not split terms");
        equal(Arrays.asList("100%", "a_b", "C:\\campus", "O'Reilly", "[活动].*", "%_\\'"),
                KeywordTerms.parse("100%,a_b,C:\\campus,O'Reilly,[活动].*,%_\\'"),
                "Percent, underscore, backslash, quote and regex punctuation remain literal");

        equal(Collections.emptyList(), KeywordTerms.parse(""), "Empty editor has no default rules");
        equal(Collections.emptyList(), KeywordTerms.parse(" ,，\n\r\n   ,"),
                "Only spaces and delimiters produce no default rules");
        equal(Collections.emptyList(), KeywordTerms.clean(Collections.emptyList()),
                "Empty persisted collection stays empty");
        equal(Collections.singletonList("志愿"), KeywordTerms.clean(Arrays.asList("", " 志愿 ", " ", "志愿")),
                "Blank stored entries ignored and surrounding ordinary spaces trimmed");
        rejects(() -> KeywordTerms.parse(null), "Null editor input rejected");
        rejects(() -> KeywordTerms.clean(null), "Null stored collection rejected");
        rejects(() -> KeywordTerms.clean(Arrays.asList("志愿", null)), "Null stored term rejected");

        String chinese100 = repeated("志", 100);
        equal(Collections.singletonList(chinese100), KeywordTerms.parse(chinese100),
                "Exactly 100 BMP code points accepted");
        rejects(() -> KeywordTerms.parse(chinese100 + "愿"), "101 BMP code points rejected");
        String emoji100 = repeated("\uD83D\uDE80", 100);
        equal(Collections.singletonList(emoji100), KeywordTerms.parse(emoji100),
                "Exactly 100 supplementary Unicode code points accepted");
        rejects(() -> KeywordTerms.parse(emoji100 + "\uD83D\uDE80"), "101 supplementary code points rejected");
        String mixed100 = repeated("志\uD83D\uDE80", 50);
        equal(Collections.singletonList(mixed100), KeywordTerms.parse(" " + mixed100 + " "),
                "100 mixed-width code points accepted after ordinary space trim");
        rejects(() -> KeywordTerms.clean(Collections.singletonList(mixed100 + "愿")),
                "Stored term also obeys Unicode code point maximum");

        List<String> fifty = numberedWords(50);
        equal(fifty, KeywordTerms.parse(String.join("，", fifty)), "Exactly 50 unique terms accepted");
        List<String> fiftyWithDuplicates = new ArrayList<>(fifty);
        fiftyWithDuplicates.addAll(fifty);
        fiftyWithDuplicates.add(" ");
        equal(fifty, KeywordTerms.clean(fiftyWithDuplicates), "Duplicates and blanks do not consume the 50-term quota");
        rejects(() -> KeywordTerms.parse(String.join("\n", numberedWords(51))), "51 unique editor terms rejected");
        rejects(() -> KeywordTerms.clean(numberedWords(51)), "51 unique stored terms rejected");
        List<String> caseFifty = new ArrayList<>(numberedWords(49));
        caseFifty.add("Campus");
        List<String> repeatedCaseFifty = new ArrayList<>(caseFifty);
        repeatedCaseFifty.add("CAMPUS");
        equal(caseFifty, KeywordTerms.clean(repeatedCaseFifty), "Case-only duplicate at limit does not exceed quota");

        int[] controls = {0x0000, 0x0001, 0x0009, 0x000B, 0x001B, 0x001F, 0x007F, 0x0085, 0x009F};
        for (int codePoint : controls) {
            String control = Character.toString((char) codePoint);
            String label = String.format("U+%04X", codePoint);
            rejects(() -> KeywordTerms.parse("志" + control + "愿"), label + " inside editor term rejected");
            rejects(() -> KeywordTerms.parse(control + "志愿"), label + " before editor term rejected");
            rejects(() -> KeywordTerms.parse("志愿" + control), label + " after editor term rejected");
            rejects(() -> KeywordTerms.clean(Collections.singletonList("志" + control + "愿")),
                    label + " inside stored term rejected");
        }
        rejects(() -> KeywordTerms.clean(Collections.singletonList("志愿\n活动")),
                "Stored term cannot contain newline even though editor uses it as delimiter");
        rejects(() -> KeywordTerms.clean(Collections.singletonList("志愿\r活动")),
                "Stored term cannot contain carriage return");

        if (!failures.isEmpty()) {
            for (String failure : failures) System.err.println("FAIL: " + failure);
            throw new AssertionError("Keyword terms checks failed: " + failures.size() + " of " + assertions);
        }
        System.out.println("Keyword terms checks passed: " + assertions);
    }
}
