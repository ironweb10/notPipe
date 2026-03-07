package io.github.gohoski.notpipe.api;

import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.gohoski.notpipe.Utils;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/**
 * Created by Gleb on 15.02.2026.
 * Video conversion through 2yxa (pronounced as 'dva uha')
 */

public class DvaUha {
    static final String BASE_URL = "https://cnv.2yxa.mobi";

    public interface Callback {
        void onMessage(String message);
        void onSuccess(String downloadUrl);
        void onError(Exception e);
        String onCaptchaRequired(String imageUrl);
    }

    /**
     * @param codec 0 = mpeg4 1 = h263
     */
    public static void convert(String url, int codec, Callback callback) throws IOException {
        String home = HttpClient.executeToString(new HttpRequest(BASE_URL,"/"));
        Matcher matcher = Pattern.compile("<img src=\"(/zkod\\.php\\?[^\\\"]*)\"").matcher(home);
        if (matcher.find()) {
            String captchaUrl = matcher.group(1);
            String captcha = callback.onCaptchaRequired(Utils.parseUrl(BASE_URL,captchaUrl));
            String ilove2yxa = captchaUrl.split("=")[1];
            matcher = Pattern.compile("(?i)<form[^>]*action=\"([^\"]*)\"").matcher(home);
            if (matcher.find()) {
                String boundary = "----geckoformboundaryfb8c3a1ded4b3d5c1242a0ba7d020ff";
                String boundaryLine = "--" + boundary;
                HttpRequest req = new HttpRequest.Builder(BASE_URL, "/" + matcher.group(1).replace("amp;",""), "POST")
                        .addHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .addHeader("Cookie", "ILOVE2YXA=" + ilove2yxa)
                        .addHeader("Origin", "https://cnv.2yxa.mobi")
                        .addHeader("Referer", "https://cnv.2yxa.mobi/")
                        .body(boundaryLine + "\r\n" +
                                "Content-Disposition: form-data; name=\"MAX_FILE_SIZE\"\r\n\r\n104857600\r\n" +
                                boundaryLine + "\r\n" +
                                "Content-Disposition: form-data; name=\"file_upload\"; filename=\"\"\r\n" +
                                "Content-Type: application/octet-stream\r\n\r\n\r\n" +
                                boundaryLine + "\r\n" +
                                "Content-Disposition: form-data; name=\"url\"\r\n\r\n" + url + "\r\n" +
                                boundaryLine + "\r\n" +
                                "Content-Disposition: form-data; name=\"ispage\"\r\n\r\nno\r\n" +
                                boundaryLine + "\r\n" +
                                "Content-Disposition: form-data; name=\"name\"\r\n\r\nvideoImporting\r\n" +
                                boundaryLine + "\r\n" +
                                "Content-Disposition: form-data; name=\"secretKey\"\r\n\r\n" +
                                captcha + "\r\n" +
                                boundaryLine + "\r\n" +
                                "Content-Disposition: form-data; name=\"golink\"\r\n\r\nЗагрузить\r\n" +
                                boundaryLine + "--\r\n")
                        .build();
                String i = HttpClient.executeToString(req);
                System.out.println(i);
                matcher = Pattern.compile("<input[^>]*name=\"id\"[^>]*value=\"([^\"]*)\"").matcher(i);
                if (matcher.find()) {
                    String id = matcher.group(1);
                    req = new HttpRequest.Builder(BASE_URL, "/import.php","GET")
                            .addHeader("Cookie", "ILOVE2YXA=" + ilove2yxa)
                            .addParam("vb", "501").addParam("kadr","0")
                            .addParam("maschtab","1").addParam("vcodec","auto")
                            .addParam("zvuk","aac").addParam("ab","48")
                            .addParam("stereo","1").addParam("volume","100")
                            .addParam("ot","00:00").addParam("do","")
                            .addParam("gab1", "640").addParam("gab2","360")
                            .addParam("id", id).addParam("ext", "mp4")
                            .addParam("precet", "0").addParam("ILOVE2YXA",ilove2yxa)
                            .addParam("mode", "makehandvideo")
                            .addParam("doconv","конвертировать!").build();
                    boolean isFinished = false;
                    Pattern refresh = Pattern.compile("<a\\s+(?=[^>]*class=\"vse\")[^>]*href=\"([^\"]*)\""),
                            message = Pattern.compile("(?s)<div class=\"content\">(.*?)</div>");
                    while (!isFinished) {
                        try {
                            i = HttpClient.executeToString(req);
                            Matcher refreshMatcher = refresh.matcher(i);
                            if (refreshMatcher.find()) {
                                String nextUrl = refreshMatcher.group(1).replace("amp;", "");
                                Matcher messageMatcher = message.matcher(i);
                                if (messageMatcher.find()) {
                                    String cleanMessage = messageMatcher.group(1)
                                            .replaceAll("(?s)<!--.*?-->", "")
                                            .replace("<br/>", " ")
                                            .replaceAll("<[^>]+>", "")
                                            .replaceAll("\\s+", " ").trim()
                                            .replace("&#8470;", "№");
                                    callback.onMessage(cleanMessage);
                                } else {
                                    Log.w("DvaUha", "WTF is happening why is there no message found");
                                }
                                req = new HttpRequest.Builder(BASE_URL, nextUrl)
                                        .addHeader("Cookie", "ILOVE2YXA=" + ilove2yxa).build();
                                Thread.sleep(5000);
                            } else {
                                Matcher successMatcher = Pattern.compile("<input[^>]*name=\"ln\"[^>]*value=\"([^\"]*)\"").matcher(i);
                                if (successMatcher.find()) {
                                    callback.onSuccess(successMatcher.group(1));
                                } else {
                                    callback.onError(new Exception(i.contains("<div>Время ссылки истекло, найдите файл заново!</div>")
                                            ? "Unfortunately, 2yxa is down. Please try again later." : "Converted video URL not found. Is 2yxa stable?"));
                                }
                                isFinished = true;
                            }
                        } catch(Exception e) {
                            callback.onError(new Exception("Polling error: " + e.getMessage(), e));
                            isFinished = true;
                        }
                    }
                } else {
                    callback.onError(new Exception("No import ID found. Is 2yxa stable?"));
                }
            } else {
                callback.onError(new Exception("No form found. Is 2yxa stable?"));
            }
        } else {
            callback.onError(new Exception("No CAPTCHA found. Is 2yxa stable?"));
        }
    }
}
