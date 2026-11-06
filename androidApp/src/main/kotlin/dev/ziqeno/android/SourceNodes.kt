package dev.ziqeno.android

internal object SourceNodes {
    const val maxActiveNodes = 250

    fun decode(sources: List<VpnSource>, active: () -> Boolean): List<XrayNode> {
        val groups = sources.takeWhile { active() }.map { source ->
            runCatching { XrayEngine.decodeNodes(source.config) }.getOrDefault(emptyList())
                .map { if (source.kind == SourceKind.MANUAL && source.name.isNotBlank()) it.copy(name = source.name) else it }
                .iterator()
        }
        val nodes = linkedMapOf<String, XrayNode>()
        while (nodes.size < maxActiveNodes && active() && groups.any { it.hasNext() }) {
            for (group in groups) {
                if (!group.hasNext()) continue
                val node = group.next()
                nodes.putIfAbsent(node.id, node)
                if (nodes.size == maxActiveNodes) break
            }
        }
        return nodes.values.toList()
    }
}
