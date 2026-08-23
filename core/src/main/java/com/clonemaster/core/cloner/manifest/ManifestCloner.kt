package com.clonemaster.core.cloner.manifest

import com.clonemaster.core.cloner.CloneRequest
import com.clonemaster.core.cloner.axml.BinaryXml
import com.clonemaster.core.cloner.axml.BinaryXml.Document
import com.clonemaster.core.cloner.axml.BinaryXml.Element
import com.clonemaster.core.cloner.axml.BinaryXml.Node.NamespaceStart
import com.clonemaster.core.cloner.axml.BinaryXml.Attribute

/** Result of a manifest transform. */
data class ManifestTransformResult(
    val newPackage: String,
    val authorityMap: Map<String, String>,
    val removedSharedUserId: Boolean,
    val namespaces: Map<String, String>,
    /** Component/instrumentation names that were rewritten (for diagnostics). */
    val rewrittenComponentNames: List<String> = emptyList(),
    /** Optional-feature effects actually applied (label/version overrides). */
    val appliedOptions: List<String> = emptyList(),
    /** Non-fatal issues (e.g. requested override whose attribute was absent). */
    val warnings: List<String> = emptyList()
)

/**
 * Binary-manifest transformation for cloning.
 *
 * Because the DEX engine renames every type in the original package to the
 * clone package, ALL manifest references that point into that package must be
 * rewritten coherently:
 *   - package attribute
 *   - absolute component names (application/activity/activity-alias/service/
 *     receiver/provider/instrumentation android:name) -> new package,
 *   - android:process values with a package prefix,
 *   - provider authorities (exact string values, ";" lists supported),
 *   - sharedUserId removal (incompatible with a different signing key).
 *
 * Relative component names (".Foo") need no change: they resolve against the
 * (new) manifest package, and the class has been moved there by the DEX engine.
 */
class ManifestCloner {

    companion object {
        val PACKAGE_REGEX = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        private val COMPONENT_ELEMENTS = setOf(
            "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation"
        )
    }

    fun transform(doc: Document, request: CloneRequest): ManifestTransformResult {
        val newPkg = request.clonePackage
        require(newPkg.isNotEmpty()) { "clonePackage is empty" }
        require(PACKAGE_REGEX.matches(newPkg) && !newPkg.endsWith(".")) {
            "Invalid package: $newPkg – must match [a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"
        }
        require(newPkg.length <= 100) { "clonePackage too long (${newPkg.length} > 100)" }

        val nsMap = mutableMapOf<String, String>()
        for (n in doc.nodes) if (n is NamespaceStart) {
            nsMap[doc.strings.getOrElse(n.prefix) { "" }] = doc.strings.getOrElse(n.uri) { "" }
        }
        val androidNsIdx = doc.strings.indexOf(ANDROID_NS)

        val manifest = doc.findFirstElement() ?: error("No <manifest> element found in binary manifest")
        require(doc.elementName(manifest) == "manifest") { "Root element is not <manifest>" }

        // 1. package attribute
        val pkgAttr = doc.findAttr(manifest, "package")
        require(pkgAttr != null) { "Manifest has no package attribute" }
        val original = doc.attrValue(pkgAttr)
        require(original.isNotEmpty()) { "Manifest package attribute is empty" }
        doc.setStringValue(pkgAttr, newPkg)

        // 2. sharedUserId removal
        val sharedUserId = doc.findAttr(manifest, "sharedUserId")
        var removedShared = false
        if (sharedUserId != null) {
            manifest.attributes.remove(sharedUserId)
            removedShared = true
        }

        // 3. component names + processes (rewrite original-package-prefixed values)
        val rewrittenNames = mutableListOf<String>()
        for (el in doc.elements()) {
            val tag = doc.strings.getOrElse(el.name) { "" }
            if (tag !in COMPONENT_ELEMENTS) continue
            val nameAttr = findAndroidAttr(doc, el, androidNsIdx, "name") ?: continue
            val v = doc.attrValue(nameAttr)
            val rewritten = rewriteComponentValue(v, original, newPkg)
            if (rewritten != null) {
                doc.setStringValue(nameAttr, rewritten)
                rewrittenNames.add("$tag: $v -> $rewritten")
            }
            val processAttr = findAndroidAttr(doc, el, androidNsIdx, "process")
            if (processAttr != null) {
                val pv = doc.attrValue(processAttr)
                val pw = rewriteComponentValue(pv, original, newPkg)
                if (pw != null) doc.setStringValue(processAttr, pw)
            }
        }

        // 4. authorities
        val authorityMap = mutableMapOf<String, String>()
        val seen = HashSet<String>()
        for (el in doc.elements()) {
            val authAttr = doc.findAttr(el, "authorities") ?: continue
            val value = doc.attrValue(authAttr)
            if (value.isEmpty()) continue
            var changed = false
            val newValue = value.split(";").joinToString(";") { old ->
                val trimmed = old.trim()
                if (trimmed.isEmpty()) return@joinToString ""
                val mapped = request.authorityMap[trimmed] ?: trimmed
                if (mapped != trimmed) {
                    authorityMap[trimmed] = mapped
                    require(seen.add(mapped)) { "Duplicate new authority collision: $mapped" }
                    changed = true
                }
                mapped
            }
            if (changed) doc.setStringValue(authAttr, newValue)
        }

        // 5. version overrides – MODIFY-ONLY policy: binary-XML attribute
        //    insertion is unsafe (attributes are indexed by the string pool /
        //    resource map), so an absent attribute is reported, never added.
        val warnings = mutableListOf<String>()
        val applied = mutableListOf<String>()
        request.versionNameOverride?.let { vn ->
            val a = findAndroidAttr(doc, manifest, androidNsIdx, "versionName")
            if (a != null) {
                doc.setStringValue(a, vn)
                applied += "versionName -> \"$vn\""
            } else {
                warnings += "versionName override requested but the source manifest has no android:versionName – untouched"
            }
        }
        request.versionCodeOverride?.let { vc ->
            val a = findAndroidAttr(doc, manifest, androidNsIdx, "versionCode")
            if (a != null) {
                val v = vc.coerceIn(1, 2_100_000_000).toInt()
                a.dataType = BinaryXml.Attribute.TYPE_INT
                a.data = v
                a.rawValue = doc.findString(v.toString())
                applied += "versionCode -> $v"
            } else {
                warnings += "versionCode override requested but the source manifest has no android:versionCode – untouched"
            }
        }

        // 6. label override – application label + labels of launcher entry points.
        request.labelOverride?.let { label ->
            val appEl = doc.findFirstElement("application")
            val appLabel = appEl?.let { findAndroidAttr(doc, it, androidNsIdx, "label") }
            if (appLabel != null) {
                doc.setStringValue(appLabel, label)
                applied += "application label -> \"$label\""
            } else {
                warnings += "label override requested but <application> has no android:label – untouched"
            }
            val n = rewriteLauncherLabels(doc, androidNsIdx, label)
            if (n > 0) applied += "launcher-activity label(s) -> \"$label\" ($n)"
        }

        return ManifestTransformResult(newPkg, authorityMap, removedShared, nsMap, rewrittenNames, applied, warnings)
    }

