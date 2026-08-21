package com.clonemaster.viewmod

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.clonemaster.cloning.models.ViewModRule
import org.json.JSONObject

/**
 * Runtime/layout inspection and customization system
 */
class ViewInspector {

    data class ViewNode(
        val idName: String,
        val className: String,
        val text: String,
        val bounds: String,
        val children: List<ViewNode>
    )

    fun dumpHierarchy(activity: Activity): ViewNode {
        val root = activity.window.decorView as ViewGroup
        return dumpView(root)
    }

    private fun dumpView(view: View): ViewNode {
        val idName = try { view.resources.getResourceEntryName(view.id) } catch (ignored: Exception) { "no-id" }
        val text = (view as? android.widget.TextView)?.text?.toString() ?: ""
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val bounds = "${loc[0]},${loc[1]},${loc[0]+view.width},${loc[1]+view.height}"
        val children = if (view is ViewGroup) {
            (0 until view.childCount).map { dumpView(view.getChildAt(it)) }
        } else emptyList()
        return ViewNode(idName, view.javaClass.simpleName, text, bounds, children)
    }

    fun search(root: ViewNode, query: String): List<ViewNode> {
        val result = mutableListOf<ViewNode>()
        fun dfs(node: ViewNode) {
            if (node.idName.contains(query, true) || node.text.contains(query, true) || node.className.contains(query, true)) {
                result.add(node)
            }
            node.children.forEach { dfs(it) }
        }
        dfs(root)
        return result
    }
}

class ViewModificationEngine {

    private val rules = mutableListOf<ViewModRule>()

    fun loadRules(json: String) {
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                rules.add(
                    ViewModRule(
                        id = obj.optString("id"),
                        activityPattern = obj.optString("activityPattern", "*"),
                        viewIdName = obj.optString("viewIdName"),
                        searchText = obj.optString("searchText"),
                        action = com.clonemaster.cloning.models.ViewModAction.valueOf(obj.optString("action", "HIDE")),
                        replacementText = obj.optString("replacementText"),
                        styleJson = obj.optString("styleJson")
                    )
                )
            }
        } catch (ignored: Exception) {}
    }

    fun apply(activity: Activity) {
        val root = activity.window.decorView as ViewGroup
        applyRecursive(root, activity.javaClass.name)
    }

    private fun applyRecursive(view: View, activityName: String) {
        rules.filter { it.enabled && (it.activityPattern == "*" || activityName.contains(it.activityPattern)) }.forEach { rule ->
            val matches = when {
                rule.viewIdName.isNotEmpty() -> {
                    try { view.resources.getResourceEntryName(view.id) == rule.viewIdName } catch (ignored: Exception) { false }
                }
                rule.searchText.isNotEmpty() -> {
                    (view as? android.widget.TextView)?.text?.toString()?.contains(rule.searchText) == true
                }
                else -> false
            }
            if (matches) {
                when (rule.action) {
                    com.clonemaster.cloning.models.ViewModAction.HIDE -> view.visibility = View.GONE
                    com.clonemaster.cloning.models.ViewModAction.SHOW -> view.visibility = View.VISIBLE
                    com.clonemaster.cloning.models.ViewModAction.REPLACE_TEXT -> (view as? android.widget.TextView)?.text = rule.replacementText
                    com.clonemaster.cloning.models.ViewModAction.RESTYLE -> applyStyle(view, rule.styleJson)
                    com.clonemaster.cloning.models.ViewModAction.REMOVE -> (view.parent as? ViewGroup)?.removeView(view)
                    com.clonemaster.cloning.models.ViewModAction.DISABLE_CLICK -> view.isClickable = false
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursive(view.getChildAt(i), activityName)
            }
        }
    }

    private fun applyStyle(view: View, styleJson: String) {
        try {
            val json = JSONObject(styleJson)
            json.optString("background")?.let { color ->
                if (color.startsWith("#")) view.setBackgroundColor(android.graphics.Color.parseColor(color))
            }
            if (view is android.widget.TextView) {
                json.optString("textColor")?.let { c ->
                    if (c.startsWith("#")) view.setTextColor(android.graphics.Color.parseColor(c))
                }
                json.optString("textSize")?.let { s ->
                    view.textSize = s.toFloat()
                }
            }
        } catch (ignored: Exception) {}
    }

    fun saveRules(): String {
        val arr = org.json.JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("activityPattern", rule.activityPattern)
            obj.put("viewIdName", rule.viewIdName)
            obj.put("searchText", rule.searchText)
            obj.put("action", rule.action.name)
            obj.put("replacementText", rule.replacementText)
            obj.put("styleJson", rule.styleJson)
            arr.put(obj)
        }
        return arr.toString()
    }
}
