package com.rodolfo.booter.aws

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS Signature Version 4 signer for POST requests to the root path,
 * which is all the EC2 Query API needs.
 */
internal object SigV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private val AMZ_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** Returns the headers to attach to the request (besides Host, which HttpURLConnection sets). */
    fun signPost(
        credentials: AwsCredentials,
        region: String,
        service: String,
        host: String,
        contentType: String,
        body: String,
        now: Instant = Instant.now(),
    ): Map<String, String> {
        val amzDate = AMZ_DATE.format(now)
        val date = amzDate.substring(0, 8)
        val scope = "$date/$region/$service/aws4_request"
        val signedHeaders = "content-type;host;x-amz-date"

        val canonicalRequest = buildString {
            append("POST\n")
            append("/\n")
            append("\n") // no query string
            append("content-type:$contentType\n")
            append("host:$host\n")
            append("x-amz-date:$amzDate\n")
            append("\n")
            append("$signedHeaders\n")
            append(sha256Hex(body.toByteArray()))
        }
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"

        var key = hmac("AWS4${credentials.secretAccessKey}".toByteArray(), date)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hmac(key, stringToSign).toHex()

        return mapOf(
            "Content-Type" to contentType,
            "X-Amz-Date" to amzDate,
            "Authorization" to "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature",
        )
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray())
        }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
