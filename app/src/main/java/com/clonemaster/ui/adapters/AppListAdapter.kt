package com.clonemaster.ui.adapters

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.clonemaster.R
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.databinding.ItemAppBinding

/**
 * QA Fix: Previously only set appName/packageName/version, no icon, no dynamic update
 * Now: Loads app icon via PackageManager.getApplicationIcon with fallback, shows targetSdk, version, ripple feedback, and supports list update
 */
class AppListAdapter(
    private var apps: List<AppInfo>,
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount() = apps.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.binding.appName.text = app.appName
        holder.binding.packageName.text = app.packageName
        holder.binding.version.text = "v${app.versionName} (${app.versionCode})"

        // Target SDK label
        try {
            holder.binding.targetSdk.text = "SDK ${app.targetSdk}"
        } catch (ignored: Exception) {
            holder.binding.targetSdk.text = ""
        }

        // Load app icon with fallback – independent implementation
        try {
            val pm = holder.itemView.context.packageManager
            val icon = pm.getApplicationIcon(app.packageName)
            holder.binding.appIcon.setImageDrawable(icon)
        } catch (ignored: Exception) {
            try {
                holder.binding.appIcon.setImageResource(R.mipmap.ic_launcher)
            } catch (ignored2: Exception) {
                // ignore
            }
        }

        holder.binding.root.setOnClickListener { onClick(app) }
    }

    fun updateList(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    fun filter(query: String): List<AppInfo> {
        if (query.isEmpty()) return apps
        return apps.filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }
    }
}
