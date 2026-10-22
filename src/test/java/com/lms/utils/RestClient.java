package com.lms.utils;

import com.lms.config.EnvironmentConfig;
import com.lms.config.TestConfig;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Thin wrapper around RestAssured. Centralizes base URI resolution, default headers,
 * timeouts, and request/response logging so individual API clients (Phase 3+) stay
 * focused on endpoint paths and payloads.
 *
 * Auth is explicit: callers pass a bearer token (or null) rather than this class
 * reaching into ScenarioContext, since a single test often juggles both a student and
 * an admin token at once (see cross-functional workflows, Phase 6).
 */
public class RestClient {

    private final RequestSpecification baseSpec;

    public RestClient() {
        this(EnvironmentConfig.getApiBaseUrl());
    }

    public RestClient(String baseUrl) {
        RestAssuredConfig config = RestAssuredConfig.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", TestConfig.getConnectionTimeoutMs())
                        .setParam("http.socket.timeout", TestConfig.getRequestTimeoutMs()));

        this.baseSpec = new RequestSpecBuilder()
                .setBaseUri(baseUrl)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(config)
                .addFilter(new RequestLoggingFilter())
                .addFilter(new ResponseLoggingFilter())
                .build();
    }

    private RequestSpecification spec(String bearerToken) {
        RequestSpecification request = given().spec(baseSpec);
        if (bearerToken != null && !bearerToken.isEmpty()) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        return request;
    }

    public Response get(String path) {
        return get(path, null, null);
    }

    public Response get(String path, String bearerToken) {
        return get(path, null, bearerToken);
    }

    public Response get(String path, Map<String, ?> queryParams, String bearerToken) {
        RequestSpecification request = spec(bearerToken);
        if (queryParams != null) {
            request = request.queryParams(queryParams);
        }
        return request.when().get(path).then().extract().response();
    }

    public Response post(String path, Object body) {
        return post(path, body, null);
    }

    public Response post(String path, Object body, String bearerToken) {
        return spec(bearerToken).body(body).when().post(path).then().extract().response();
    }

    public Response put(String path, Object body, String bearerToken) {
        return spec(bearerToken).body(body).when().put(path).then().extract().response();
    }

    public Response patch(String path, Object body, String bearerToken) {
        return spec(bearerToken).body(body).when().patch(path).then().extract().response();
    }

    public Response delete(String path, String bearerToken) {
        return spec(bearerToken).when().delete(path).then().extract().response();
    }

    public <T> T deserialize(Response response, Class<T> clazz) {
        return response.as(clazz);
    }
}
