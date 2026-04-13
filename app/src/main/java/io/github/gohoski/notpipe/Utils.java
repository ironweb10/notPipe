package io.github.gohoski.notpipe;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

import android.content.Context;
import android.content.res.Resources;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Gleb on 21.01.2026.
 * Utility methods
 */
public class Utils {
    private static Boolean isV7 = null;

    public static boolean isV7() {
        if (isV7 == null) {
            try {
                isV7 = "armeabi-v7a".equals(Build.class.getField("CPU_ABI").get(null));
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
    public static long parseTextCount(String subscriberCount) {
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
            return Math.round(Double.parseDouble(countStr) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatNumber(Context context, long number) {
        if (number < 1000) return String.valueOf(number);

        Resources res = context.getResources();
        String thousands = res.getString(R.string.thousands_suffix);
        String millions = res.getString(R.string.millions_suffix);
        String billions = res.getString(R.string.billions_suffix);
        String decimalSep = res.getString(R.string.decimal_separator);

        long absNumber = Math.abs(number);
        StringBuffer sb = new StringBuffer();
        if (number < 0) {
            sb.append('-');
        }

        if (absNumber >= 1000000000L) {
            long value = absNumber / 1000000000L;
            sb.append(value);
            sb.append(billions);
        } else if (absNumber >= 1000000L) {
            long value = absNumber / 1000000L;
            sb.append(value);
            sb.append(millions);
        } else if (absNumber >= 10000L) {
            long value = absNumber / 1000L;
            sb.append(value);
            sb.append(thousands);
        } else {
            int truncated = (int) (absNumber / 10); // e.g. 1234 -> 123
            int whole = truncated / 100;            // 1
            int frac  = truncated % 100;            // 23

            sb.append(whole);
            if (frac > 0) {
                sb.append(decimalSep);
                sb.append(frac / 10);
                if (frac % 10 != 0) sb.append(frac % 10);
            }
            sb.append(thousands);
        }

        return sb.toString();
    }

    public static Date parseRelativeDate(String relativeDate) {
        if (relativeDate == null) return null;
        String input = relativeDate.toLowerCase().trim();
        if (input.endsWith("ago"))
            input = input.substring(0, input.length() - 3).trim();
        else if (input.endsWith("назад"))
            input = input.substring(0, input.length() - 5).trim();
        else return null;
        Matcher m = Pattern.compile("^(\\d+)\\s*([a-zа-яё]+)?$").matcher(input);
        if (!m.find()) return null;
        int amount; try {
            amount = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = m.group(2);
        if (unit == null || unit.length() == 0) return null;
        Calendar cal = Calendar.getInstance();
        switch (unit) {
            case "month": case "months": case "месяц": case "месяца": case "месяцев": case "mo":
                cal.add(Calendar.MONTH, -amount); break;
            case "year": case "years": case "год": case "года": case "лет": case "y":
                cal.add(Calendar.YEAR, -amount); break;
            case "day": case "days": case "день": case "дня": case "дней": case "d":
                cal.add(Calendar.DAY_OF_MONTH, -amount); break;
            case "week": case "weeks": case "неделю": case "недели": case "недель": case "w":
                cal.add(Calendar.WEEK_OF_YEAR, -amount); break;
            case "hour": case "hours": case "час": case "часа": case "часов": case "h":
                cal.add(Calendar.HOUR_OF_DAY, -amount); break;
            case "minute": case "minutes": case "минуту": case "минуты": case "минут": case "m":
                cal.add(Calendar.MINUTE, -amount); break;
            default:
                cal.add(Calendar.SECOND, -amount);
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
        } else if (seconds > 30) {
            return res.getQuantityString(R.plurals.seconds_ago, (int) seconds, seconds);
        } else {
            return context.getString(R.string.just_now);
        }
    }

    public static boolean hasConnection(Context context) {
        if (context == null) return true; // Fallback to avoid breaking
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnected();
        } catch (Exception e) {
            return true; // Ignore if permission missing or service unavailable
        }
    }

    /**
     * Blocks the current thread until the internet is available.
     * Safe to run in AsyncTask doInBackground.
     */
    public static void waitForConnection(Context context) throws IOException {
        if (context == null) return;
        boolean waited = false;

        while (!hasConnection(context)) {
            waited = true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // If the user closes the app, the AsyncTask gets cancelled and interrupts the sleep
                Thread.currentThread().interrupt();
                throw new IOException("Request cancelled while waiting for network");
            }
        }

        // Wait extra after network is restored
        if (waited) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}