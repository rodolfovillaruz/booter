package com.rodolfo.booter.aws

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /**
     * The images offered when launching: the latest Amazon Linux 2023 and Ubuntu 24.04 builds,
     * then your own AMIs, newest first. All x86_64, since every size in [INSTANCE_SIZES] is.
     */
    suspend fun describeLaunchImages(): List<Ec2Image> = coroutineScope {
        val amazonLinux = async { latestImage("amazon", "al2023-ami-2023.*-x86_64", "Amazon Linux 2023") }
        val ubuntu = async {
            latestImage(UBUNTU_OWNER, "ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*", "Ubuntu 24.04")
        }
        val own = async {
            describeImages("self", namePattern = null)
                .map { it.copy(label = it.name) }
                .sortedByDescending { it.creationDate }
        }
        listOfNotNull(amazonLinux.await(), ubuntu.await()) + own.await()
    }

    suspend fun describeKeyPairs(): List<String> =
        Ec2Xml.parseSetItems(call("DescribeKeyPairs", emptyMap()), "keySet")
            .mapNotNull { it["keyName"] }
            .sortedBy { it.lowercase() }

    /**
     * Launches one instance into the default VPC, subnet, and security group. Reusing the same
     * [clientToken] makes a repeated call return the first launch instead of starting another.
     */
    suspend fun runInstance(
        name: String,
        imageId: String,
        instanceType: String,
        keyName: String?,
        clientToken: String,
    ): Ec2Instance {
        val params = buildMap {
            put("ImageId", imageId)
            put("InstanceType", instanceType)
            put("MinCount", "1")
            put("MaxCount", "1")
            put("ClientToken", clientToken)
            // Booter's resize flow relies on a shutdown stopping the instance, never terminating it.
            put("InstanceInitiatedShutdownBehavior", "stop")
            keyName?.let { put("KeyName", it) }
            if (name.isNotBlank()) {
                put("TagSpecification.1.ResourceType", "instance")
                put("TagSpecification.1.Tag.1.Key", "Name")
                put("TagSpecification.1.Tag.1.Value", name)
            }
        }
        val launched = Ec2Xml.parseInstances(call("RunInstances", params)).instances.firstOrNull()
            ?: throw AwsException("EmptyResponse", "AWS didn't return the new instance")
        return launched.copy(name = name.takeIf { it.isNotBlank() })
    }

    private suspend fun latestImage(owner: String, namePattern: String, label: String): Ec2Image? =
        describeImages(owner, namePattern).maxByOrNull { it.creationDate }?.copy(label = label)

    private suspend fun describeImages(owner: String, namePattern: String?): List<Ec2Image> {
        val params = buildMap {
            put("Owner.1", owner)
            put("Filter.1.Name", "architecture")
            put("Filter.1.Value.1", "x86_64")
            put("Filter.2.Name", "state")
            put("Filter.2.Value.1", "available")
            if (namePattern != null) {
                put("Filter.3.Name", "name")
                put("Filter.3.Value.1", namePattern)
            }
        }
        return Ec2Xml.parseSetItems(call("DescribeImages", params), "imagesSet").mapNotNull { fields ->
            val id = fields["imageId"] ?: return@mapNotNull null
            val imageName = fields["name"] ?: id
            Ec2Image(id = id, name = imageName, creationDate = fields["creationDate"].orEmpty(), label = imageName)
        }
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
        /** Canonical's account, which publishes the official Ubuntu AMIs. */
        const val UBUNTU_OWNER = "099720109477"
        const val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
    }
}
