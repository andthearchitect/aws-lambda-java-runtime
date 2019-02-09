package com.ata.aws.lambda;

import java.util.List;
import java.util.Map;

public class SimpleHttpResponse {

    private Integer responseCode;
    private Map<String, List<String>> headers;
    private String body;


    public SimpleHttpResponse(Integer responseCode, Map<String, List<String>> headers, String body) {
        this.responseCode = responseCode;
        this.headers = headers;
        this.body = body;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    @Override
    public String toString() {
        return "SimpleHttpResponse{" +
                "responseCode=" + responseCode +
                ", headers=" + headers +
                ", body='" + body + '\'' +
                '}';
    }
}
