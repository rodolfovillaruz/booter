package com.rodolfo.booter.aws

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Parsers for the handful of EC2 Query API responses Booter uses. */
internal object Ec2Xml {

    class InstancesPage(val instances: List<Ec2Instance>, val nextToken: String?)

    private class Builder {
        var id: String? = null
        var name: String? = null
        var type: String? = null
        var state: String? = null
        var publicIp: String? = null

        fun build(): Ec2Instance? {
            return Ec2Instance(
                id = id ?: return null,
                name = name,
                type = type ?: "unknown",
                state = state ?: "unknown",
                publicIp = publicIp,
            )
        }
    }

    /**
     * Walks DescribeInstancesResponse. Instances live at `reservationSet/item/instancesSet/item`;
     * fields are matched by their path relative to that instance element so nested sets
     * (network interfaces, block devices, ...) can't leak values into the result.
     */
    fun parseInstances(xml: String): InstancesPage {
        val parser = newParser(xml)
        val path = ArrayList<String>()
        val instances = mutableListOf<Ec2Instance>()
        var nextToken: String? = null

        var current: Builder? = null
        var instanceDepth = -1
        var tagKey: String? = null
        var tagValue: String? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    path += parser.name
                    if (current == null && parser.name == "item" &&
                        path.size >= 2 && path[path.size - 2] == "instancesSet"
                    ) {
                        current = Builder()
                        instanceDepth = path.size
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isEmpty()) continue
                    val instance = current
                    if (instance != null) {
                        when (path.subList(instanceDepth, path.size)) {
                            listOf("instanceId") -> instance.id = text
                            listOf("instanceType") -> instance.type = text
                            listOf("ipAddress") -> instance.publicIp = text
                            listOf("instanceState", "name") -> instance.state = text
                            listOf("tagSet", "item", "key") -> tagKey = text
                            listOf("tagSet", "item", "value") -> tagValue = text
                        }
                    } else if (path.size == 2 && path[1] == "nextToken") {
                        nextToken = text
                    }
                }

