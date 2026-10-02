package com.cleaner.app

import java.io.File

/**
 * 重复文件组数据模型
 *
 * @property hash 该组文件的内容哈希值 (SHA-256 十六进制)
 * @property fileName 组内第一个文件的文件名 (用于展示)
 * @property fileSize 单个文件的大小 (字节)
 * @property files 该组中所有重复文件的列表
 */
data class DuplicateGroup(
    val hash: String,
    val fileName: String,
    val fileSize: Long,
    val files: MutableList<FileEntry> = mutableListOf()
) {
    /**
     * 自动选择保留最新的文件，其余标记为待删除
     *
     * 策略: 按文件最后修改时间降序排列，保留最新的一个，其余标记删除
     */
    fun autoSelectKeepNewest() {
        // 按修改时间降序排列
        val sorted = files.sortedByDescending { it.lastModified }
        files.clear()
        sorted.forEachIndexed { index, entry ->
            // 第一项（最新的）不标记删除
            entry.markedForDeletion = (index != 0)
            files.add(entry)
        }
    }

    /**
     * 可释放的空间 = 被标记删除的文件大小总和
     */
    fun wasteSize(): Long = files.filter { it.markedForDeletion }.sumOf { fileSize }

    /**
     * 被标记删除的文件数量
     */
    fun deleteCount(): Int = files.count { it.markedForDeletion }

    /**
     * 全选/取消全选
     * 注意: 保留的文件不受影响，不会被选中；始终确保至少一个文件被保护
     *
     * @param select true=全选待删除项, false=取消全部
     */
    fun selectAll(select: Boolean) {
        if (files.isEmpty()) return
        // 优先使用当前未标记的文件作为保留项；若所有文件均已标记，回退到最新文件
        val keepIndex = files.indexOfFirst { !it.markedForDeletion }
            .takeIf { it >= 0 }
            ?: files.indexOfFirst { it.lastModified == files.maxOf { f -> f.lastModified } }
        files.forEachIndexed { index, entry ->
            if (index != keepIndex) {
                entry.markedForDeletion = select
            }
        }
        // 强制保护保留文件，防止 selectAll(true) 将所有文件标记为删除
        files[keepIndex].markedForDeletion = false
    }

    /**
     * 切换全选状态
     * 如果当前有未选中的待删除项，则全选；否则全部取消
     */
    fun toggleSelectAll() {
        if (files.isEmpty()) return
        val keepIndex = files.indexOfFirst { !it.markedForDeletion }
            .takeIf { it >= 0 }
            ?: files.indexOfFirst { it.lastModified == files.maxOf { f -> f.lastModified } }
        val deletable = files.filterIndexed { index, _ -> index != keepIndex }
        val anyUnselected = deletable.any { !it.markedForDeletion }
        selectAll(anyUnselected)
    }
}

/**
 * 单个文件条目
 *
 * @property file 文件对象
 * @property markedForDeletion 是否被标记为待删除
 * @property lastModified 文件最后修改时间戳
 */
data class FileEntry(
    val file: File,
    var markedForDeletion: Boolean = false,
    val lastModified: Long = file.lastModified()
) {
    /**
     * 文件路径字符串
     */
    val path: String get() = file.absolutePath

    /**
     * 格式化的修改时间
     */
    val formattedDate: String
        get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(lastModified))
}