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
    val namespaces: Map<String, String>
)

/**
 * Transforms a parsed binary manifest for cloning:
 *  - rewrites the package attribute
 *  - rewrites provider authorities (exact string values, ";" lists supported)
 *  - removes sharedUserId (incompatible with a different signing key)
 *  - validates package format to prevent INSTALL_FAILED_INVALID_APK
 */
class ManifestCloner {

    companion object {
        val PACKAGE_REGEX = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+\\.?")
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

        // 3. authorities
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

        return ManifestTransformResult(newPkg, authorityMap, removedShared, nsMap)
    }
}

/** Attribute helpers that need document context. */
fun Document.findAttr(el: Element, name: String): Attribute? =
    el.attributes.firstOrNull { a -> strings.getOrElse(a.name) { "" } == name }