                XmlPullParser.END_TAG -> {
                    val instance = current
                    if (instance != null) {
                        val closingTag = path.size == instanceDepth + 2 &&
                            parser.name == "item" && path[instanceDepth] == "tagSet"
                        if (closingTag) {
                            if (tagKey == "Name") instance.name = tagValue
                            tagKey = null
                            tagValue = null
                        }
                        if (path.size == instanceDepth) {
                            instance.build()?.let { instances += it }
                            current = null
                        }
                    }
                    path.removeAt(path.lastIndex)
                }

                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return InstancesPage(instances, nextToken)
    }

    /**
     * The direct child fields of each `item` in a top-level set, e.g. `imagesSet` in
     * DescribeImagesResponse. Deeper nested sets are skipped.
     */
    fun parseSetItems(xml: String, setName: String): List<Map<String, String>> {
        val parser = newParser(xml)
        val path = ArrayList<String>()
        val items = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    path += parser.name
                    if (path.size == 3 && path[1] == setName && parser.name == "item") current = mutableMapOf()
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty() && path.size == 4) current?.put(path[3], text)
                }

                XmlPullParser.END_TAG -> {
                    if (path.size == 3 && current != null) {
                        items += current
                        current = null
                    }
                    path.removeAt(path.lastIndex)
                }

                XmlPullParser.END_DOCUMENT -> return items
            }
        }
    }

    /**
     * Walks DescribeSecurityGroupsResponse, noting for each group whether any inbound rule opens
     * TCP port 22 (or all traffic) to an IP range. Rules that only allow other groups don't count.
     */
    fun parseSecurityGroups(xml: String): List<Ec2SecurityGroup> {
        val parser = newParser(xml)
        val path = ArrayList<String>()
        val groups = mutableListOf<Ec2SecurityGroup>()

        var id: String? = null
        var name: String? = null
        var vpcId: String? = null
        var allowsSsh = false
        var protocol: String? = null
        var fromPort: Int? = null
        var toPort: Int? = null
        var hasRange = false

        fun inGroup() = path.size >= 3 && path[1] == "securityGroupInfo" && path[2] == "item"
        fun inRule() = inGroup() && path.size >= 5 && path[3] == "ipPermissions" && path[4] == "item"

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> path += parser.name

                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isEmpty() || !inGroup()) continue
                    val field = path.subList(3, path.size)
                    when {
                        field == listOf("groupId") -> id = text
                        field == listOf("groupName") -> name = text
                        field == listOf("vpcId") -> vpcId = text
                        !inRule() -> {}
                        path.size == 6 && path[5] == "ipProtocol" -> protocol = text
                        path.size == 6 && path[5] == "fromPort" -> fromPort = text.toIntOrNull()
                        path.size == 6 && path[5] == "toPort" -> toPort = text.toIntOrNull()
                        path.size == 8 && path[5] in setOf("ipRanges", "ipv6Ranges") -> hasRange = true
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (inRule() && path.size == 5) {
                        val coversSsh = protocol == "-1" ||
                            (protocol == "tcp" && (fromPort ?: 0) <= 22 && 22 <= (toPort ?: 65535))
                        if (coversSsh && hasRange) allowsSsh = true
                        protocol = null
                        fromPort = null
                        toPort = null
                        hasRange = false
                    } else if (inGroup() && path.size == 3) {
                        val groupId = id
                        val groupVpc = vpcId
                        // EC2-Classic groups have no VPC and can't be used with a subnet.
                        if (groupId != null && groupVpc != null) {
                            groups += Ec2SecurityGroup(groupId, name ?: groupId, groupVpc, allowsSsh)
                        }
                        id = null
                        name = null
                        vpcId = null
                        allowsSsh = false
                    }
                    path.removeAt(path.lastIndex)
                }

                XmlPullParser.END_DOCUMENT -> return groups
            }
        }
    }

    /** Walks DescribeSubnetsResponse, picking up each subnet's Name tag from its `tagSet`. */
    fun parseSubnets(xml: String): List<Ec2Subnet> {
        val parser = newParser(xml)
        val path = ArrayList<String>()
        val subnets = mutableListOf<Ec2Subnet>()
        var fields = mutableMapOf<String, String>()
        var tagKey: String? = null
        var tagValue: String? = null

        fun inSubnet() = path.size >= 3 && path[1] == "subnetSet" && path[2] == "item"

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> path += parser.name

                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isEmpty() || !inSubnet()) continue
                    when {
                        path.size == 4 -> fields[path[3]] = text
                        path.size == 6 && path[3] == "tagSet" && path[5] == "key" -> tagKey = text
                        path.size == 6 && path[3] == "tagSet" && path[5] == "value" -> tagValue = text
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (inSubnet() && path.size == 5 && path[3] == "tagSet") {
                        val value = tagValue
                        if (tagKey == "Name" && value != null) fields["Name"] = value
                        tagKey = null
                        tagValue = null
                    } else if (inSubnet() && path.size == 3) {
                        val id = fields["subnetId"]
                        val vpcId = fields["vpcId"]
                        if (id != null && vpcId != null) {
                            subnets += Ec2Subnet(
                                id = id,
                                vpcId = vpcId,
                                name = fields["Name"],
                                availabilityZone = fields["availabilityZone"].orEmpty(),
                                defaultForAz = fields["defaultForAz"] == "true",
                            )
                        }
                        fields = mutableMapOf()
                    }
                    path.removeAt(path.lastIndex)
                }

                XmlPullParser.END_DOCUMENT -> return subnets
            }
        }
    }

    /** Text of the first element named [tag], e.g. `value` in DescribeInstanceAttribute. */
    fun firstText(xml: String, tag: String): String? {
        val parser = newParser(xml)
        var inside = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> inside = parser.name == tag
                XmlPullParser.TEXT -> if (inside) return parser.text.trim()
                XmlPullParser.END_TAG -> inside = false
                XmlPullParser.END_DOCUMENT -> return null
            }
        }
    }

    fun parseError(xml: String, httpStatus: Int): AwsException {
        val parsed = runCatching { firstText(xml, "Code") to firstText(xml, "Message") }.getOrNull()
        return AwsException(
            code = parsed?.first ?: "HTTP $httpStatus",
            message = parsed?.second ?: "AWS request failed",
        )
    }

    private fun newParser(xml: String): XmlPullParser =
        Xml.newPullParser().apply { setInput(StringReader(xml)) }
}
