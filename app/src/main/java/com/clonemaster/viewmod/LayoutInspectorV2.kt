package com.clonemaster.viewmod

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Independent implementation for Layout Inspector improvements
 * Public feature reference: WhatsNew 3.6.0 "Layout Inspector improvements"
 * Equivalent functionality: improved view hierarchy inspection with search, properties, live updates, independent implementation
 */
class LayoutInspectorV2 {

    data class ViewProperty(
        val name: String,
        val value: String,
        val type: String
    )

    data class InspectedView(
        val id: Int,
        val idName: String,
        val className: String,
        val text: String,
        val bounds: String,
        val visibility: String,
        val properties: List<ViewProperty>,
        val children: List<InspectedView>
    )

    fun inspectActivity(activity: Activity): InspectedView {
        val root = activity.window.decorView as ViewGroup
        return inspectView(root, 0)
    }

    private fun inspectView(view: View, depth: Int): InspectedView {
        val idName = try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "no-id" }
        val text = (view as? TextView)?.text?.toString() ?: ""
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val bounds = "(${loc[0]},${loc[1]}-${loc[0]+view.width},${loc[1]+view.height}) ${view.width}x${view.height}"
        val visibility = when (view.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "UNKNOWN"
        }

        val properties = mutableListOf<ViewProperty>().apply {
            add(ViewProperty("alpha", view.alpha.toString(), "float"))
            add(ViewProperty("elevation", view.elevation.toString(), "float"))
            add(ViewProperty("isClickable", view.isClickable.toString(), "boolean"))
            add(ViewProperty("isEnabled", view.isEnabled.toString(), "boolean"))
            add(ViewProperty("isFocused", view.isFocused.toString(), "boolean"))
            add(ViewProperty("isSelected", view.isSelected.toString(), "boolean"))
            if (view is TextView) {
                add(ViewProperty("text", view.text.toString().take(100), "String"))
                add(ViewProperty("textSize", view.textSize.toString(), "float"))
                add(ViewProperty("textColor", view.currentTextColor.toString(), "color"))
            }
            add(ViewProperty("background", view.background?.toString() ?: "null", "Drawable"))
            add(ViewProperty("tag", view.tag?.toString() ?: "null", "Object"))
        }

        val children = if (view is ViewGroup) {
            (0 until view.childCount).map { inspectView(view.getChildAt(it), depth+1) }
        } else emptyList()

        return InspectedView(view.id, idName, view.javaClass.name, text, bounds, visibility, properties, children)
    }

    fun search(root: InspectedView, query: String): List<InspectedView> {
        val result = mutableListOf<InspectedView>()
        fun dfs(node: InspectedView) {
            if (node.idName.contains(query, true) || node.text.contains(query, true) || node.className.contains(query, true) || node.properties.any { it.value.contains(query, true) }) {
                result.add(node)
            }
            node.children.forEach { dfs(it) }
        }
        dfs(root)
        return result
    }

    fun getViewById(root: InspectedView, idName: String): InspectedView? {
        if (root.idName == idName) return root
        for (child in root.children) {
            val found = getViewById(child, idName)
            if (found != null) return found
        }
        return null
    }

    fun generateHierarchyText(root: InspectedView, indent: Int = 0): String {
        val sb = StringBuilder()
        sb.append("  ".repeat(indent))
        sb.append("${root.className} id=${root.idName} text='${root.text.take(30)}' bounds=${root.bounds} visibility=${root.visibility}\n")
        root.children.forEach { sb.append(generateHierarchyText(it, indent+1)) }
        return sb.toString()
    }
}
