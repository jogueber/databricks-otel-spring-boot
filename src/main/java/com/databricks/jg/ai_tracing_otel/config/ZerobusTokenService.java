package com.databricks.jg.ai_tracing_otel.config;

import com.databricks.sdk.core.DatabricksConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches OAuth tokens scoped to the Zerobus Direct Write API.
 * Zerobus requires a token with resource=api://databricks/workspaces/{id}/zerobusDirectWriteApi,
 * which differs from the standard workspace token that DatabricksConfig.authenticate() returns.
 * Tokens are cached per table and refreshed 60 seconds before expiry.
 */
@Component
@ConditionalOnProperty(prefix = "databricks.otel", name = "export-mode", havingValue = "zerobus")
class ZerobusTokenService {

    private final DatabricksConfig config;
    private final String resource;
    private final RestClient restClient;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    ZerobusTokenService(DatabricksConfig config, DatabricksProperties properties) {
        this.config = config;
        String workspaceId = properties.otel().zerobusEndpoint().split("\\.")[0];
        this.resource = "api://databricks/workspaces/" + workspaceId + "/zerobusDirectWriteApi";
        this.restClient = RestClient.builder().baseUrl(config.getHost()).build();
    }

    String getToken(String tableFullName) {
        return tokenCache.compute(tableFullName, (table, cached) -> {
            if (cached != null && !cached.isExpired()) {
                return cached;
            }
            return fetchToken(table);
        }).value();
    }

    private CachedToken fetchToken(String tableFullName) {
        var formData = new LinkedMultiValueMap<String, String>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", config.getClientId());
        formData.add("client_secret", config.getClientSecret());
        formData.add("scope", "all-apis");
        formData.add("resource", resource);
        formData.add("authorization_details", buildAuthDetails(tableFullName));

        var response = restClient.post()
                .uri("/oidc/v1/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Token response missing access_token");
        }
        long expiresIn = response.expiresIn() != null ? response.expiresIn() : 3600;
        return new CachedToken(response.accessToken(), Instant.now().plusSeconds(expiresIn - 60));
    }

    private static String buildAuthDetails(String tableFullName) {
        String[] parts = tableFullName.split("\\.");
        String catalog = parts[0];
        String schema = parts[0] + "." + parts[1];
        return """
                [{"type":"unity_catalog_privileges","privileges":["USE CATALOG"],"object_type":"CATALOG","object_full_path":"%s"},\
                {"type":"unity_catalog_privileges","privileges":["USE SCHEMA"],"object_type":"SCHEMA","object_full_path":"%s"},\
                {"type":"unity_catalog_privileges","privileges":["SELECT","MODIFY"],"object_type":"TABLE","object_full_path":"%s"}]"""
                .formatted(catalog, schema, tableFullName);
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {}

    record CachedToken(String value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
