package com.rodolfo.booter.aws

data class AwsCredentials(val accessKeyId: String, val secretAccessKey: String)

data class Ec2Instance(
    val id: String,
    val name: String?,
    val type: String,
    val state: String,
    val publicIp: String?,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id
}

data class Ec2Image(
    val id: String,
    val name: String,
    /** ISO-8601, so it sorts as a string. */
    val creationDate: String,
    /** Short label for the picker, e.g. "Amazon Linux 2023" or "My AMI". */
    val label: String,
)

class AwsException(val code: String, message: String) : Exception("$message ($code)")

/** The only sizes Booter offers for now, with a short spec line for the picker. */
val INSTANCE_SIZES: Map<String, String> = linkedMapOf(
    "t3.micro" to "2 vCPU · 1 GiB RAM",
    "t3.large" to "2 vCPU · 8 GiB RAM",
)
