package com.cleaner.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

/**
 * 重复文件组的 RecyclerView 适配器
 *
 * 使用两种视图类型:
 * - TYPE_GROUP_HEADER: 重复组头部（文件名、大小、数量）
 * - TYPE_FILE_ENTRY: 组内单个文件条目（路径、时间、删除开关）
 *
 * @property groups 重复文件组列表
 * @property onDeleteToggle 删除标记变更回调
 */
class DuplicateGroupAdapter(
    private val groups: List<DuplicateGroup>,
    private val onDeleteToggle: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP_HEADER = 0
        private const val TYPE_FILE_ENTRY = 1

        /**
         * 格式化文件大小为人类可读字符串
         *
         * @param bytes 字节数
         * @return 格式化字符串 (如 "1.23 MB")
         */
        @JvmStatic
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val index = digitGroups.coerceIn(0, units.size - 1)
            return String.format(
                Locale.getDefault(), "%.1f %s",
                bytes / Math.pow(1024.0, index.toDouble()), units[index]
            )
        }
    }

    /**
     * 扁平化列表项
     */
    private sealed class FlatItem {
        data class GroupHeader(val group: DuplicateGroup, val groupIndex: Int) : FlatItem()
        data class FileItem(val group: DuplicateGroup, val fileIndex: Int) : FlatItem()
    }

    private val items = buildFlatList()

    /**
     * 构建扁平化展示列表
     */
    private fun buildFlatList(): List<FlatItem> {
        val list = mutableListOf<FlatItem>()
        groups.forEachIndexed { gi, group ->
            list.add(FlatItem.GroupHeader(group, gi))
            group.files.forEachIndexed { fi, _ ->
                list.add(FlatItem.FileItem(group, fi))
            }
        }
        return list
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is FlatItem.GroupHeader -> TYPE_GROUP_HEADER
        is FlatItem.FileItem -> TYPE_FILE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP_HEADER -> {
                val view = inflater.inflate(
                    R.layout.item_duplicate_group, parent, false
                )
                GroupHeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(
                    R.layout.item_file_entry, parent, false
                )
                FileEntryViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FlatItem.GroupHeader -> (holder as GroupHeaderViewHolder).bind(item.group)
            is FlatItem.FileItem -> (holder as FileEntryViewHolder).bind(
                item.group, item.fileIndex
            )
        }
    }

    /**
     * 重复组头部 ViewHolder
     */
    inner class GroupHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.card_group)
        private val ivIcon: ImageView = view.findViewById(R.id.iv_group_icon)
        private val tvName: TextView = view.findViewById(R.id.tv_group_name)
        private val tvInfo: TextView = view.findViewById(R.id.tv_group_info)
        private val tvWaste: TextView = view.findViewById(R.id.tv_group_waste)

        fun bind(group: DuplicateGroup) {
            tvName.text = group.fileName
            tvInfo.text = itemView.context.getString(
                R.string.group_count, group.files.size
            ) + "  ·  " + formatSize(group.fileSize)
            val waste = group.wasteSize()
            tvWaste.text = if (waste > 0) formatSize(waste) else ""

            // 根据文件扩展名设置图标
            val ext = group.fileName.substringAfterLast('.', "").lowercase()
            ivIcon.setImageResource(
                when {
                    ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") ->
                        android.R.drawable.ic_menu_gallery
                    ext in listOf("mp4", "avi", "mkv", "mov", "wmv") ->
                        android.R.drawable.ic_menu_view
                    ext in listOf("mp3", "flac", "wav", "aac", "ogg") ->
                        android.R.drawable.ic_menu_slideshow
                    ext in listOf("apk") -> android.R.drawable.ic_menu_manage
                    ext in listOf("pdf") -> android.R.drawable.ic_menu_agenda
                    ext in listOf("zip", "rar", "7z", "tar", "gz") ->
                        android.R.drawable.ic_menu_compass
                    else -> android.R.drawable.ic_menu_info_details
                }
            )
        }
    }

    /**
     * 单文件条目 ViewHolder
     */
    inner class FileEntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val switchDelete: MaterialSwitch = view.findViewById(R.id.switch_delete)
        private val tvPath: TextView = view.findViewById(R.id.tv_file_path)
        private val tvMeta: TextView = view.findViewById(R.id.tv_file_meta)
        private val chipKeep: Chip = view.findViewById(R.id.chip_keep)

        fun bind(group: DuplicateGroup, fileIndex: Int) {
            val entry = group.files[fileIndex]
            tvPath.text = entry.path
            tvMeta.text = entry.formattedDate

            // 判断是否为"保留"文件（未被标记删除的）
            val isKept = !entry.markedForDeletion
            chipKeep.visibility = if (isKept) View.VISIBLE else View.GONE

            // 开关状态
            switchDelete.setOnCheckedChangeListener(null)
            switchDelete.isChecked = entry.markedForDeletion
            switchDelete.setOnCheckedChangeListener { _, isChecked ->
                // 不允许切换保留文件
                if (isKept && !isChecked) return@setOnCheckedChangeListener
                entry.markedForDeletion = isChecked
                chipKeep.visibility = if (!isChecked && fileIndex == 0) View.VISIBLE else View.GONE
                onDeleteToggle?.invoke()
            }
        }
    }
}