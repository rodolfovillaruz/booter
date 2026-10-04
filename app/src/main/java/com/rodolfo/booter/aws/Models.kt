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

data class Ec2SecurityGroup(
    val id: String,
    val name: String,
    val vpcId: String,
    /** Whether an inbound rule lets some IP range reach TCP port 22. */
    val allowsSsh: Boolean,
)

data class Ec2Subnet(
    val id: String,
    val vpcId: String,
    /** The Name tag, if any. */
    val name: String?,
    val availabilityZone: String,
    /** Whether it's the default subnet for its zone, which only the default VPC has. */
    val defaultForAz: Boolean,
) {
    val label: String get() = listOfNotNull(name, availabilityZone).joinToString(" · ")
}

class AwsException(val code: String, message: String) : Exception("$message ($code)")

/** The only sizes Booter offers for now, with a short spec line for the picker. */
val INSTANCE_SIZES: Map<String, String> = linkedMapOf(
    "t3.micro" to "2 vCPU · 1 GiB RAM",
    "t3.large" to "2 vCPU · 8 GiB RAM",
)
