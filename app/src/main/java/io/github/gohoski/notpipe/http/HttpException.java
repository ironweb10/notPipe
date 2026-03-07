package io.github.gohoski.notpipe.http;

/**
 * Created by Gleb on 21.08.2025.
 */

import java.io.IOException;

public class HttpException extends IOException {
    private final int statusCode;
    private final String responseBody;

    public HttpException(int statusCode, String message, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}