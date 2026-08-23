package com.clonemaster.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.clonemaster.R
import com.clonemaster.ui.CompatibilityIndicator
import com.clonemaster.ui.ControlType
import com.clonemaster.ui.OptionItem
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Adapter for individual clone options – independent UI implementation
 * Each option has icon, name, short description, enabled/disabled state, appropriate control
 * Controls: Switches, Checkboxes, Dropdowns, Sliders, Text fields, Lists/editors, Dialogs
 */
class OptionsAdapter(
    private var options: List<OptionItem>,
    private val configValues: MutableMap<String, Any>, // configFieldPath -> value
    private val onOptionChanged: (OptionItem, Any) -> Unit,
    private val onOptionClicked: (OptionItem) -> Unit
) : RecyclerView.Adapter<OptionsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.optionIcon)
        val name: TextView = view.findViewById(R.id.optionName)
        val description: TextView = view.findViewById(R.id.optionDescription)
        val compatibility: TextView = view.findViewById(R.id.optionCompatibility)
        val switchControl: SwitchMaterial? = view.findViewById(R.id.optionSwitch)
        val textField: EditText? = view.findViewById(R.id.optionTextField)
        val dropdown: Spinner? = view.findViewById(R.id.optionDropdown)
        val slider: SeekBar? = view.findViewById(R.id.optionSlider)
        val button: Button? = view.findViewById(R.id.optionButton)
        val advancedBadge: TextView? = view.findViewById(R.id.advancedBadge)
    }

    override fun getItemViewType(position: Int): Int {
        return when (options[position].controlType) {
            ControlType.SWITCH -> 0
            ControlType.TEXT_FIELD -> 1
            ControlType.DROPDOWN -> 2
            ControlType.SLIDER -> 3
            ControlType.BUTTON, ControlType.DIALOG, ControlType.LIST_EDITOR -> 4
            else -> 0
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutRes = when (viewType) {
            0 -> R.layout.item_option_switch
            1 -> R.layout.item_option_text
            2 -> R.layout.item_option_dropdown
            3 -> R.layout.item_option_slider
            4 -> R.layout.item_option_button
            else -> R.layout.item_option_switch
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return VH(view)
    }

    override fun getItemCount() = options.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val option = options[position]
        holder.name.text = option.name
        holder.description.text = option.description

        // Compatibility indicator
        holder.compatibility.text = "${option.compatibility.emoji} ${option.compatibility.label}"
        holder.compatibility.setTextColor(
            when (option.compatibility) {
                CompatibilityIndicator.SUPPORTED -> 0xFF4CAF50.toInt()
                CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY -> 0xFFFFC107.toInt()
                CompatibilityIndicator.KNOWN_LIMITATION -> 0xFFF44336.toInt()
                CompatibilityIndicator.REQUIRES_ROOT -> 0xFFFF9800.toInt()
            }
        )

        // Advanced badge
        holder.advancedBadge?.visibility = if (option.isAdvanced) View.VISIBLE else View.GONE

        // Icon – use default launcher icon for now, could be customized per option
        try {
            holder.icon.setImageResource(R.mipmap.ic_launcher)
        } catch (ignored: Exception) {}

        // Control binding – maps to real config field, no fake switches
        val currentValue = configValues[option.configFieldPath]

        when (option.controlType) {
            ControlType.SWITCH -> {
                holder.switchControl?.let { switch ->
                    switch.isChecked = (currentValue as? Boolean) ?: false
                    switch.setOnCheckedChangeListener { _, isChecked ->
                        configValues[option.configFieldPath] = isChecked
                        onOptionChanged(option, isChecked)

                        // Show warning for dangerous options
                        if (isChecked && option.requiresWarning != null) {
                            android.app.AlertDialog.Builder(holder.itemView.context)
                                .setTitle("Warning")
                                .setMessage(option.requiresWarning)
                                .setPositiveButton("Enable") { _, _ -> }
                                .setNegativeButton("Cancel") { _, _ ->
                                    switch.isChecked = false
                                    configValues[option.configFieldPath] = false
                                    onOptionChanged(option, false)
                                }
                                .show()
                        }
                    }
                }
            }
            ControlType.TEXT_FIELD -> {
                holder.textField?.let { edit ->
                    edit.setText((currentValue as? String) ?: "")
                    edit.setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            val newValue = edit.text.toString()
                            configValues[option.configFieldPath] = newValue
                            onOptionChanged(option, newValue)
                        }
                    }
                }
            }
            ControlType.DROPDOWN -> {
                holder.dropdown?.let { spinner ->
                    // For simplicity, use array of predefined values based on config field
                    val values = getDropdownValues(option)
                    val adapter = ArrayAdapter(holder.itemView.context, android.R.layout.simple_spinner_item, values)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinner.adapter = adapter

                    val current = currentValue?.toString() ?: ""
                    val selectedIndex = if (option.configFieldPath == "display.orientationLock") {
                        // stored value is a raw int ("1"), labels are "Portrait (1)" …
                        com.clonemaster.ui.OptionValueParsers.orientationIndex(current)
                    } else {
                        values.indexOfFirst { it.equals(current, true) }.takeIf { it >= 0 } ?: 0
                    }
                    spinner.setSelection(selectedIndex)

                    // RecyclerView rows rebind often; setSelection() fires
                    // onItemSelected programmatically on the next loop pass.
                    // Swallow exactly that bind-fire or it re-saves a
                    // (possibly default) value for an option the user never
                    // touched — the dropdown equivalent of the preset bug.
                    var bindGuard = true
                    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            if (bindGuard) { bindGuard = false; return }
                            val newValue = values[pos]
                            configValues[option.configFieldPath] = newValue
                            onOptionChanged(option, newValue)
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
            }
            ControlType.SLIDER -> {
                holder.slider?.let { seekBar ->
                    val current = (currentValue as? Int) ?: 50
                    seekBar.progress = current
                    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                configValues[option.configFieldPath] = progress
                                onOptionChanged(option, progress)
                            }
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
            }
            ControlType.BUTTON, ControlType.DIALOG, ControlType.LIST_EDITOR -> {
                holder.button?.let { btn ->
                    btn.text = when (option.controlType) {
                        ControlType.LIST_EDITOR -> "Edit List"
                        ControlType.DIALOG -> "Configure"
                        else -> "Open"
                    }
                    btn.setOnClickListener {
                        onOptionClicked(option)
                    }
                }
            }
            else -> {}
        }

        // Tapping option opens detailed configuration when required
        holder.itemView.setOnClickListener {
            if (option.controlType == ControlType.DIALOG || option.controlType == ControlType.LIST_EDITOR || option.controlType == ControlType.BUTTON) {
                onOptionClicked(option)
            }
        }
    }

    private fun getDropdownValues(option: OptionItem): List<String> {
        return when {
            option.configFieldPath.contains("iconBadge") -> listOf("NONE", "NUMBER", "DOT", "CUSTOM_TEXT")
            option.configFieldPath.contains("darkMode") -> listOf("LIGHT", "DARK", "SYSTEM", "FORCE_DARK")
            option.configFieldPath.contains("orientationLock") -> com.clonemaster.ui.OptionValueParsers.ORIENTATION_LABELS
            option.configFieldPath.contains("appCategory") -> listOf("undefined", "game", "audio", "video", "image", "social", "news", "maps", "productivity")
            option.configFieldPath.contains("compression") -> listOf("NONE", "ZIP", "GZIP", "ZSTD")
            option.configFieldPath.contains("encryption") -> listOf("NONE", "AES256", "CHACHA20")
            option.configFieldPath.contains("deviceProfile") || option.configFieldPath.contains("physicalDeviceProfileId") -> listOf("pixel8_pro", "pixel7a", "s24_ultra", "a54", "oneplus12", "xiaomi14pro", "nothing2", "fold5")
            option.configFieldPath.contains("injectMode") -> listOf("DOCUMENT_START", "DOCUMENT_END", "DOCUMENT_IDLE")
            option.configFieldPath.contains("rootHideLevel") -> listOf("OFF", "BASIC", "STANDARD", "AGGRESSIVE")
            option.configFieldPath.contains("emulatorHideLevel") -> listOf("OFF", "BASIC", "STANDARD", "FULL")
            else -> listOf("Default", "Enabled", "Disabled")
        }
    }

    fun updateOptions(newOptions: List<OptionItem>) {
        options = newOptions
        notifyDataSetChanged()
    }

    fun filter(query: String, allOptions: List<OptionItem>): List<OptionItem> {
        if (query.isEmpty()) return allOptions
        val lower = query.lowercase()
        return allOptions.filter { option ->
            option.name.lowercase().contains(lower) ||
                    option.description.lowercase().contains(lower) ||
                    option.category.displayName.lowercase().contains(lower) ||
                    option.configFieldPath.lowercase().contains(lower) ||
                    option.aliases.any { it.lowercase().contains(lower) }
        }
    }
}
