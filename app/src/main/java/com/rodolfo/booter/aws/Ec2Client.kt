package com.rodolfo.booter.aws

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Talks to the EC2 Query API directly over HTTPS, signed with SigV4. */
class Ec2Client(
    private val credentials: AwsCredentials,
    private val region: String,
) {
    private val host = "ec2.$region.amazonaws.com"

    suspend fun describeInstances(): List<Ec2Instance> {
        val all = mutableListOf<Ec2Instance>()
        var token: String? = null
        do {
            val params = buildMap {
                put("MaxResults", "100")
                token?.let { put("NextToken", it) }
            }
            val page = Ec2Xml.parseInstances(call("DescribeInstances", params))
            all += page.instances
            token = page.nextToken
        } while (token != null)
        return all
            .filter { it.state != "terminated" }
            .sortedBy { it.displayName.lowercase() }
    }

    suspend fun startInstance(instanceId: String) {
        call("StartInstances", mapOf("InstanceId.1" to instanceId))
    }

    /** Only allowed while the instance is fully stopped. */
    suspend fun modifyInstanceType(instanceId: String, instanceType: String) {
        call(
            "ModifyInstanceAttribute",
            mapOf("InstanceId" to instanceId, "InstanceType.Value" to instanceType),
        )
    }

    /** "stop" or "terminate" — what happens when the OS inside the instance shuts down. */
    suspend fun shutdownBehavior(instanceId: String): String? {
        val xml = call(
            "DescribeInstanceAttribute",
            mapOf("InstanceId" to instanceId, "Attribute" to "instanceInitiatedShutdownBehavior"),
        )
        return Ec2Xml.firstText(xml, "value")
    }

    private suspend fun call(action: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val body = (mapOf("Action" to action, "Version" to API_VERSION) + params)
                .entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
            val headers = SigV4.signPost(credentials, region, "ec2", host, CONTENT_TYPE, body)

            val conn = URL("https://$host/").openConnection() as HttpsURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toByteArray()) }

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw Ec2Xml.parseError(text, status)
                text
            } finally {
                conn.disconnect()
            }
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    private companion object {
        const val API_VERSION = "2016-11-15"
        const val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
    }
}
