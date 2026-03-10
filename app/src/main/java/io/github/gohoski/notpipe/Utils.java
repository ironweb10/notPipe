package io.github.gohoski.notpipe;

import android.os.Build;

import android.content.Context;
import android.content.res.Resources;

import java.util.Calendar;
import java.util.Date;

/**
 * Created by Gleb on 21.01.2026.
 * Utility methods
 */
public class Utils {
    private static Boolean isV7 = null;

    public static boolean isV7() {
        if (isV7 == null) {
            try {
                Object abi = Build.class.getField("CPU_ABI").get(null);
                isV7 = "armeabi-v7a".equals(abi);
            } catch (Exception ignored) {
                isV7 = false;
            }
        }
        return isV7;
    }

    /**
     * Parses and combines a base URL with a given URL.
     * 
     * Examples:
     * - ("http://0.0.0.0", "/videoplayback?id=abc") -> "http://0.0.0.0/videoplayback?id=abc"
     * - ("http://0.0.0.0", "http://0.0.0.0/abc") -> "http://0.0.0.0/abc"
     * - ("http://0.0.0.0", "https://some.domain/abc") -> "http://0.0.0.0/abc"
     * - ("http://0.0.0.0", "http://128.128.128.128/abc") -> "http://0.0.0.0/abc"
     * 
     * @param baseUrl The base URL to use
     * @param url The URL to parse and combine
     * @return The combined URL
     */
    public static String parseUrl(String baseUrl, String url) {
        if (url.startsWith("/")) {
            return baseUrl + url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            int pathStart = url.indexOf('/', url.indexOf("://") + 3);
            if (pathStart != -1) {
                return baseUrl + url.substring(pathStart);
            } else {
                return baseUrl;
            }
        }
        return url;
    }

    /**
     * Formats duration in seconds to a human-readable format.
     * 
     * Examples:
     * - 90 -> "1:30"
     * - 3663 -> "1:01:03"
     * 
     * @param totalSeconds The total duration in seconds
     * @return Formatted duration string
     */
    public static String formatDuration(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (hours > 0) {
            // Format: H:MM:SS (e.g., 1:52:03)
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            // Format: M:SS (e.g., 1:30)
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    /**
     * Parses an Invidious text count string to an integer.
     *
     * Examples:
     * - "1.29K" -> 1290
     * - "2.44M" -> 2440000
     * - "500" -> 500
     * - "2K" -> 2000
     *
     * @param subscriberCount The subscriber count string (e.g., "1.29K", "2.44M", "500")
     * @return The subscriber count as an integer
     */
    public static int parseTextCount(String subscriberCount) {
        if (subscriberCount == null || subscriberCount.length() == 0) {
            return 0;
        }
        String countStr = subscriberCount.trim();
        int multiplier = 1;
        if (countStr.endsWith("K")) {
            multiplier = 1000;
            countStr = countStr.substring(0, countStr.length() - 1);
        } else if (countStr.endsWith("M")) {
            multiplier = 1000000;
            countStr = countStr.substring(0, countStr.length() - 1);
        } else if (countStr.endsWith("B")) {
            multiplier = 1000000000;
            countStr = countStr.substring(0, countStr.length() - 1);
        }
        try {
            return (int) Math.round(Double.parseDouble(countStr) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatNumber(Context context, int number) {
        if (number < 1000) return String.valueOf(number);

        Resources res = context.getResources();
        String thousands = res.getString(R.string.thousands_suffix);
        String millions = res.getString(R.string.millions_suffix);
        String billions = res.getString(R.string.billions_suffix);
        String decimalSep = res.getString(R.string.decimal_separator);

        // Use long to prevent overflow during calculations
        long absNumber = Math.abs((long) number);
        StringBuffer sb = new StringBuffer();
        if (number < 0) {
            sb.append('-');
        }

        if (absNumber >= 1000000000L) {
            long value = (absNumber + 500000000L) / 1000000000L;
            sb.append(value);
            sb.append(billions);
        } else if (absNumber >= 1000000L) {
            long value = (absNumber + 500000L) / 1000000L;
            sb.append(value);
            sb.append(millions);
        } else if (absNumber >= 10000L) {
            long value = (absNumber + 500L) / 1000L;
            sb.append(value);
            sb.append(thousands);
        } else {
            // (9.04K, 1.23K, etc.)
            double value = absNumber / 1000.0;
            // Truncate to 2 decimal places (not round)
            value = Math.floor(value * 100.0) / 100.0;

            int wholePart = (int) value;
            int fractionalPart = (int) ((value - wholePart) * 100.0);
            if (fractionalPart >= 100) {
                wholePart += fractionalPart / 100;
                fractionalPart = fractionalPart % 100;
            }

            sb.append(wholePart);

            // Only show decimal places if they are non-zero
            if (fractionalPart > 0) {
                sb.append(decimalSep);
                int firstDec = fractionalPart / 10;
                int secondDec = fractionalPart % 10;
                sb.append(firstDec);
                sb.append(secondDec);
            }

            sb.append(thousands);
        }

        return sb.toString();
    }

    public static Date parseRelativeDate(String relativeDate) {
        if (relativeDate == null) return null;
        String input = relativeDate/*.toLowerCase()*/.trim();
        if (!input.endsWith("ago")) return null;
        String timePart = input.substring(0, input.length() - 3).trim();
        String[] parts = timePart.split("\\s+");

        if (parts.length < 2) return null;
        int amount;
        try {
            amount = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = parts[1];
        Calendar cal = Calendar.getInstance();
        switch(unit) {
            case "month":
            case "months":
                cal.add(Calendar.MONTH, -amount); break;
            case "year":
            case "years":
                cal.add(Calendar.YEAR, -amount); break;
            case "day":
            case "days":
                cal.add(Calendar.DAY_OF_MONTH, -amount); break;
            case "week":
            case "weeks":
                cal.add(Calendar.WEEK_OF_YEAR, -amount); break;
//            case "hour":
//            case "hours":
            default:
                cal.add(Calendar.HOUR_OF_DAY, -amount);
        }
        return cal.getTime();
    }

    /**
     * Formats a Date into a time-ago string. Made due to DateUtils unreliability
     *
     * @param context The context for accessing resources
     * @param date The date to format
     * @return A string like "50 seconds ago", "6 minutes ago", "2 years ago"
     */
    public static String formatTimeAgo(Context context, Date date) {
        if (date == null) return "";
        long diff = System.currentTimeMillis() - date.getTime();
        if (diff < 0) diff = 0;

        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long weeks = days / 7;
        long months = days / 30;
        long years = days / 365;

        Resources res = context.getResources();
        if (years > 0) {
            return res.getQuantityString(R.plurals.years_ago, (int) years, years);
        } else if (months > 0) {
            return res.getQuantityString(R.plurals.months_ago, (int) months, months);
        } else if (weeks > 0) {
            return res.getQuantityString(R.plurals.weeks_ago, (int) weeks, weeks);
        } else if (days > 0) {
            return res.getQuantityString(R.plurals.days_ago, (int) days, days);
        } else if (hours > 0) {
            return res.getQuantityString(R.plurals.hours_ago, (int) hours, hours);
        } else if (minutes > 0) {
            return res.getQuantityString(R.plurals.minutes_ago, (int) minutes, minutes);
        } else if (seconds > 0) {
            // Only call getQuantityString if seconds is at least 1 to avoid the Android 1.5 crash
            return res.getQuantityString(R.plurals.seconds_ago, (int) seconds, seconds);
        } else {
            // Handle the 0 seconds case
            return context.getString(R.string.just_now);
        }
    }
}