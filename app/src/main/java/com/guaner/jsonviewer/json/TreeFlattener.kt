package com.guaner.jsonviewer.json

/**
 * 把树按「展开状态」拍平成可见节点列表，同时建立 path -> node 索引（用于 JSONPath 定位）。
 */
object TreeFlattener {

    /**
     * 建立 path -> node 的全树索引（不依赖展开状态），用于导航模式下按路径定位节点
     * （进入某一层、面包屑跳转、搜索/JSONPath 命中定位都要用到）。
     */
    fun buildIndex(root: TreeNode): Map<String, TreeNode> {
        val index = HashMap<String, TreeNode>()
        fun walk(n: TreeNode) {
            index[n.path] = n
            for (c in n.children) walk(c)
        }
        walk(root)
        return index
    }

    class FlatResult(
        val visible: List<TreeNode>,
        val index: Map<String, TreeNode>
    )

    fun flatten(root: TreeNode, isExpanded: (TreeNode) -> Boolean): FlatResult {
        val visible = ArrayList<TreeNode>()
        val index = HashMap<String, TreeNode>()
        walk(root, isExpanded, visible, index)
        return FlatResult(visible, index)
    }

    private fun walk(
        node: TreeNode,
        isExpanded: (TreeNode) -> Boolean,
        visible: MutableList<TreeNode>,
        index: MutableMap<String, TreeNode>
    ) {
        visible.add(node)
        index[node.path] = node
        if (node.isContainer && isExpanded(node)) {
            for (c in node.children) walk(c, isExpanded, visible, index)
        }
    }

    /** 树搜索结果：命中路径 + 「有命中后代」的路径集合（用于自动展开） */
    class MatchResult(
        val matchedPaths: List<String>,
        val pathsWithMatchBelow: Set<String>
    )

    fun collectMatches(root: TreeNode, query: String): MatchResult {
        val matched = ArrayList<String>()
        val below = HashSet<String>()
        fun visit(node: TreeNode): Boolean {
            val selfHit = node.key?.contains(query, ignoreCase = true) == true ||
                node.scalar?.contains(query, ignoreCase = true) == true
            if (selfHit) matched.add(node.path)
            var childHit = false
            for (c in node.children) {
                if (visit(c)) childHit = true
            }
            if (childHit) below.add(node.path)
            return childHit || selfHit
        }
        visit(root)
        return MatchResult(matched, below)
    }
}
