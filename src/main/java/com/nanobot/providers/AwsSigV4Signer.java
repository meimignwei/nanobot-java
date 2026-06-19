package com.nanobot.providers;

import jakarta.annotation.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Minimal AWS Signature V4 signer for Bedrock API calls.
 * Avoids pulling in the full AWS SDK for a single REST API.
 */
final class AwsSigV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private AwsSigV4Signer() {}

    /** Return signed headers (Authorization, X-Amz-Date, X-Amz-Security-Token if present). */
    static Map<String, String> sign(
            String accessKey, String secretKey, @Nullable String sessionToken,
            String region, String service, String method, URI uri,
            Map<String, String> headers, byte[] body
    ) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = now.format(DATE_FMT);
        String timestamp = now.format(TIMESTAMP_FMT);

        // Host header
        String host = uri.getHost();
        int port = uri.getPort();
        if (port > 0 && port != 443 && port != 80) host += ":" + port;

        Map<String, String> signedHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        signedHeaders.put("Host", host);
        if (headers != null) signedHeaders.putAll(headers);
        signedHeaders.put("X-Amz-Date", timestamp);
        if (sessionToken != null && !sessionToken.isEmpty()) {
            signedHeaders.put("X-Amz-Security-Token", sessionToken);
        }

        String canonicalHeaders = signedHeaders.entrySet().stream()
                .map(e -> e.getKey().toLowerCase() + ":" + e.getValue().trim() + "\n")
                .collect(Collectors.joining());
        String signedHeaderNames = signedHeaders.keySet().stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining(";"));

        String payloadHash = hex(sha256(body));
        String canonicalQuery = uri.getRawQuery() != null ? uri.getRawQuery() : "";
        String canonicalRequest = method + "\n"
                + (uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()) + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaderNames + "\n"
                + payloadHash;

        String credentialScope = amzDate + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + timestamp + "\n"
                + credentialScope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = getSigningKey(secretKey, amzDate, region, service);
        String signature = hex(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        String authHeader = ALGORITHM + " Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaderNames + ", Signature=" + signature;

        Map<String, String> result = new TreeMap<>();
        result.put("Authorization", authHeader);
        result.put("X-Amz-Date", timestamp);
        if (sessionToken != null && !sessionToken.isEmpty()) {
            result.put("X-Amz-Security-Token", sessionToken);
        }
        return result;
    }

    static byte[] getSigningKey(String secretKey, String date, String region, String service) throws Exception {
        byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmacSha256(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmacSha256(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
