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
