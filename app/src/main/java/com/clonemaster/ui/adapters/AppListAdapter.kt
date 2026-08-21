
package com.clonemaster.ui.adapters
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.databinding.ItemAppBinding

class AppListAdapter(private val apps: List<AppInfo>, private val onClick: (AppInfo)->Unit): RecyclerView.Adapter<AppListAdapter.VH>() {
    class VH(val binding: ItemAppBinding): RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }
    override fun getItemCount() = apps.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.binding.appName.text = app.appName
        holder.binding.packageName.text = app.packageName
        holder.binding.version.text = app.versionName
        holder.binding.root.setOnClickListener { onClick(app) }
    }
}