    /**
     * Rewrites android:label on activity/activity-alias elements that carry a
     * MAIN+LAUNCHER intent-filter (i.e. the entry points shown in launchers).
     * Only existing label attributes are modified; count returned.
     * Non-launcher components keep their own labels.
     */
    private fun rewriteLauncherLabels(doc: Document, androidNsIdx: Int, label: String): Int {
        var changed = 0
        var component: Element? = null
        var inFilter = false
        var sawMain = false
        var sawLauncher = false
        for (n in doc.nodes) when (n) {
            is BinaryXml.Node.Elem -> {
                when (doc.elementName(n.element)) {
                    "activity", "activity-alias" -> if (component == null) {
                        component = n.element
                        sawMain = false
                        sawLauncher = false
                        inFilter = false
                    }
                    "intent-filter" -> if (component != null) inFilter = true
                    "action" -> if (inFilter) {
                        val v = findAndroidAttr(doc, n.element, androidNsIdx, "name")?.let { doc.attrValue(it) }
                        if (v == "android.intent.action.MAIN") sawMain = true
                    }
                    "category" -> if (inFilter) {
                        val v = findAndroidAttr(doc, n.element, androidNsIdx, "name")?.let { doc.attrValue(it) }
                        if (v == "android.intent.category.LAUNCHER") sawLauncher = true
                    }
                }
            }
            is BinaryXml.Node.EndElement -> {
                when (doc.strings.getOrElse(n.name) { "" }) {
                    "intent-filter" -> inFilter = false
                    "activity", "activity-alias" -> {
                        val c = component
                        if (c != null && sawMain && sawLauncher) {
                            val l = findAndroidAttr(doc, c, androidNsIdx, "label")
                            if (l != null) {
                                doc.setStringValue(l, label)
                                changed++
                            }
                        }
                        component = null
                        inFilter = false
                    }
                }
            }
            else -> {}
        }
        return changed
    }

    /**
     * Rewrites a component/process value when it references the original package:
     *   - "com.example.App"            -> "com.example.clone1.App"  (absolute)
     *   - ":remote"                    -> unchanged (relative process)
     *   - ".MainActivity"              -> unchanged (relative name; resolves against
     *                                     the NEW package, where the class now lives)
     *   - "com.example:remote"         -> "com.example.clone1:remote"
     * Returns null when no rewrite is needed.
     */
    private fun rewriteComponentValue(v: String, original: String, newPkg: String): String? = when {
        v == original -> newPkg
        v.startsWith("$original.") || v.startsWith("$original:") -> newPkg + v.removePrefix(original)
        else -> null
    }

    /** Attr in the android namespace only (avoids matching same-named non-android attrs). */
    private fun findAndroidAttr(doc: Document, el: Element, androidNsIdx: Int, name: String): Attribute? =
        el.attributes.firstOrNull { a ->
            doc.strings.getOrElse(a.name) { "" } == name &&
                    (a.ns == androidNsIdx || (androidNsIdx < 0 && a.ns == -1))
        }
}

/** Attribute helpers that need document context. */
fun Document.findAttr(el: Element, name: String): Attribute? =
    el.attributes.firstOrNull { a -> strings.getOrElse(a.name) { "" } == name }
