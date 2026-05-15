package com.blockemc.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;

public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([A-Fa-f0-9]{6})");
    private static final char SECTION = '\u00A7';

    private ColorUtil() {
    }

    public static String color(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', translateHexColors(input));
    }

    public static List<String> color(List<String> input) {
        List<String> lines = new ArrayList<>();
        for (String line : input) {
            lines.add(color(line));
        }
        return lines;
    }

    private static String translateHexColors(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder().append(SECTION).append('x');
            for (char ch : hex.toCharArray()) {
                replacement.append(SECTION).append(ch);
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }
}
