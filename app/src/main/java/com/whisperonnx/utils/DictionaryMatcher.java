package com.whisperonnx.utils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces whole words or phrases in one pass, ignoring case. Free of Android dependencies. */
public final class DictionaryMatcher {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");

    private DictionaryMatcher() {}

    public static String apply(String text, Map<String, String> replacements) {
        if (text == null || text.isEmpty() || replacements.isEmpty()) return text;

        Map<String, String> byKey = new HashMap<>();
        List<String> heardPhrases = new ArrayList<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            byKey.put(key(entry.getKey()), entry.getValue());
            heardPhrases.add(entry.getKey());
        }
        // Longest first, so that a phrase wins over a word it contains
        heardPhrases.sort(Comparator.comparingInt(String::length).reversed());

        StringBuilder alternatives = new StringBuilder();
        for (String heard : heardPhrases) {
            if (alternatives.length() > 0) alternatives.append('|');
            alternatives.append(Pattern.quote(heard));
        }
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = byKey.get(key(matcher.group()));
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Replaces each word that looks like a dictionary word: same first letter, ignoring accents,
     * doubled letters and sounds written alike, with a few different letters in long words.
     * Lowercase words are treated more strictly, as they are more likely to be ordinary words.
     * A word that resembles two dictionary words equally is left alone.
     */
    public static String applySimilar(String text, Collection<String> dictionaryWords) {
        if (text == null || text.isEmpty() || dictionaryWords.isEmpty()) return text;

        Set<String> exactWords = new HashSet<>();
        Map<String, String> byNormalized = new HashMap<>();
        for (String word : dictionaryWords) {
            exactWords.add(word);
            if (word.trim().contains(" ")) continue;
            byNormalized.put(normalize(word), word);
        }

        Matcher matcher = WORD.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = exactWords.contains(token) ? token : closest(token, byNormalized);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String closest(String token, Map<String, String> byNormalized) {
        String normalized = normalize(token);
        if (normalized.isEmpty()) return token;
        int limit = allowedDifferences(normalized.length(), Character.isUpperCase(token.codePointAt(0)));
        int bestDistance = Integer.MAX_VALUE;
        String best = null;
        boolean tied = false;
        for (Map.Entry<String, String> entry : byNormalized.entrySet()) {
            String candidate = entry.getKey();
            if (candidate.isEmpty() || candidate.charAt(0) != normalized.charAt(0)) continue;
            int distance = distance(normalized, candidate);
            if (distance > limit) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
                tied = false;
            } else if (distance == bestDistance) {
                tied = true;
            }
        }
        return best == null || tied ? token : best;
    }

    private static int allowedDifferences(int length, boolean capitalized) {
        if (length < 5) return 0;
        if (capitalized) return length < 8 ? 1 : 2;
        return length < 8 ? 0 : 1;
    }

    static String normalize(String word) {
        String plain = Normalizer.normalize(word.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('y', 'i')
                .replace('v', 'b');
        StringBuilder collapsed = new StringBuilder();
        for (int i = 0; i < plain.length(); i++) {
            if (i == 0 || plain.charAt(i) != plain.charAt(i - 1)) collapsed.append(plain.charAt(i));
        }
        return collapsed.toString();
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1, previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    public static String key(String phrase) {
        return phrase.trim().toLowerCase(Locale.ROOT);
    }
}
